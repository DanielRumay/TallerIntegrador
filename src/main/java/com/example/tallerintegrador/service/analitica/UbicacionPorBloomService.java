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
            String justificacion,
            /**
             * El patrón de respuesta viola el supuesto acumulativo (superó un estrato y falló
             * el inmediatamente inferior). Se expone para que el docente lo vea: no invalida
             * la ubicación, pero avisa de que esa medición es menos fiable.
             */
            boolean patronInconsistente
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
                    "Sin respuestas registradas: se ubica en el nivel inicial por defecto.",
                    false);
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

        // Escalograma: se busca el estrato más alto DOMINADO (ver `dominado`).
        NivelConocimiento nivel = NivelConocimiento.PRINCIPIANTE;
        String estratoAlcanzado = "ninguno";

        for (int i = 0; i < ESTRATOS.size(); i++) {
            if (!dominado(desempeno, i)) continue;
            estratoAlcanzado = ESTRATOS.get(i);
            nivel = switch (estratoAlcanzado) {
                case "Evaluar" -> NivelConocimiento.AVANZADO;
                case "Analizar" -> NivelConocimiento.INTERMEDIO;
                default -> NivelConocimiento.PRINCIPIANTE;
            };
        }

        boolean inconsistente = patronInconsistente(desempeno);

        String justificacion = estratoAlcanzado.equals("ninguno")
                ? "No alcanzó el umbral de %.0f%% en ningún estrato; se ubica en el nivel inicial."
                        .formatted(UMBRAL_DOMINIO * 100)
                : "Estrato más alto alcanzado: %s (≥%.0f%% de aciertos)."
                        .formatted(estratoAlcanzado, UMBRAL_DOMINIO * 100);

        if (inconsistente) {
            justificacion += " El patrón de respuestas no es acumulativo"
                    + " (superó un estrato y falló uno más básico), así que esta ubicación es"
                    + " menos fiable de lo habitual.";
        }

        log.info("[UBICACION-BLOOM] Desempeño={} → nivel={} (patronInconsistente={})",
                desempeno, nivel, inconsistente);
        return new ResultadoUbicacion(nivel, desempeno, justificacion, inconsistente);
    }

    private boolean alcanzado(Map<String, Double> desempeno, String estrato) {
        Double proporcion = desempeno.get(estrato);
        return proporcion != null && proporcion >= UMBRAL_DOMINIO;
    }

    /** true = superado, false = fallado, null = ese estrato no se preguntó. */
    private Boolean estado(Map<String, Double> desempeno, String estrato) {
        Double proporcion = desempeno.get(estrato);
        return proporcion == null ? null : proporcion >= UMBRAL_DOMINIO;
    }

    /**
     * Un estrato cuenta como DOMINADO si supera el umbral y además no es un dato aislado.
     *
     * POR QUÉ NO BASTA CON SUPERAR EL UMBRAL. Con dos reactivos por estrato, el umbral del
     * 50% se cumple acertando UNO. En opción múltiple de cuatro alternativas, alguien que
     * responde al azar acierta al menos uno de dos el 44% de las veces: casi una moneda al
     * aire podía promover a un alumno de estrato. Se vio en una prueba real con el patrón
     * Comprender 0% · Analizar 100% · Evaluar 0%, que ubicaba en INTERMEDIO a alguien que
     * había fallado TODO lo básico.
     *
     * LA REGLA. Un estrato superado cuyos vecinos con datos fallaron TODOS es la evidencia
     * más débil que existe —un acierto suelto rodeado de fallos— y no promueve por sí solo.
     * Si en cambio lo respalda el estrato inferior (patrón acumulativo normal) o el superior
     * (dominio demostrado por arriba), sí cuenta.
     *
     * POR QUÉ ESTO NO ROMPE EL ESCALOGRAMA. Guttman (1944) no exige perfección en los
     * estratos bajos, y eso se conserva: quien falla Comprender pero acierta Analizar Y
     * Evaluar sigue ubicándose en AVANZADO, porque cuatro aciertos consecutivos en lo difícil
     * no se explican por azar (≈0.4% al azar) y un fallo en lo fácil sí se explica por
     * descuido. Lo que se descarta es el caso contrario: un único acierto aislado.
     */
    private boolean dominado(Map<String, Double> desempeno, int indice) {
        String estrato = ESTRATOS.get(indice);
        if (!alcanzado(desempeno, estrato)) return false;

        List<Boolean> vecinos = new java.util.ArrayList<>();
        if (indice > 0) vecinos.add(estado(desempeno, ESTRATOS.get(indice - 1)));
        if (indice < ESTRATOS.size() - 1) vecinos.add(estado(desempeno, ESTRATOS.get(indice + 1)));

        List<Boolean> conDatos = vecinos.stream().filter(java.util.Objects::nonNull).toList();

        // Sin vecinos medidos no hay nada que contradiga: se respeta el umbral. Es el caso de
        // una prueba parcial, donde exigir respaldo castigaría por una pregunta que no se hizo.
        if (conDatos.isEmpty()) return true;

        boolean todosFallaron = conDatos.stream().noneMatch(Boolean::booleanValue);
        return !todosFallaron;
    }

    /**
     * El patrón viola el supuesto acumulativo: hay un estrato superado por encima de uno
     * fallado. Es el "error de Guttman" de toda la vida. No cambia la ubicación por sí mismo
     * —de eso se encarga `dominado`—, pero se informa para que el docente sepa que esa
     * medición concreta merece menos confianza.
     */
    private boolean patronInconsistente(Map<String, Double> desempeno) {
        boolean vistoFallo = false;
        for (String estrato : ESTRATOS) {
            Boolean e = estado(desempeno, estrato);
            if (e == null) continue;
            if (!e) vistoFallo = true;
            else if (vistoFallo) return true;
        }
        return false;
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
