package com.example.tallerintegrador.agents;

import com.example.tallerintegrador.entidades.postgres.Intento;
import com.example.tallerintegrador.entidades.postgres.NivelConocimiento;
import com.example.tallerintegrador.entidades.postgres.TipoEvaluacion;
import com.example.tallerintegrador.repository.IntentoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * VerificadorAgent — la guardia determinista del comité.
 *
 * El comité de agentes PROPONE un nivel; este verificador decide si la propuesta está
 * respaldada por evidencia dura del historial del alumno. Si no lo está, la veta y aplica
 * una política escrita en código.
 *
 * Deliberadamente NO usa el LLM: su valor es precisamente ser auditable y reproducible.
 * Es la respuesta a "¿y si la IA se equivoca al clasificar a mi alumno?".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificadorAgent {

    private final IntentoRepository intentoRepository;

    /** Nota mínima (escala vigesimal) sostenida para habilitar el nivel AVANZADO. */
    public static final double UMBRAL_AVANZADO = 16.0;
    /** Nota mínima del último intento para habilitar el nivel INTERMEDIO. */
    public static final double UMBRAL_INTERMEDIO = 11.0;
    /** Intentos consecutivos que deben sostener el umbral para subir a AVANZADO. */
    public static final int INTENTOS_SOSTENIDOS_AVANZADO = 2;

    public record Veredicto(
            NivelConocimiento nivelPropuesto,
            NivelConocimiento nivelAplicado,
            boolean vetado,
            String motivo
    ) {}

    /**
     * Contrasta la propuesta del comité con el historial real del alumno.
     *
     * @param usuarioId      alumno evaluado
     * @param nivelAnterior  nivel vigente antes de la deliberación
     * @param nivelPropuesto nivel que propuso el Coordinador
     */
    public Veredicto verificar(Long usuarioId, NivelConocimiento nivelAnterior, NivelConocimiento nivelPropuesto) {

        if (nivelPropuesto == null) {
            return new Veredicto(null, nivelAnterior != null ? nivelAnterior : NivelConocimiento.PRINCIPIANTE,
                    true, "El comité no emitió un nivel válido; se conserva el nivel vigente.");
        }

        NivelConocimiento anterior = nivelAnterior != null ? nivelAnterior : NivelConocimiento.PRINCIPIANTE;
        List<Double> notas = ultimasNotasFormativas(usuarioId);

        // Regla 1 — no se puede saltar dos niveles en una sola evaluación.
        if (anterior == NivelConocimiento.PRINCIPIANTE && nivelPropuesto == NivelConocimiento.AVANZADO) {
            return vetar(nivelPropuesto, NivelConocimiento.INTERMEDIO,
                    "Salto de dos niveles en una sola evaluación (PRINCIPIANTE → AVANZADO). Se aplica el nivel intermedio.");
        }

        // Regla 2 — AVANZADO exige rendimiento alto sostenido, no un único acierto.
        if (nivelPropuesto == NivelConocimiento.AVANZADO) {
            if (notas.size() < INTENTOS_SOSTENIDOS_AVANZADO) {
                return vetar(nivelPropuesto, anterior,
                        "AVANZADO requiere al menos %d evaluaciones formativas registradas; hay %d."
                                .formatted(INTENTOS_SOSTENIDOS_AVANZADO, notas.size()));
            }
            boolean sostenido = notas.stream()
                    .limit(INTENTOS_SOSTENIDOS_AVANZADO)
                    .allMatch(n -> n >= UMBRAL_AVANZADO);
            if (!sostenido) {
                return vetar(nivelPropuesto, NivelConocimiento.INTERMEDIO,
                        "AVANZADO requiere %.1f o más en las últimas %d evaluaciones; observado: %s."
                                .formatted(UMBRAL_AVANZADO, INTENTOS_SOSTENIDOS_AVANZADO, formatear(notas)));
            }
        }

        // Regla 3 — INTERMEDIO exige haber aprobado la última evaluación.
        if (nivelPropuesto == NivelConocimiento.INTERMEDIO && !notas.isEmpty() && notas.get(0) < UMBRAL_INTERMEDIO) {
            return vetar(nivelPropuesto, NivelConocimiento.PRINCIPIANTE,
                    "INTERMEDIO requiere %.1f o más en la última evaluación; observado: %.2f."
                            .formatted(UMBRAL_INTERMEDIO, notas.get(0)));
        }

        return new Veredicto(nivelPropuesto, nivelPropuesto, false, "Propuesta respaldada por el historial del alumno.");
    }

    private Veredicto vetar(NivelConocimiento propuesto, NivelConocimiento aplicado, String motivo) {
        log.warn("[VERIFICADOR] Veto: propuesto={} → aplicado={}. Motivo: {}", propuesto, aplicado, motivo);
        return new Veredicto(propuesto, aplicado, true, motivo);
    }

    /** Notas de las evaluaciones no diagnósticas, de la más reciente a la más antigua. */
    private List<Double> ultimasNotasFormativas(Long usuarioId) {
        if (usuarioId == null) return List.of();
        return intentoRepository.findByUsuarioIdOrderByFechaDesc(usuarioId).stream()
                .filter(i -> i.getTipoEvaluacion() != TipoEvaluacion.DIAGNOSTICA)
                .filter(i -> i.getNota() != null)
                .sorted(Comparator.comparing(Intento::getFecha, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(Intento::getNota)
                .toList();
    }

    private String formatear(List<Double> notas) {
        return notas.stream()
                .limit(INTENTOS_SOSTENIDOS_AVANZADO)
                .map(n -> String.format("%.2f", n))
                .reduce((a, b) -> a + ", " + b)
                .orElse("sin datos");
    }
}
