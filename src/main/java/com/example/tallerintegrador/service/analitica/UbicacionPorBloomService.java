package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.NivelConocimiento;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Ubicación del alumno por desempeño real en la jerarquía de Bloom.
 *
 * Reemplaza al ACRA como criterio para fijar NivelConocimiento. El motivo no es que el
 * ACRA sea un mal instrumento — es válido para lo que mide —, sino que medía otra cosa:
 * sus ítems preguntan "¿subrayas lo importante?", "¿repites mentalmente para memorizar?".
 * Eso son ESTRATEGIAS DE ESTUDIO. Derivar de ahí el nivel de dominio de Lenguaje y, con
 * él, la dificultad de los reactivos, es un salto entre dos constructos distintos: un
 * alumno con buenos hábitos y comprensión inferencial débil terminaba recibiendo preguntas
 * de nivel Evaluar que no podía resolver.
 *
 * Este servicio ubica al alumno con lo que sí predice la dificultad adecuada: si acierta o
 * no reactivos del propio curso en cada nivel cognitivo.
 *
 * Fundamento del criterio de corte: la Taxonomía Revisada de Bloom (Anderson y Krathwohl,
 * 2001) es acumulativa — dominar un nivel superior presupone los inferiores. Eso permite
 * una ubicación de tipo escalograma (Guttman, 1944): se ubica al alumno en el nivel MÁS
 * ALTO en el que alcanza el umbral de dominio, sin exigir perfección en los inferiores.
 */
@Slf4j
@Service
public class UbicacionPorBloomService {

    /** Proporción mínima de aciertos dentro de un nivel para considerarlo alcanzado. */
    public static final double UMBRAL_DOMINIO = 0.5;

    /** Reactivos por nivel en la prueba de ubicación. Con 2 por nivel son 6 en total. */
    public static final int REACTIVOS_POR_NIVEL = 2;

    /**
     * Los tres estratos de la prueba, del más bajo al más alto. Se agrupan los seis
     * niveles de Bloom en tres estratos porque distinguir "Analizar" de "Evaluar" con dos
     * reactivos no es estadísticamente sostenible: el error de medición sería mayor que la
     * diferencia entre niveles.
     */
    public static final List<String> ESTRATOS = List.of("Comprender", "Analizar", "Evaluar");

    /** Una respuesta de la prueba de ubicación: en qué nivel estaba el reactivo y si acertó. */
    public record RespuestaUbicacion(String nivelBloom, boolean acierto) {}

    public record ResultadoUbicacion(
            NivelConocimiento nivel,
            Map<String, Double> desempenoPorEstrato,
            String justificacion
    ) {}

    /**
     * Determina el nivel del alumno a partir de sus respuestas. Función pura: sin base de
     * datos, sin LLM, sin efectos de lado — se puede probar de forma exhaustiva, que es
     * justo lo que no se podía hacer con el criterio anterior.
     */
    public ResultadoUbicacion determinarNivel(List<RespuestaUbicacion> respuestas) {
        if (respuestas == null || respuestas.isEmpty()) {
            return new ResultadoUbicacion(
                    NivelConocimiento.PRINCIPIANTE,
                    Map.of(),
                    "Sin respuestas registradas: se ubica en el nivel inicial por defecto.");
        }

        Map<String, Double> desempeno = new java.util.LinkedHashMap<>();
        for (String estrato : ESTRATOS) {
            List<RespuestaUbicacion> delEstrato = respuestas.stream()
                    .filter(r -> estrato.equalsIgnoreCase(r.nivelBloom()))
                    .toList();
            if (!delEstrato.isEmpty()) {
                long aciertos = delEstrato.stream().filter(RespuestaUbicacion::acierto).count();
                desempeno.put(estrato, (double) aciertos / delEstrato.size());
            }
        }

        // Escalograma: se busca el estrato más alto alcanzado.
        NivelConocimiento nivel = NivelConocimiento.PRINCIPIANTE;
        String estratoAlcanzado = "ninguno";

        if (alcanzado(desempeno, "Comprender")) {
            nivel = NivelConocimiento.PRINCIPIANTE;
            estratoAlcanzado = "Comprender";
        }
        if (alcanzado(desempeno, "Analizar")) {
            nivel = NivelConocimiento.INTERMEDIO;
            estratoAlcanzado = "Analizar";
        }
        if (alcanzado(desempeno, "Evaluar")) {
            nivel = NivelConocimiento.AVANZADO;
            estratoAlcanzado = "Evaluar";
        }

        String justificacion = estratoAlcanzado.equals("ninguno")
                ? "No alcanzó el umbral de %.0f%% en ningún estrato; se ubica en el nivel inicial."
                        .formatted(UMBRAL_DOMINIO * 100)
                : "Estrato más alto alcanzado: %s (≥%.0f%% de aciertos)."
                        .formatted(estratoAlcanzado, UMBRAL_DOMINIO * 100);

        log.info("[UBICACION-BLOOM] Desempeño={} → nivel={}", desempeno, nivel);
        return new ResultadoUbicacion(nivel, desempeno, justificacion);
    }

    private boolean alcanzado(Map<String, Double> desempeno, String estrato) {
        Double proporcion = desempeno.get(estrato);
        return proporcion != null && proporcion >= UMBRAL_DOMINIO;
    }

    /**
     * Composición de la prueba de ubicación: qué nivel de Bloom pedirle al generador y
     * cuántos reactivos de cada uno. La consume AdaptiveLearningService para armar la
     * prueba con el pipeline RAG que ya existe.
     */
    public Map<String, Integer> composicionDeLaPrueba() {
        Map<String, Integer> composicion = new java.util.LinkedHashMap<>();
        for (String estrato : ESTRATOS) {
            composicion.put(estrato, REACTIVOS_POR_NIVEL);
        }
        return composicion;
    }
}
