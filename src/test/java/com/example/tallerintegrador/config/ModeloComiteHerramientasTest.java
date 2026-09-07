package com.example.tallerintegrador.config;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprueba contra la API REAL que el modelo del comite puede usar herramientas.
 *
 * POR QUE HACE FALTA ESTO. Los tres agentes del comite fallaban en cada deliberacion con
 *
 *   400 INVALID_ARGUMENT — "Function call is missing a thought_signature in functionCall parts"
 *
 * y el sistema caia a su respaldo enlatado guardando `uso_fallback = true`. Desde la interfaz
 * era invisible: la pantalla mostraba un debate y un nivel como si el comite hubiera
 * deliberado. Solo se vio consultando la tabla `debate_agentes`.
 *
 * La causa es que los Gemini 3.x con razonamiento devuelven una `thought_signature` junto a
 * cada llamada a funcion y exigen que se reenvie al entregar el resultado; la version de
 * LangChain4j en uso no la propaga. Por eso los agentes con herramientas se movieron a un
 * modelo aparte.
 *
 * Ninguna prueba con dobles puede validar eso: el fallo esta en el protocolo entre esta
 * aplicacion y la API de Google. Esta hace la llamada de verdad, con el mismo patron
 * (AiServices + `.tools(...)`) y el mismo modelo configurado, y falla si vuelve el 400.
 *
 * Se omite si no hay clave, para que un entorno sin credenciales no rompa la construccion.
 */
class ModeloComiteHerramientasTest {

    /** Debe coincidir con `langchain4j.google-ai-gemini.committee-model-name`. */
    private static final String MODELO_COMITE =
            System.getProperty("committee.model", "gemini-2.5-flash");

    /** Herramienta minima, del mismo estilo que HerramientasComite. */
    static class HistorialFalso {
        final AtomicInteger llamadas = new AtomicInteger();

        @Tool("Devuelve las ultimas notas del alumno indicado")
        String historialDeNotas(@P("id del alumno") int usuarioId) {
            llamadas.incrementAndGet();
            return "Notas recientes del alumno " + usuarioId + ": 12, 14, 11 sobre 20.";
        }
    }

    interface AgenteDePrueba {
        String opinar(String instruccion);
    }

    private String claveApi() {
        String k = System.getenv("GOOGLE_API_KEY");
        if (k == null || k.isBlank()) k = System.getenv("GEMINI_API_KEY");
        return k;
    }

    @Test
    @DisplayName("El modelo del comite completa un turno CON herramienta, sin error de thought_signature")
    void elModeloDelComiteUsaHerramientas() {
        String clave = claveApi();
        Assumptions.assumeTrue(clave != null && !clave.isBlank(),
                "Sin GOOGLE_API_KEY: se omite la comprobacion contra la API real.");

        // Las mismas banderas que lleva `committeeChatModel`. Se pueden apagar desde la linea
        // de comandos (-Dcommittee.thinking=false) para comprobar que son ELLAS las que
        // arreglan la propagacion de la firma, y no otra cosa.
        boolean conThinking = Boolean.parseBoolean(
                System.getProperty("committee.thinking", "true"));

        var constructor = GoogleAiGeminiChatModel.builder()
                .apiKey(clave)
                .modelName(MODELO_COMITE)
                .temperature(0.2)
                .maxRetries(1); // sin reintentos: se quiere ver el fallo, no taparlo

        if (conThinking) {
            constructor
                    .returnThinking(true)
                    .sendThinking(true)
                    .thinkingConfig(GeminiThinkingConfig.builder().thinkingBudget(0).build());
        }

        ChatModel modelo = constructor.build();

        HistorialFalso herramientas = new HistorialFalso();
        AgenteDePrueba agente = AiServices.builder(AgenteDePrueba.class)
                .chatModel(modelo)
                .tools(herramientas)
                .build();

        String respuesta = assertDoesNotThrow(
                () -> agente.opinar("Consulta el historial de notas del alumno 3 con la herramienta "
                        + "disponible y resume en una frase como va."),
                "El modelo '" + MODELO_COMITE + "' no pudo completar un turno con herramientas. "
                        + "Si el mensaje habla de 'thought_signature', ese modelo exige firmas de "
                        + "pensamiento que LangChain4j no propaga: hay que elegir otro.");

        // Que no lance no basta: si nunca llamo a la herramienta, el segundo turno —el que
        // fallaba— no llego a ocurrir y la prueba no habria demostrado nada.
        assertTrue(herramientas.llamadas.get() > 0,
                "el modelo no llego a invocar la herramienta, asi que el turno problematico no se probo");

        assertNotNull(respuesta);
        assertFalse(respuesta.isBlank(), "se esperaba una respuesta con contenido");
    }
}
