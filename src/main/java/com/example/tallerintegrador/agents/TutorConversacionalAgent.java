package com.example.tallerintegrador.agents;

import com.example.tallerintegrador.service.GeminiService;
import com.example.tallerintegrador.service.RagRetrieverService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TutorConversacionalAgent {

    private final GeminiService geminiService;
    private final RagRetrieverService ragRetrieverService;
    private final ObjectMapper mapper = new ObjectMapper();

    // -----------------------------------------------------------------------
    // PASO 1: Genera una pregunta para el avatar (Basada en Bloom)
    // -----------------------------------------------------------------------
    public Map<String, Object> generarPreguntaTutor(String tema, String mongoId, int turno) {
        log.info("[TUTOR] Generando pregunta de orden superior #{} sobre '{}'", turno, tema);

        // Recuperar contexto RAG estricto
        var chunks = ragRetrieverService.recuperar(tema, mongoId);
        String contexto = chunks.stream()
                .limit(3)
                .map(RagRetrieverService.ChunkRelevante::texto)
                .reduce("", (a, b) -> a + "\n\n" + b);

        if (contexto.trim().isEmpty()) {
            contexto = "[No se encontró contexto específico. Genera una pregunta analítica general sobre el tema].";
        }

        String prompt = PROMPT_PREGUNTA_TUTOR.formatted(tema, turno, contexto);
        String respuestaRaw = geminiService.askGemini(prompt).text();

        try {
            String clean = respuestaRaw
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "").trim();
            int s = clean.indexOf("{"), e = clean.lastIndexOf("}");
            if (s != -1 && e > s) clean = clean.substring(s, e + 1);

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(clean, Map.class);
            return parsed;
        } catch (Exception ex) {
            log.warn("[TUTOR] No se pudo parsear JSON, retornando raw");
            return Map.of("pregunta", respuestaRaw, "tipo", "analitica");
        }
    }

    // -----------------------------------------------------------------------
    // PASO 2: Analiza la respuesta oral del estudiante y streamea feedback SSE
    // -----------------------------------------------------------------------
    public void analizarRespuestaOral(
            String pregunta,
            String respuestaEstudiante,
            String tema,
            String nivelDificultad,
            SseEmitter emitter) {

        log.info("[TUTOR] Analizando respuesta oral del estudiante");

        String prompt = PROMPT_ANALISIS_ORAL.formatted(
                tema, pregunta, respuestaEstudiante, nivelDificultad);

        try {
            emitter.send(SseEmitter.event()
                    .name("avatar_state")
                    .data(mapper.writeValueAsString(Map.of("estado", "pensando"))));

            geminiService.askGeminiStream(prompt).forEach(chunk -> {
                try {
                    emitter.send(SseEmitter.event()
                            .name("feedback")
                            .data(chunk.text()));
                } catch (IOException e) {
                    log.warn("[TUTOR] Error SSE chunk: {}", e.getMessage());
                }
            });

            emitter.send(SseEmitter.event()
                    .name("avatar_state")
                    .data(mapper.writeValueAsString(Map.of("estado", "esperando"))));

            emitter.send(SseEmitter.event().name("done").data("ok"));
            emitter.complete();

        } catch (Exception e) {
            log.error("[TUTOR] Error analizando respuesta: {}", e.getMessage());
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            } catch (IOException ignored) {}
            emitter.completeWithError(e);
        }
    }

    // -----------------------------------------------------------------------
    // Prompts Mejorados con Rigor Académico
    // -----------------------------------------------------------------------

    private static final String PROMPT_PREGUNTA_TUTOR = """
        Actúa como ARIA, una tutora académica experta en la Dimensión del Proceso Cognitivo de la Taxonomía Revisada de Bloom (Anderson y Krathwohl, 2001).
        Tema de estudio: '%s'
        Turno de la sesión: %d
        
        Basándote ESTRICTAMENTE en este material del curso:
        ---
        %s
        ---
        
        Genera UNA sola pregunta de discusión que:
        1. Se sitúe en el nivel de ANALIZAR o EVALUAR (orden superior).
        2. Exija al estudiante diferenciar, integrar ideas o emitir juicios críticos sobre los conceptos exactos mencionados en el texto.
        3. PROHIBIDO crear escenarios ficticios infantiles, metáforas cotidianas forzadas o preguntas de "trabajo en grupo". Mantén el rigor académico del texto.
        4. Mantenga un tono conversacional, directo y retador, como un profesor universitario debatiendo con su alumno.
        
        Responde SOLO con JSON válido:
        {
          "pregunta": "texto de la pregunta aquí, máximo 2 oraciones",
          "concepto_clave": "el concepto académico principal que evalúa",
          "pista_si_no_responde": "una pista teórica breve si el alumno se bloquea",
          "tipo": "analitica"
        }
        """;

    private static final String PROMPT_ANALISIS_ORAL = """
        Eres ARIA, una tutora académica exigente pero motivadora. Acabas de hacer una pregunta sobre '%s' 
        y el estudiante respondió oralmente.
        
        PREGUNTA QUE HICISTE:
        %s
        
        RESPUESTA DEL ESTUDIANTE (transcripción de voz):
        "%s"
        
        Nivel de exigencia actual: %s
        
        INSTRUCCIONES para tu feedback:
        1. Comienza evaluando directamente su argumento (Ej: "Excelente análisis...", "Tienes un punto, pero...", "No exactamente...").
        2. Juzga si su razonamiento conecta adecuadamente con los conceptos del texto original.
        3. Amplía o corrige su idea con una explicación académica concisa (máximo 3 oraciones).
        4. Si acertó, hazle una micro-pregunta reflexiva de seguimiento. Si falló, aclara el concepto con el enfoque correcto.
        5. Cierra con una frase corta que lo impulse a seguir pensando.
        
        Tono: Universitario, analítico, cálido.
        Longitud total: entre 60 y 120 palabras (optimizado para TTS).
        NO uses listas, bullets, ni markdown. Solo prosa fluida.
        """;
}