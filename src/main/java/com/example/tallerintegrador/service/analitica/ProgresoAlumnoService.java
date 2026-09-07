package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.Intento;
import com.example.tallerintegrador.entidades.postgres.TipoEvaluacion;
import com.example.tallerintegrador.entidades.postgres.TurnoTutorSocratico;
import com.example.tallerintegrador.repository.DominioConceptoAlumnoRepository;
import com.example.tallerintegrador.repository.IntentoRepository;
import com.example.tallerintegrador.repository.TurnoTutorSocraticoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * Progreso del alumno: puntos, rango y marca personal.
 *
 * SEPARACIÓN DELIBERADA Y CENTRAL DE ESTE SERVICIO:
 *
 *   NIVEL DE DOMINIO  (PRINCIPIANTE / INTERMEDIO / AVANZADO)
 *       Sale del desempeño cognitivo: escalograma de Guttman sobre niveles de Bloom,
 *       deliberación del comité y veto determinista del Verificador.
 *       ES LO QUE EL SISTEMA USA PARA ELEGIR LA DIFICULTAD.
 *
 *   PROGRESO Y RANGO  (los puntos de esta clase)
 *       Sale de la actividad: practicar, acertar, ser constante, seguir las recomendaciones.
 *       SIRVE PARA MOTIVAR. No toca el nivel de dominio ni la dificultad. Nunca.
 *
 * Por qué importa tanto no mezclarlos: si los puntos decidieran el nivel, entonces lo
 * decidiría la CANTIDAD de actividad, y un alumno que practica mucho y mal alcanzaría el
 * nivel avanzado. Eso destruiría la validez del constructo — el sistema dejaría de medir
 * competencia para medir insistencia. La gamificación motiva; no debe evaluar.
 *
 * NO HAY TABLA NUEVA. Los puntos se CALCULAN a partir de registros que ya existen (intentos,
 * turnos con Aria, dominio BKT). Guardarlos aparte crearía dos versiones de la verdad que
 * podrían divergir; calcularlos garantiza que el número siempre refleje lo que el alumno hizo
 * de verdad.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProgresoAlumnoService {

    private final IntentoRepository intentoRepository;
    private final TurnoTutorSocraticoRepository turnoTutorRepository;
    private final DominioConceptoAlumnoRepository dominioRepository;

    // ── Cuánto vale cada cosa ────────────────────────────────────────────────
    /** Por terminar una evaluación, se acierte o no. Premia intentarlo. */
    static final int PUNTOS_POR_EVALUACION = 10;
    /** Por cada punto de nota sobre 20. Una nota de 15 da 15. */
    static final int PUNTOS_POR_PUNTO_DE_NOTA = 1;
    /** Por resolver una pregunta con Aria sin necesitar pistas (escalón 1). */
    static final int PUNTOS_RESOLVER_SOLO = 8;
    /** Por resolverla tras una repregunta. Menos, pero no cero: seguir intentándolo cuenta. */
    static final int PUNTOS_RESOLVER_CON_PISTA = 4;
    /** Por cada concepto que cruza el umbral de dominio. Es el que más vale. */
    static final int PUNTOS_POR_CONCEPTO_DOMINADO = 25;
    /** Por día consecutivo de práctica. */
    static final int PUNTOS_POR_DIA_DE_RACHA = 5;

    /** Probabilidad BKT a partir de la cual se considera dominado un concepto. */
    private static final double UMBRAL_DOMINIO = 0.75;

    public record Rango(String nombre, String emoji, int desde) {}

    /**
     * Los rangos son de PROGRESO, no de competencia. Se llaman a propósito con nombres que
     * no se confunden con los niveles de dominio (Principiante / Intermedio / Avanzado): si
     * compartieran nombre, el alumno —y el docente— creerían que subir de rango cambia la
     * dificultad de sus preguntas, y no es así.
     */
    static final List<Rango> RANGOS = List.of(
            new Rango("Explorador", "🌱", 0),
            new Rango("Constante", "🔥", 150),
            new Rango("Analista", "🧭", 400),
            new Rango("Estratega", "⚡", 800),
            new Rango("Maestro", "🏆", 1500));

    @Transactional(readOnly = true)
    public Map<String, Object> progresoDe(Long usuarioId) {
        List<Intento> intentos = intentoRepository.findByUsuarioIdOrderByFechaDesc(usuarioId).stream()
                .filter(i -> i.getTipoEvaluacion() != TipoEvaluacion.DIAGNOSTICA)
                .toList();

        List<TurnoTutorSocratico> turnos =
                turnoTutorRepository.findByUsuarioIdAndCerradoTrueOrderByFechaAsc(usuarioId);

        long conceptosDominados = dominioRepository.findByUsuarioIdOrderByConceptoAsc(usuarioId).stream()
                .filter(d -> d.getProbabilidadDominio() >= UMBRAL_DOMINIO)
                .count();

        int racha = calcularRacha(intentos);

        // ── Desglose, para que el alumno vea DE DÓNDE salen sus puntos ───────
        // Una cifra sin desglose es un número mágico; con desglose, es una explicación de qué
        // conviene hacer a continuación.
        int porEvaluaciones = intentos.size() * PUNTOS_POR_EVALUACION;

        int porNotas = intentos.stream()
                .filter(i -> i.getNota() != null)
                .mapToInt(i -> (int) Math.round(i.getNota() * PUNTOS_POR_PUNTO_DE_NOTA))
                .sum();

        int porAria = turnos.stream()
                .mapToInt(t -> t.getEscalonConsumido() <= 1 ? PUNTOS_RESOLVER_SOLO : PUNTOS_RESOLVER_CON_PISTA)
                .sum();

        int porConceptos = (int) conceptosDominados * PUNTOS_POR_CONCEPTO_DOMINADO;
        int porRacha = racha * PUNTOS_POR_DIA_DE_RACHA;

        int total = porEvaluaciones + porNotas + porAria + porConceptos + porRacha;

        // ── Progreso personal: la PENDIENTE, no el pico ──────────────────────
        //
        // Antes aquí solo estaba la mejor nota histórica, y como indicador motivacional es
        // flojo por dos razones: es un techo que deja de moverse (tras un buen día solo se
        // puede igualar) y un único intento con suerte lo fija para siempre.
        //
        // Lo que sostiene la motivación en alumnos de bajo rendimiento — la población de este
        // sistema — es la orientación al DOMINIO (¿estoy mejorando?) y no al DESEMPEÑO (¿cuál
        // es mi récord?). Ames (1992) sobre estructuras de meta en el aula; Elliot y McGregor
        // (2001) en el modelo 2x2 de metas de logro. Una marca máxima es puro desempeño; una
        // media que sube es dominio.
        //
        // Se promedian TRES intentos por lado para que un mal día no dibuje una caída que en
        // realidad no existe.
        List<Double> notas = intentos.stream()
                .filter(i -> i.getNota() != null)
                .map(Intento::getNota)
                .toList();

        Double mejorNota = notas.stream().max(Double::compareTo).orElse(null);

        // `intentos` viene del más reciente al más antiguo.
        Double mediaReciente = mediaDe(notas, 0, VENTANA_PROGRESO);
        Double mediaAnterior = mediaDe(notas, VENTANA_PROGRESO, VENTANA_PROGRESO * 2);
        Double progreso = (mediaReciente != null && mediaAnterior != null)
                ? redondear(mediaReciente - mediaAnterior)
                : null;

        Double ultimaNota = intentos.isEmpty() ? null : intentos.get(0).getNota();

        Rango actual = rangoDe(total);
        Rango siguiente = siguienteRango(total);

        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("puntos", total);
        salida.put("rango", actual.nombre());
        salida.put("rangoEmoji", actual.emoji());
        salida.put("siguienteRango", siguiente != null ? siguiente.nombre() : null);
        salida.put("puntosParaSiguiente", siguiente != null ? siguiente.desde() - total : null);
        salida.put("progresoEnRango", progresoEnRango(total, actual, siguiente));

        salida.put("mejorNota", mejorNota);
        salida.put("mediaReciente", mediaReciente);
        salida.put("mediaAnterior", mediaAnterior);
        // null mientras no haya suficientes intentos: la interfaz debe decir cuántos faltan
        // en vez de dibujar un progreso de cero, que se leería como estancamiento.
        salida.put("progreso", progreso);
        salida.put("intentosParaProgreso", Math.max(0, VENTANA_PROGRESO * 2 - notas.size()));
        salida.put("ultimaNota", ultimaNota);
        salida.put("superoSuMarca", mejorNota != null && ultimaNota != null
                && ultimaNota.doubleValue() >= mejorNota.doubleValue());

        salida.put("racha", racha);
        salida.put("conceptosDominados", conceptosDominados);
        salida.put("evaluacionesCompletadas", intentos.size());

        Map<String, Object> desglose = new LinkedHashMap<>();
        desglose.put("porEvaluaciones", porEvaluaciones);
        desglose.put("porNotas", porNotas);
        desglose.put("porAria", porAria);
        desglose.put("porConceptosDominados", porConceptos);
        desglose.put("porRacha", porRacha);
        salida.put("desglose", desglose);

        salida.put("aclaracion", "Los puntos miden tu constancia y tu esfuerzo. NO deciden la "
                + "dificultad de tus preguntas: eso lo determina lo que demuestras al responder.");
        return salida;
    }

    /**
     * Días consecutivos con al menos una evaluación, contando hacia atrás desde hoy.
     *
     * Se admite que el último día sea AYER y no hoy: una racha que se rompe a las 00:00 por no
     * haber practicado todavía castiga al alumno por levantarse tarde.
     */
    /** Intentos por lado de la comparación. Tres suaviza el ruido sin tardar un mes en moverse. */
    private static final int VENTANA_PROGRESO = 3;

    /**
     * Media de las notas en [desde, hasta) de una lista ordenada de más reciente a más antigua.
     * Devuelve null si no hay al menos un intento completo en ese tramo: con menos, la
     * comparación no significa nada y es mejor no enseñarla.
     */
    private Double mediaDe(List<Double> notas, int desde, int hasta) {
        if (notas.size() < hasta) return null;
        return redondear(notas.subList(desde, hasta).stream()
                .mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private Double redondear(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private int calcularRacha(List<Intento> intentos) {
        if (intentos.isEmpty()) return 0;

        Set<LocalDate> dias = new HashSet<>();
        for (Intento i : intentos) {
            if (i.getFecha() != null) dias.add(i.getFecha().toLocalDate());
        }
        if (dias.isEmpty()) return 0;

        LocalDate hoy = LocalDate.now();
        LocalDate cursor = dias.contains(hoy) ? hoy
                : dias.contains(hoy.minusDays(1)) ? hoy.minusDays(1)
                : null;
        if (cursor == null) return 0;

        int racha = 0;
        while (dias.contains(cursor)) {
            racha++;
            cursor = cursor.minusDays(1);
        }
        return racha;
    }

    static Rango rangoDe(int puntos) {
        Rango actual = RANGOS.get(0);
        for (Rango r : RANGOS) {
            if (puntos >= r.desde()) actual = r;
        }
        return actual;
    }

    static Rango siguienteRango(int puntos) {
        for (Rango r : RANGOS) {
            if (puntos < r.desde()) return r;
        }
        return null; // ya está en el más alto
    }

    /** 0 a 1 dentro del rango actual, para pintar la barra. */
    static double progresoEnRango(int puntos, Rango actual, Rango siguiente) {
        if (siguiente == null) return 1.0;
        int tramo = siguiente.desde() - actual.desde();
        if (tramo <= 0) return 1.0;
        return Math.min(1.0, Math.max(0.0, (puntos - actual.desde()) / (double) tramo));
    }
}
