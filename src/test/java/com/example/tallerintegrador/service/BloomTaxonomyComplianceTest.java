package com.example.tallerintegrador.service;
import com.example.tallerintegrador.service.ia.PromptTemplateService;
import com.example.tallerintegrador.service.metricas.TelemetriaIAService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de CONTRATO del prompt de generación frente a la Taxonomía Revisada de Bloom.
 *
 * Qué verifican: que el prompt que sale hacia el modelo contiene las directivas, los verbos
 * y el esquema de retorno correspondientes al nivel cognitivo solicitado. Eso es determinista
 * y por tanto se puede afirmar en CI.
 *
 * Qué NO verifican, y es importante no confundirlo: la tasa real de conformidad del OE1
 * (≥90% de reactivos conformes). Esa cifra exige llamadas reales al modelo y etiquetado
 * independiente por docentes sobre un conjunto de referencia; medirla con el LLM mockeado
 * para que devuelva la respuesta esperada solo comprueba que Jackson deserializa, y produce
 * un 100% que no significa nada. La serie continua de conformidad autodeclarada se recoge en
 * producción vía TelemetriaIAService y se consulta en GET /api/metricas/bloom.
 */
public class BloomTaxonomyComplianceTest {

    private final PromptTemplateService promptTemplateService = new PromptTemplateService();

    @Test
    @DisplayName("El prompt declara el nivel de Bloom objetivo y exige el esquema de autoevaluación")
    void testPromptStructureIncludesBloomDirectives() {
        String prompt = promptTemplateService.build(
                "STRUCTURED_OUTPUT", "OPCION_MULTIPLE", "Evaluar", "INTERMEDIO", "Texto de prueba", 1);

        assertNotNull(prompt);
        assertTrue(prompt.contains("Bloom"),
                "El prompt debe mencionar la taxonomía de Bloom");
        assertTrue(prompt.contains("NIVEL BLOOM OBJETIVO: Evaluar"),
                "El prompt debe fijar explícitamente el nivel cognitivo objetivo");
        assertTrue(prompt.contains("evaluacion_bloom"),
                "El prompt debe exigir el objeto de autoevaluación en el esquema de retorno");
        assertTrue(prompt.contains("nivel_bloom_orden"),
                "El esquema debe pedir el orden numérico del nivel, base del indicador de HOTS");
    }

    @ParameterizedTest(name = "Nivel {0} → orden {1}, hots={2}, verbo obligatorio ''{3}''")
    @DisplayName("Cada nivel de Bloom inyecta su orden, su marca de HOTS y sus verbos de acción")
    @CsvSource({
            "Recordar,   1, false, identificar",
            "Comprender, 2, false, parafrasear",
            "Aplicar,    3, false, ejecutar",
            "Analizar,   4, true,  Diferenciar",
            "Evaluar,    5, true,  criticar",
            "Crear,      6, true,  Diseñar"
    })
    void testDirectivasPorNivel(String nivel, int orden, boolean esHots, String verbo) {
        String prompt = promptTemplateService.build(
                "STRUCTURED_OUTPUT", "OPCION_MULTIPLE", nivel, "INTERMEDIO", "Texto de prueba", 1);

        assertTrue(prompt.contains("NIVEL BLOOM OBJETIVO: " + nivel + " (Nivel " + orden + ")"),
                "Debe declarar el nivel y su orden: " + nivel);
        assertTrue(prompt.contains("\"nivel_bloom_orden\": " + orden),
                "Debe fijar el orden esperado en el objeto evaluacion_bloom para " + nivel);
        assertTrue(prompt.contains("\"es_hots\": " + esHots),
                "Debe fijar la marca de orden superior para " + nivel);
        assertTrue(prompt.toLowerCase().contains(verbo.toLowerCase()),
                "Debe incluir los verbos de acción propios de " + nivel);
    }

    @Test
    @DisplayName("Los niveles de orden superior se marcan como HOTS y los básicos no")
    void testMarcaDeHots() {
        for (String bajo : new String[]{"Recordar", "Comprender", "Aplicar"}) {
            String prompt = promptTemplateService.build(
                    "STRUCTURED_OUTPUT", "ABIERTA", bajo, "FACIL", "Texto", 1);
            assertTrue(prompt.contains("\"es_hots\": false"), bajo + " no debe marcarse como HOTS");
        }
        for (String alto : new String[]{"Analizar", "Evaluar", "Crear"}) {
            String prompt = promptTemplateService.build(
                    "STRUCTURED_OUTPUT", "ABIERTA", alto, "PROFUNDIZACION", "Texto", 1);
            assertTrue(prompt.contains("\"es_hots\": true"), alto + " debe marcarse como HOTS");
        }
    }

    @Test
    @DisplayName("Sin nivel indicado, el generador apunta por defecto a orden superior")
    void testNivelPorDefectoApuntaAOrdenSuperior() {
        String prompt = promptTemplateService.build(
                "STRUCTURED_OUTPUT", "OPCION_MULTIPLE", null, "INTERMEDIO", "Texto", 1);
        assertTrue(prompt.contains("orden superior"),
                "Sin nivel explícito el prompt debe empujar hacia niveles de orden superior");
    }

    @Test
    @DisplayName("Las preguntas ya respondidas se inyectan como regla de exclusión")
    void testReglaDeExclusionDeDuplicados() {
        String yaVista = "¿Cuál es la función del sujeto en la oración?";
        String prompt = promptTemplateService.build(
                "STRUCTURED_OUTPUT", "OPCION_MULTIPLE", "Analizar", "INTERMEDIO", "Texto", 1,
                java.util.List.of(yaVista));

        assertTrue(prompt.contains("REGLA DE EXCLUSIÓN"),
                "Debe declararse la regla de exclusión cuando hay historial");
        assertTrue(prompt.contains(yaVista),
                "La pregunta ya respondida debe aparecer en la lista de exclusión");
    }

    @Test
    @DisplayName("El prompt prohíbe referencias a la estructura del documento original")
    void testProhibicionDeReferenciasExternas() {
        String prompt = promptTemplateService.build(
                "STRUCTURED_OUTPUT", "OPCION_MULTIPLE", "Comprender", "FACIL", "Texto", 1);
        assertTrue(prompt.contains("TERMINANTEMENTE PROHIBIDO"),
                "El reactivo debe ser autónomo respecto del documento fuente");
        assertTrue(prompt.contains("según la sección 3"),
                "Deben ejemplificarse las referencias externas prohibidas");
    }
}
