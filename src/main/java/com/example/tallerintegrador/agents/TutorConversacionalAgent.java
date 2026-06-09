package com.example.tallerintegrador.agents;

import com.example.tallerintegrador.service.GeminiService;
import com.example.tallerintegrador.service.RagRetrieverService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
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
            } catch (IOException ignored) {
            }
            emitter.complete();
        }
    }

    // -----------------------------------------------------------------------
    // PASO 2.5: Analiza la respuesta de audio directo del estudiante y streamea feedback SSE
    // -----------------------------------------------------------------------
    public void analizarAudioTutor(
            String pregunta,
            MultipartFile audio,
            String tema,
            String nivelDificultad,
            SseEmitter emitter) {

        long size = audio != null ? audio.getSize() : 0;
        String contentType = audio != null ? audio.getContentType() : "unknown";
        log.info("[TUTOR] Analizando audio del estudiante. Tamaño: {} bytes, Tipo: {}", size, contentType);

        String prompt = PROMPT_ANALISIS_AUDIO.formatted(
                tema, pregunta, nivelDificultad);

        try {
            emitter.send(SseEmitter.event()
                    .name("avatar_state")
                    .data(mapper.writeValueAsString(Map.of("estado", "pensando"))));

            log.info("[TUTOR] Enviando audio a Gemini...");
            geminiService.askGeminiStreamWithAudio(prompt, audio).forEach(chunk -> {
                try {
                    String chunkText = chunk.text();
                    log.info("[TUTOR] Chunk de Gemini: {}", chunkText);
                    emitter.send(SseEmitter.event()
                            .name("feedback")
                            .data(chunkText));
                } catch (IOException e) {
                    log.warn("[TUTOR] Error SSE chunk: {}", e.getMessage());
                }
            });

            log.info("[TUTOR] Gemini terminó de responder.");
            emitter.send(SseEmitter.event()
                    .name("avatar_state")
                    .data(mapper.writeValueAsString(Map.of("estado", "esperando"))));

            emitter.send(SseEmitter.event().name("done").data("ok"));
            emitter.complete();

        } catch (Exception e) {
            log.error("[TUTOR] Error analizando respuesta de audio: {}", e.getMessage(), e);
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            } catch (IOException ignored) {
            }
            emitter.complete();
        }
    }

    // -----------------------------------------------------------------------
    // Prompts Mejorados con Rigor Académico
    // -----------------------------------------------------------------------

    private static final String PROMPT_PREGUNTA_TUTOR = """
            Actúa como ARIA, una tutora educativa experta en la Taxonomía Revisada de Bloom.
            Tema de estudio: '%s'
            Turno de la sesión: %d
            Audiencia: Estudiantes de 2do grado de secundaria (13 a 14 años).
            
            Basándote ESTRICTAMENTE en este material del curso:
            ---
            %s
            ---
            
            Genera UNA sola pregunta de discusión que cumpla esto:
            1. Nivel de Bloom: ANALIZAR o EVALUAR. Exige al estudiante conectar ideas, deducir consecuencias o dar una opinión justificada sobre el texto.
            2. Lenguaje adaptado: Usa un vocabulario accesible, claro y directo para un adolescente. PROHIBIDO usar jerga académica densa (ej. no uses palabras como "interdependencia", "agencia moral", "estructura narrativa funcional").
            3. Enfoque: Traduce los conceptos complejos del texto a una pregunta retadora pero fácil de entender.
            4. Tono: Como una profesora joven, dinámica y empática que quiere hacer pensar a sus alumnos de secundaria.
            
            Responde SOLO con JSON válido:
            {
              "pregunta": "texto de la pregunta aquí, máximo 2 oraciones",
              "concepto_clave": "el concepto principal en palabras sencillas",
              "pista_si_no_responde": "una pista clara y amigable si el alumno se bloquea",
              "tipo": "analitica"
            }
            """;

    private static final String PROMPT_ANALISIS_ORAL = """
            Eres ARIA, una tutora dinámica y empática. Acabas de hacer una pregunta a un estudiante de 2do de secundaria (13-14 años) sobre '%s'.
            
            PREGUNTA QUE HICISTE:
            %s
            
            RESPUESTA DEL ESTUDIANTE (transcripción de voz):
            "%s"
            
            Nivel de exigencia actual: %s
            
            INSTRUCCIONES para tu feedback:
            1. Comienza validando su esfuerzo de forma natural (Ej: "¡Buen punto!", "Entiendo por qué dices eso, pero...").
            2. Juzga si su respuesta tiene sentido según el texto original. Explica con total claridad por qué está bien o qué le faltó para estar completa (según la puntuación de 1 a 4 estrellas que le vas a asignar), usando palabras sencillas.
            3. Explica con empatía qué estuvo bien o qué se puede mejorar. Si falló, guíalo hacia la respuesta correcta con un ejemplo fácil de entender. PROHIBIDO hacer preguntas abiertas o repreguntas al final; no debes dejar ninguna pregunta pendiente al estudiante en tu feedback.
            4. Cierra con una frase motivadora.
            5. Evalúa la respuesta del estudiante con una puntuación de 1 a 4 según su nivel de fundamentación y razonamiento:
               - 4: Excelente (respuesta muy bien fundamentada y razonada).
               - 3: Buena (fundamentada, pero con detalles menores por mejorar).
               - 2: Regular (poco fundamentada o incompleta).
               - 1: Deficiente (sin fundamentar o incorrecta).
               Debes colocar la puntuación al final de tu respuesta en este formato exacto: [PUNTUACION: X] (donde X es un número del 1 al 4).
            
            Tono: Amigable, claro, como una excelente profesora de secundaria.
            Longitud total: entre 60 y 120 palabras (optimizado para TTS).
            NO uses listas, bullets, ni markdown. Solo prosa fluida antes de la etiqueta [PUNTUACION: X].
            """;

    private static final String PROMPT_ANALISIS_AUDIO = """
            Eres ARIA, una tutora dinámica y empática. Acabas de hacer una pregunta a un estudiante de 2do de secundaria (13-14 años) sobre '%s'.
            
            PREGUNTA QUE HICISTE:
            %s
            
            Nivel de exigencia actual: %s
            
            Escucha el audio adjunto que contiene la respuesta hablada del estudiante.
            
            INSTRUCCIONES para tu feedback:
            1. Comienza validando su esfuerzo de forma natural (Ej: "¡Buen punto!", "Entiendo por qué dices eso, pero...").
            2. Juzga si su respuesta tiene sentido según el tema. Explica con total claridad por qué está bien o qué le faltó para estar completa (según la puntuación de 1 a 4 estrellas que le vas a asignar), usando palabras sencillas.
            3. Explica con empatía qué estuvo bien o qué se puede mejorar. Si falló, guíalo hacia la respuesta correcta con un ejemplo fácil de entender. PROHIBIDO hacer preguntas abiertas o repreguntas al final; no debes dejar ninguna pregunta pendiente al estudiante en tu feedback.
            4. Cierra con una frase motivadora.
            5. Evalúa la respuesta del estudiante con una puntuación de 1 a 4 según su nivel de fundamentación y razonamiento:
               - 4: Excelente (respuesta muy bien fundamentada y razonada).
               - 3: Buena (fundamentada, pero con detalles menores por mejorar).
               - 2: Regular (poco fundamentada o incompleta).
               - 1: Deficiente (sin fundamentar o incorrecta).
               Debes colocar la puntuación al final de tu respuesta en este formato exacto: [PUNTUACION: X] (donde X es un número del 1 al 4).
            
            Tono: Amigable, claro, como una excelente profesora de secundaria.
            Longitud total: entre 60 y 120 palabras (optimizado para TTS).
            NO uses listas, bullets, ni markdown. Solo prosa fluida antes de la etiqueta [PUNTUACION: X].
            """;
}