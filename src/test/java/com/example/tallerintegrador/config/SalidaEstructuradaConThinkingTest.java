package com.example.tallerintegrador.config;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprueba que activar `returnThinking` en el modelo de SALIDA ESTRUCTURADA no ensucia el
 * JSON.
 *
 * POR QUE. Al arreglar la propagacion de `thought_signature` se activaron `returnThinking` y
 * `sendThinking` tambien en `structuredOutputChatModel`, que NO usa herramientas: lo usan el
 * generador de preguntas, el juez y los criticos, y todos esperan un objeto bien formado.
 *
 * Si el texto del razonamiento se mezclara con la respuesta, el parseo fallaria — y fallaria
 * en produccion, no aqui, porque ninguna prueba con dobles toca la API real. La duda se
 * resuelve haciendo la llamada de verdad y comprobando que el objeto llega completo.
 *
 * Se omite si no hay clave.
 */
class SalidaEstructuradaConThinkingTest {

    record Veredicto(String nivel, int puntuacion, String justificacion) {}

    interface EvaluadorDePrueba {
        Veredicto evaluar(String texto);
    }

    private String claveApi() {
        String k = System.getenv("GOOGLE_API_KEY");
        if (k == null || k.isBlank()) k = System.getenv("GEMINI_API_KEY");
        return k;
    }

    @Test
    @DisplayName("Con returnThinking activo, la salida estructurada sigue llegando parseable")
    void laSalidaEstructuradaSigueSiendoValida() {
        String clave = claveApi();
        Assumptions.assumeTrue(clave != null && !clave.isBlank(),
                "Sin GOOGLE_API_KEY: se omite la comprobacion contra la API real.");

        // Exactamente la configuracion de `structuredOutputChatModel`.
        ChatModel modelo = GoogleAiGeminiChatModel.builder()
                .apiKey(clave)
                .modelName(System.getProperty("structured.model", "gemini-3.1-flash-lite"))
                .temperature(0.2)
                .maxRetries(1)
                .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
                .returnThinking(Boolean.parseBoolean(System.getProperty("structured.thinking", "true")))
                .sendThinking(Boolean.parseBoolean(System.getProperty("structured.thinking", "true")))
                .build();

        EvaluadorDePrueba evaluador = AiServices.builder(EvaluadorDePrueba.class)
                .chatModel(modelo)
                .build();

        Veredicto v = assertDoesNotThrow(
                () -> evaluador.evaluar("El alumno explico que el profesor favorecia a Humberto "
                        + "porque su padre tenia dinero. Da un nivel, una puntuacion del 1 al 4 y "
                        + "una justificacion breve."),
                "La salida estructurada dejo de ser parseable con returnThinking activo");

        assertNotNull(v, "el objeto no puede venir nulo");
        assertNotNull(v.nivel());
        assertNotNull(v.justificacion());

        // Si el razonamiento se hubiera colado en el texto, estos campos vendrian vacios o con
        // basura en vez del contenido pedido.
        assertFalse(v.justificacion().isBlank(), "la justificacion llego vacia");
        assertTrue(v.puntuacion() >= 0, "la puntuacion no se pudo leer");
    }
}
