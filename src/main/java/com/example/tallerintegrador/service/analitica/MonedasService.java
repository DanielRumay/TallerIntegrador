package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.Intento;
import com.example.tallerintegrador.entidades.postgres.MovimientoMonedas;
import com.example.tallerintegrador.entidades.postgres.TipoEvaluacion;
import com.example.tallerintegrador.entidades.postgres.TurnoTutorSocratico;
import com.example.tallerintegrador.repository.IntentoRepository;
import com.example.tallerintegrador.repository.MovimientoMonedasRepository;
import com.example.tallerintegrador.repository.TurnoTutorSocraticoRepository;
import com.example.tallerintegrador.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * Economía de monedas: cuánto tiene un alumno, cuánto puede ganar hoy y cómo gasta.
 *
 * SALDO = lo ganado practicando (calculado) + premios únicos − gastos (los dos últimos en el
 * libro de movimientos). Lo de practicar se calcula y no se guarda, igual que los puntos: así
 * cambiar una regla se aplica a todo el historial sin migrar datos.
 *
 * DOS REGLAS CONTRA EL "FARMEO" (practicar rápido y sin esfuerzo solo por las monedas):
 *  - Tope diario: las primeras 5 evaluaciones del día pagan completo, de la 6.ª a la 9.ª la
 *    mitad y desde la 10.ª nada. La XP sí sigue sumando: practicar mucho nunca se castiga, solo
 *    deja de dar monedas.
 *  - Esfuerzo mínimo: una evaluación terminada en menos de 20 segundos no paga.
 *
 * Todas las fuentes calculadas son monótonas (intentos y turnos no se borran, la racha más
 * larga de la historia no baja), así el saldo nunca disminuye solo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonedasService {

    private final IntentoRepository intentoRepository;
    private final TurnoTutorSocraticoRepository turnoRepository;
    private final MovimientoMonedasRepository movimientoRepository;
    private final UserRepository userRepository;

    static final int MONEDAS_POR_EVALUACION = 5;
    static final int MONEDAS_NOTA_ALTA = 3;
    static final double NOTA_ALTA = 14.0;
    static final int MONEDAS_ARIA_SOLO = 3;
    static final int MONEDAS_ARIA_CON_PISTA = 1;

    /** Evaluaciones del día que pagan completo; las siguientes hasta MAX_CON_MONEDAS_POR_DIA pagan la mitad. */
    static final int COMPLETAS_POR_DIA = 5;
    static final int MAX_CON_MONEDAS_POR_DIA = 9;
    static final int SEGUNDOS_MINIMOS = 20;
    /** Turnos con Aria que pagan al día. */
    static final int ARIA_POR_DIA = 10;

    /** Hitos de racha: días seguidos → monedas, una sola vez en la vida del alumno. */
    static final SortedMap<Integer, Integer> HITOS_RACHA = new TreeMap<>(Map.of(7, 20, 30, 50, 100, 150));

    public record Desglose(int practica, int aria, int rachas, int premios, int gastos) {
        public int saldo() { return practica + aria + rachas + premios + gastos; }
    }

    @Transactional(readOnly = true)
    public Desglose desglose(Long usuarioId) {
        List<Intento> intentos = intentosQueCuentan(usuarioId);
        List<TurnoTutorSocratico> turnos = turnoRepository.findByUsuarioIdAndCerradoTrueOrderByFechaAsc(usuarioId);
        return new Desglose(
                monedasPorPractica(intentos),
                monedasPorAria(turnos),
                monedasPorHitos(rachaMasLarga(intentos)),
                movimientoRepository.sumaPorTipo(usuarioId, MovimientoMonedas.Tipo.PREMIO),
                movimientoRepository.sumaPorTipo(usuarioId, MovimientoMonedas.Tipo.GASTO));
    }

    public int saldo(Long usuarioId) {
        return desglose(usuarioId).saldo();
    }

    /** Lo que el alumno ve en su billetera: saldo, de dónde sale, cuánto le queda hoy y lo último. */
    @Transactional(readOnly = true)
    public Map<String, Object> billetera(Long usuarioId) {
        Desglose d = desglose(usuarioId);
        List<Intento> intentos = intentosQueCuentan(usuarioId);
        long hoyConMonedas = intentos.stream()
                .filter(i -> i.getFecha() != null && i.getFecha().toLocalDate().equals(LocalDate.now()))
                .filter(MonedasService::conEsfuerzo)
                .count();
        int racha = rachaMasLarga(intentos);

        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("saldo", d.saldo());
        salida.put("desglose", Map.of(
                "practica", d.practica(), "aria", d.aria(), "rachas", d.rachas(),
                "premios", d.premios(), "gastos", d.gastos()));
        salida.put("hoyCompletasRestantes", (int) Math.max(0, COMPLETAS_POR_DIA - hoyConMonedas));
        salida.put("hoyMediasRestantes", (int) Math.max(0,
                MAX_CON_MONEDAS_POR_DIA - Math.max(COMPLETAS_POR_DIA, hoyConMonedas)));
        salida.put("rachaMasLarga", racha);
        salida.put("siguienteHito", HITOS_RACHA.keySet().stream().filter(h -> h > racha).findFirst().orElse(null));
        salida.put("movimientos", movimientoRepository.findTop20ByUsuarioIdOrderByFechaDesc(usuarioId).stream()
                .map(m -> Map.of("cantidad", m.getCantidad(), "motivo", m.getMotivo(), "fecha", m.getFecha().toString()))
                .toList());
        return salida;
    }

    // ── Cálculo ─────────────────────────────────────────────────────────────

    private List<Intento> intentosQueCuentan(Long usuarioId) {
        return intentoRepository.findByUsuarioIdOrderByFechaDesc(usuarioId).stream()
                .filter(i -> i.getTipoEvaluacion() != TipoEvaluacion.DIAGNOSTICA)
                .toList();
    }

    /** Sin tiempo registrado se da por bueno: los intentos antiguos no lo guardaban. */
    static boolean conEsfuerzo(Intento i) {
        return i.getTiempoEmpleadoSegundos() == null || i.getTiempoEmpleadoSegundos() >= SEGUNDOS_MINIMOS;
    }

    static int monedasPorPractica(List<Intento> intentos) {
        Map<LocalDate, Integer> delDia = new HashMap<>();
        int total = 0;
        List<Intento> orden = intentos.stream()
                .filter(i -> i.getFecha() != null)
                .filter(MonedasService::conEsfuerzo)
                .sorted(Comparator.comparing(Intento::getFecha))
                .toList();
        for (Intento i : orden) {
            int n = delDia.merge(i.getFecha().toLocalDate(), 1, Integer::sum);
            if (n > MAX_CON_MONEDAS_POR_DIA) continue;
            int monedas = MONEDAS_POR_EVALUACION
                    + (i.getNota() != null && i.getNota() >= NOTA_ALTA ? MONEDAS_NOTA_ALTA : 0);
            total += n <= COMPLETAS_POR_DIA ? monedas : monedas / 2;
        }
        return total;
    }

    static int monedasPorAria(List<TurnoTutorSocratico> turnos) {
        Map<LocalDate, Integer> delDia = new HashMap<>();
        int total = 0;
        for (TurnoTutorSocratico t : turnos) {
            if (t.getFecha() != null && delDia.merge(t.getFecha().toLocalDate(), 1, Integer::sum) > ARIA_POR_DIA) continue;
            total += t.getEscalonConsumido() <= 1 ? MONEDAS_ARIA_SOLO : MONEDAS_ARIA_CON_PISTA;
        }
        return total;
    }

    /** La racha más larga de toda la historia: a diferencia de la actual, nunca baja. */
    static int rachaMasLarga(List<Intento> intentos) {
        TreeSet<LocalDate> dias = new TreeSet<>();
        for (Intento i : intentos) if (i.getFecha() != null) dias.add(i.getFecha().toLocalDate());
        int mejor = 0, actual = 0;
        LocalDate anterior = null;
        for (LocalDate d : dias) {
            actual = anterior != null && anterior.plusDays(1).equals(d) ? actual + 1 : 1;
            mejor = Math.max(mejor, actual);
            anterior = d;
        }
        return mejor;
    }

    static int monedasPorHitos(int rachaMasLarga) {
        return HITOS_RACHA.entrySet().stream()
                .filter(e -> rachaMasLarga >= e.getKey())
                .mapToInt(Map.Entry::getValue).sum();
    }

    // ── Movimientos ─────────────────────────────────────────────────────────

    /**
     * Premio único. Devuelve false si esa clave ya se cobró. Va en su propia transacción para
     * que un choque con la restricción única no arrastre a quien lo llamó.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean premiar(Long usuarioId, int cantidad, String motivo, String clave) {
        if (cantidad <= 0) throw new IllegalArgumentException("Un premio debe ser positivo.");
        if (movimientoRepository.existsByUsuarioIdAndClave(usuarioId, clave)) return false;
        try {
            movimientoRepository.saveAndFlush(new MovimientoMonedas(usuarioId, cantidad, MovimientoMonedas.Tipo.PREMIO, motivo, clave));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false; // otra petición lo cobró en el mismo instante
        }
    }

    /**
     * Gasta monedas si alcanzan. Bloquea la fila del alumno durante la compra: sin el bloqueo,
     * dos compras simultáneas leerían el mismo saldo y ambas pasarían. Devuelve el saldo nuevo.
     */
    @Transactional
    public int gastar(Long usuarioId, int cantidad, String motivo) {
        return gastar(usuarioId, cantidad, motivo, null);
    }

    /**
     * Con clave, la compra es única (un accesorio no se compra dos veces). Se comprueba después
     * de bloquear, así dos clics seguidos no cobran dos veces.
     */
    @Transactional
    public int gastar(Long usuarioId, int cantidad, String motivo, String clave) {
        if (cantidad <= 0) throw new IllegalArgumentException("El precio debe ser positivo.");
        userRepository.bloquearPorId(usuarioId).orElseThrow();
        if (clave != null && movimientoRepository.existsByUsuarioIdAndClave(usuarioId, clave)) {
            throw new IllegalStateException("Ya lo tienes.");
        }
        int saldo = saldo(usuarioId);
        if (saldo < cantidad) {
            throw new IllegalStateException("No te alcanzan las monedas: tienes " + saldo + " y necesitas " + cantidad + ".");
        }
        movimientoRepository.save(new MovimientoMonedas(usuarioId, -cantidad, MovimientoMonedas.Tipo.GASTO, motivo, clave));
        return saldo - cantidad;
    }
}
