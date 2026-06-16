package com.example.tallerintegrador.agents;

import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.service.PreguntaDedupService;
import com.example.tallerintegrador.service.GeminiService;
import com.example.tallerintegrador.service.RagRetrieverService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.embedding.Embedding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

@Slf4j
@Service
public class TutorConversacionalAgent {

    private final GeminiService geminiService;
    private final RagRetrieverService ragRetrieverService;
    private final PreguntaDedupService preguntaDedupService;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> questionsEmbeddingStore;
    private final ObjectMapper mapper = new ObjectMapper();

    public TutorConversacionalAgent(
            GeminiService geminiService,
            RagRetrieverService ragRetrieverService,
            PreguntaDedupService preguntaDedupService,
            EmbeddingModel embeddingModel,
            @Qualifier("questionsEmbeddingStore") EmbeddingStore<TextSegment> questionsEmbeddingStore) {
        this.geminiService = geminiService;
        this.ragRetrieverService = ragRetrieverService;
        this.preguntaDedupService = preguntaDedupService;
        this.embeddingModel = embeddingModel;
        this.questionsEmbeddingStore = questionsEmbeddingStore;
    }

    // -----------------------------------------------------------------------
    // PASO 1: Genera una pregunta para el avatar (Basada en Bloom)
    // -----------------------------------------------------------------------
    public Map<String, Object> generarPreguntaTutor(String tema, String mongoId, int turno, String userEmail) {
        log.info("[TUTOR] Generando pregunta de orden superior #{} sobre '{}' para el usuario '{}'", turno, tema, userEmail);

        // Recuperar contexto RAG estricto
        var chunks = ragRetrieverService.recuperar(tema, mongoId);
        String contexto = chunks.stream()
                .limit(3)
                .map(RagRetrieverService.ChunkRelevante::texto)
                .reduce("", (a, b) -> a + "\n\n" + b);

        if (contexto.trim().isEmpty()) {
            contexto = "[No se encontró contexto específico. Genera una pregunta analítica general sobre el tema].";
        }

        Usuario usuario = preguntaDedupService.obtenerUsuarioPorEmail(userEmail);
        Long usuarioId = usuario != null ? usuario.getId() : null;
        List<String> preguntasEvitar = preguntaDedupService.obtenerPreguntasEvitar(userEmail, mongoId);
        List<String> avoidList = new ArrayList<>(preguntasEvitar);

        String promptExclusion = "";
        if (!avoidList.isEmpty()) {
            promptExclusion = "\nEVITA formular preguntas idénticas o semánticamente muy similares a cualquiera de estas:\n" 
                    + String.join("\n", avoidList) + "\n";
        }

        int attempts = 0;
        Map<String, Object> parsed = null;
        String finalPreguntaTexto = null;

        while (attempts < 3) {
            attempts++;
            String prompt = PROMPT_PREGUNTA_TUTOR.formatted(tema, turno, contexto) + promptExclusion;
            String respuestaRaw = geminiService.askGemini(prompt).text();

            try {
                String clean = respuestaRaw
                        .replaceAll("(?s)```json\\s*", "")
                        .replaceAll("(?s)```\\s*", "").trim();
                int s = clean.indexOf("{"), e = clean.lastIndexOf("}");
                if (s != -1 && e > s) clean = clean.substring(s, e + 1);

                @SuppressWarnings("unchecked")
                Map<String, Object> candidateMap = mapper.readValue(clean, Map.class);
                String preguntaEnunciado = (String) candidateMap.get("pregunta");

                if (preguntaEnunciado != null && !preguntaEnunciado.trim().isEmpty()) {
                    // Validar con Qdrant similitud vectorial y con la lista local
                    if (!avoidList.contains(preguntaEnunciado) && !preguntaDedupService.esPreguntaSimilar(preguntaEnunciado, usuarioId, avoidList)) {
                        parsed = candidateMap;
                        finalPreguntaTexto = preguntaEnunciado;
                        break; // Única y válida
                    } else {
                        log.warn("[DEDUP-TUTOR] Pregunta duplicada/similar rechazada o duplicado exacto: {}", preguntaEnunciado);
                        // Añadir al avoidList local para el siguiente prompt
                        avoidList.add(preguntaEnunciado);
                        promptExclusion = "\nEVITA formular preguntas idénticas o semánticamente muy similares a cualquiera de estas:\n" 
                                + String.join("\n", avoidList) + "\n";
                    }
                }
            } catch (Exception ex) {
                log.warn("[TUTOR] Error parseando JSON en intento {}: {}", attempts, ex.getMessage());
            }
        }

        // Fallback si no fue posible encontrar una pregunta única en 3 intentos
        if (parsed == null) {
            log.warn("[DEDUP-TUTOR] Fallback: No se encontró una pregunta única tras 3 intentos. Generando sin restricciones vectoriales.");
            String prompt = PROMPT_PREGUNTA_TUTOR.formatted(tema, turno, contexto);
            String respuestaRaw = geminiService.askGemini(prompt).text();
            try {
                String clean = respuestaRaw
                        .replaceAll("(?s)```json\\s*", "")
                        .replaceAll("(?s)```\\s*", "").trim();
                int s = clean.indexOf("{"), e = clean.lastIndexOf("}");
                if (s != -1 && e > s) clean = clean.substring(s, e + 1);
                @SuppressWarnings("unchecked")
                Map<String, Object> candidateMap = mapper.readValue(clean, Map.class);
                parsed = candidateMap;
                finalPreguntaTexto = (String) candidateMap.get("pregunta");
            } catch (Exception ex) {
                parsed = Map.of("pregunta", respuestaRaw, "tipo", "analitica");
                finalPreguntaTexto = respuestaRaw;
            }
        }

        // Guardar la pregunta en Qdrant para evitar repeticiones futuras
        if (finalPreguntaTexto != null && !finalPreguntaTexto.trim().isEmpty() && usuarioId != null) {
            try {
                Long semanaId = preguntaDedupService.obtenerSemanaIdPorMongoId(mongoId);
                Response<Embedding> embResponse = embeddingModel.embed(finalPreguntaTexto);
                Metadata meta = new Metadata();
                meta.put("usuarioId", String.valueOf(usuarioId));
                meta.put("tipo", "pregunta");
                if (semanaId != null) {
                    meta.put("semanaId", String.valueOf(semanaId));
                }
                questionsEmbeddingStore.add(embResponse.content(), TextSegment.from(finalPreguntaTexto, meta));
                log.info("[DEDUP-TUTOR] Pregunta de tutoría indexada en Qdrant con éxito.");
            } catch (Exception ex) {
                log.error("Error al indexar la pregunta del tutor en Qdrant: {}", ex.getMessage());
            }
        }

        return parsed;
    }

    // -----------------------------------------------------------------------
    // PASO 2: Analiza la respuesta oral del estudiante y streamea feedback SSE
    // -----------------------------------------------------------------------
    private boolean esEvasionONoRespuesta(String respuesta) {
        if (respuesta == null || respuesta.trim().isEmpty()) return true;
        String normalized = respuesta.toLowerCase()
                .replaceAll("[áàäâ]", "a")
                .replaceAll("[éèëê]", "e")
                .replaceAll("[íìïî]", "i")
                .replaceAll("[óòöô]", "o")
                .replaceAll("[úùüû]", "u")
                .trim();
        return normalized.contains("no se")
                || normalized.contains("ni idea")
                || normalized.contains("explicame")
                || normalized.contains("ayudame")
                || normalized.contains("no entiendo")
                || normalized.contains("nose")
                || normalized.contains("dime la respuesta")
                || normalized.contains("ayuda");
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

        if (esEvasionONoRespuesta(respuestaEstudiante)) {
            prompt += "\n\nAVISO CRÍTICO DE CONTROL DE EVASIÓN: La respuesta del estudiante es '" + respuestaEstudiante + "'. Esto califica estrictamente como Caso A (evasión/solicitud de ayuda). Asigna obligatoriamente [PUNTUACION: 1], NO lo felicites ni valides su esfuerzo de ninguna manera. Comienza obligatoriamente con un mensaje empático y motivador (ej. 'No te preocupes si no lo sabes, ¡el aprendizaje es un camino constante y estamos aquí para aprender juntos!' o similar) y luego explícales de forma amigable el concepto correcto.";
        }

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
            1. REGULA EL FEEDBACK SEGÚN LA RESPUESTA:
               - CASO A: SI LA RESPUESTA ES UNA EVASIÓN, DUDA, NO SABE (ej. "no sé", "ni idea", "no entiendo"), PIDE AYUDA O EXPLICACIÓN (ej. "explícame", "ayúdame", "dime la respuesta", "explícame chola"), O ES UN TEXTO IRRELEVANTE/VACÍO:
                 * Asigna obligatoriamente la puntuación mínima: [PUNTUACION: 1].
                 * PROHIBIDO felicitar, validar esfuerzo o decir cosas como "¡Exacto!", "¡Buen punto!" o "Excelente".
                 * Comienza OBLIGATORIAMENTE con un mensaje empático y motivador que transmita tranquilidad (por ejemplo: "¡No te preocupes si no lo sabes, el aprendizaje es un camino constante y estamos aquí para aprender juntos!" o "No te preocupes si no sabes, ¡poco a poco iremos aprendiendo!" o similar). Luego, explícale de forma muy amigable y didáctica la respuesta correcta del concepto para que aprenda.
               - CASO B: SI EL ESTUDIANTE INTENTA RESPONDER LA PREGUNTA:
                 * Comienza validando su esfuerzo de forma natural (Ej: "¡Buen punto!", "Entiendo por qué dices eso, pero...").
                 * Juzga si su respuesta tiene sentido según el texto original. Explica con total claridad qué estuvo bien o qué le faltó.
                 * Asigna una puntuación del 1 al 4 estrellas:
                   - 4: Excelente (respuesta muy bien fundamentada y razonada).
                   - 3: Buena (fundamentada, pero con detalles menores por mejorar).
                   - 2: Regular (poco fundamentada o incompleta).
                   - 1: Deficiente (incorrecta o sin sentido).
            
            2. REGLAS GENERALES:
               - Explica con empatía qué estuvo bien o qué se puede mejorar. Si falló, guíalo hacia la respuesta correcta con un ejemplo fácil de entender.
               - PROHIBIDO hacer preguntas abiertas o repreguntas al final; no debes dejar ninguna pregunta pendiente al estudiante en tu feedback.
               - Cierra con una frase motivadora.
               - Debes colocar la puntuación al final de tu respuesta en este formato exacto: [PUNTUACION: X] (donde X es un número del 1 al 4).
            
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
            1. REGULA EL FEEDBACK SEGÚN LA RESPUESTA:
               - CASO A: SI EL AUDIO INDICA QUE EL ALUMNO NO SABE (ej. "no sé", "ni idea", "no entiendo"), PIDE AYUDA O EXPLICACIÓN (ej. "explícame", "ayúdame", "dime la respuesta"), O ES UN AUDIO IRRELEVANTE/VACÍO:
                 * Asigna obligatoriamente la puntuación mínima: [PUNTUACION: 1].
                 * PROHIBIDO felicitar, validar esfuerzo o decir cosas como "¡Exacto!", "¡Buen punto!" o "Excelente".
                 * Comienza OBLIGATORIAMENTE con un mensaje empático y motivador que transmita tranquilidad (por ejemplo: "¡No te preocupes si no lo sabes, el aprendizaje es un camino constante y estamos aquí para aprender juntos!" o "No te preocupes si no sabes, ¡poco a poco iremos aprendiendo!" o similar). Luego, explícale de forma muy amigable y didáctica la respuesta correcta del concepto para que aprenda.
               - CASO B: SI EL ESTUDIANTE INTENTA RESPONDER LA PREGUNTA EN EL AUDIO:
                 * Comienza validando su esfuerzo de forma natural (Ej: "¡Buen punto!", "Entiendo por qué dices eso, pero...").
                 * Juzga si su respuesta tiene sentido según el tema. Explica con total claridad qué estuvo bien o qué le faltó.
                 * Asigna una puntuación del 1 al 4 estrellas:
                   - 4: Excelente (respuesta muy bien fundamentada y razonada).
                   - 3: Buena (fundamentada, pero con detalles menores por mejorar).
                   - 2: Regular (poco fundamentada o incompleta).
                   - 1: Deficiente (incorrecta o sin sentido).
            
            2. REGLAS GENERALES:
               - Explica con empatía qué estuvo bien o qué se puede mejorar. Si falló, guíalo hacia la respuesta correcta con un ejemplo fácil de entender.
               - PROHIBIDO hacer preguntas abiertas o repreguntas al final; no debes dejar ninguna pregunta pendiente al estudiante en tu feedback.
               - Cierra con una frase motivadora.
               - Debes colocar la puntuación al final de tu respuesta en este formato exacto: [PUNTUACION: X] (donde X es un número del 1 al 4).
            
            Tono: Amigable, claro, como una excelente profesora de secundaria.
            Longitud total: entre 60 y 120 palabras (optimizado para TTS).
            NO uses listas, bullets, ni markdown. Solo prosa fluida antes de la etiqueta [PUNTUACION: X].
            """;
}