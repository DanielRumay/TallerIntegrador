package com.example.tallerintegrador.service.ia;

import com.example.tallerintegrador.repository.UserRepository;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiService {

    private final Client client;
    private final UserRepository userRepository;

    @Value("${langchain4j.google-ai-gemini.chat-model.model-name:gemini-3.1-flash-lite}")
    private String primaryModel;

    @Value("${gemini.chat-model.fallback-name:gemini-2.5-flash}")
    private String fallbackModel;

    @Value("${app.gemini.simulado:false}")
    private boolean geminiSimulado;

    private GenerateContentResponse generateWithRetryAndFallback(String model, Object contents) {
        int maxAttempts = 3;
        Exception lastException = null;
        
        List<String> modelsToTry = new ArrayList<>();
        modelsToTry.add(model);
        if (!fallbackModel.equals(model)) {
            modelsToTry.add(fallbackModel);
        }
        
        for (String currentModel : modelsToTry) {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    log.info("Llamando a Gemini usando modelo={}, intento {}/{}", currentModel, attempt, maxAttempts);
                    if (contents instanceof String prompt) {
                        return client.models.generateContent(currentModel, prompt, null);
                    } else if (contents instanceof Content content) {
                        return client.models.generateContent(currentModel, content, null);
                    }
                } catch (Exception e) {
                    lastException = e;
                    log.warn("Error con modelo {} en intento {}/{}: {}. {}", currentModel, attempt, maxAttempts, e.getClass().getName(), e.getMessage());
                    if (attempt < maxAttempts) {
                        try {
                            Thread.sleep(1000L * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }
        throw new RuntimeException("Fallo total de la API de Gemini tras intentar con varios modelos y reintentos. Último error: " + (lastException != null ? lastException.getMessage() : "desconocido"), lastException);
    }

    private Iterable<GenerateContentResponse> generateStreamWithFallback(Object contents) throws Exception {
        try {
            return callStreamApi(primaryModel, contents);
        } catch (Exception e) {
            log.warn("Error streaming con modelo primario {}, intentando fallback {}", primaryModel, fallbackModel, e);
            return callStreamApi(fallbackModel, contents);
        }
    }

    private Iterable<GenerateContentResponse> callStreamApi(String model, Object contents) throws Exception {
        if (contents instanceof String prompt) {
            return client.models.generateContentStream(model, prompt, null);
        } else if (contents instanceof Content content) {
            return client.models.generateContentStream(model, content, null);
        }
        throw new IllegalArgumentException("Contenido no soportado para stream");
    }

    public GenerateContentResponse askGemini(String prompt) {
        // La anonimización se ejecuta ANTES de bifurcar hacia el simulador. De lo contrario
        // las pruebas de carga no medirían el coste real de este filtro (RNF7), que sí paga
        // producción, y el P95 reportado quedaría artificialmente bajo.
        prompt = anonymizePrompt(prompt);

        if (geminiSimulado) {
            log.info("🤖 MOCK GEMINI ACTIVE (askGemini): Generando respuesta simulada.");
            String mockText;
            if (prompt.contains("subtemas") || prompt.contains("subtema")) {
                mockText = "[\"Definición de Sujeto\", \"Estructura del Sujeto\", \"Núcleo y Modificadores\"]";
            } else if (prompt.contains("profesor") || prompt.contains("rúbrica") || prompt.contains("Juez") || prompt.contains("JUEZ")) {
                mockText = "[{\"id_alumno\": \"Alumno 1\", \"nota\": 4.0, \"justificacion\": \"Respuesta correcta y detallada.\"}, {\"id_alumno\": \"Alumno 2\", \"nota\": 2.0, \"justificacion\": \"Respuesta incompleta.\"}]";
            } else {
                // Generación de reactivos (UNIVERSAL_SCHEMA)
                mockText = "{\\\"preguntas\\\": [{\\\"enunciado\\\": \\\"Identifique cuál es la tesis principal del texto sobre el sujeto.\\\", \\\"opciones_o_respuesta\\\": [\\\"A) El sujeto es el elemento central\\\", \\\"B) El predicado es el elemento central\\\"], \\\"respuesta_correcta\\\": \\\"A) El sujeto es el elemento central\\\", \\\"justificacion_pregunta\\\": \\\"El texto define al sujeto como núcleo de la acción.\\\", \\\"prompt_imagen\\\": \\\"\\\"}]}";
            }
            String responseJson = "{\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"" + mockText + "\"}]}}], \"usageMetadata\": {\"promptTokenCount\": 10, \"candidatesTokenCount\": 20, \"totalTokenCount\": 30}}";
            return GenerateContentResponse.fromJson(responseJson);
        }
        return generateWithRetryAndFallback(primaryModel, prompt);
    }

    public Iterable<GenerateContentResponse> askGeminiStream(String prompt) {
        if (geminiSimulado) {
            log.info("🤖 MOCK GEMINI STREAM ACTIVE (askGeminiStream)");
            String mockText = "Generando preguntas en stream mock...";
            if (prompt.contains("UNIVERSAL_SCHEMA") || prompt.contains("REACTIVO") || prompt.contains("pregunta") || prompt.contains("Sujeto")) {
                mockText = "{\"preguntas\": [{\"enunciado\": \"Identifique la tesis del texto en stream.\", \"opciones_o_respuesta\": [\"A) Opción A\", \"B) Opción B\"], \"respuesta_correcta\": \"A) Opción A\", \"justificacion_pregunta\": \"Justificación\"}]}";
            }
            String responseJson = "{\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"" + mockText.replace("\"", "\\\"") + "\"}]}}]}";
            return List.of(GenerateContentResponse.fromJson(responseJson));
        }
        try {
            return generateStreamWithFallback(anonymizePrompt(prompt));
        } catch (Exception e) {
            throw new RuntimeException("Error al iniciar stream con Gemini", e);
        }
    }

    public GenerateContentResponse askGeminiWithPdfs(String prompt, List<MultipartFile> pdfs) throws Exception {
        if (geminiSimulado) {
            log.info("🤖 MOCK GEMINI ACTIVE (askGeminiWithPdfs)");
            return askGemini(prompt);
        }
        List<Part> parts = new ArrayList<>();
        for (MultipartFile pdf : pdfs) {
            parts.add(Part.fromBytes(pdf.getBytes(), "application/pdf"));
        }
        parts.add(Part.fromText(anonymizePrompt(prompt)));

        Content content = Content.fromParts(parts.toArray(new Part[0]));
        return generateWithRetryAndFallback(primaryModel, content);
    }

    public List<Float> getEmbeddings(String text) {
        if (geminiSimulado) {
            log.info("🤖 MOCK GEMINI EMBEDDINGS ACTIVE (getEmbeddings)");
            List<Float> mockVector = new ArrayList<>();
            for (int i = 0; i < 3072; i++) {
                mockVector.add(0.0f);
            }
            return mockVector;
        }
        try {
            var response = client.models.embedContent("gemini-embedding-001", text, null);

            if (response.embeddings() != null && response.embeddings().isPresent()) {
                var listaEmbeddings = response.embeddings().get();

                if (!listaEmbeddings.isEmpty()) {
                    var valoresOptional = listaEmbeddings.get(0).values();

                    if (valoresOptional != null && valoresOptional.isPresent()) {
                        return valoresOptional.get(); // Todo perfecto, devuelve los 3072 números
                    }
                }
            }

            throw new RuntimeException("La API respondió, pero no devolvió vectores para: '" + text + "'");

        } catch (Exception e) {
            throw new RuntimeException("Fallo crítico conectando con Gemini Embeddings: " + e.getMessage(), e);
        }
    }

    public Iterable<GenerateContentResponse> askGeminiStreamWithPdfs(
            String prompt, List<MultipartFile> pdfs) throws Exception {

        List<Part> parts = new ArrayList<>();
        for (MultipartFile pdf : pdfs) {
            parts.add(Part.fromBytes(pdf.getBytes(), "application/pdf"));
        }
        parts.add(Part.fromText(anonymizePrompt(prompt)));

        Content content = Content.fromParts(parts.toArray(new Part[0]));
        return generateStreamWithFallback(content);
    }

    public Iterable<GenerateContentResponse> askGeminiStreamWithAudio(
            String prompt, MultipartFile audio) throws Exception {

        List<Part> parts = new ArrayList<>();
        String mimeType = audio.getContentType() != null ? audio.getContentType() : "audio/webm";
        parts.add(Part.fromBytes(audio.getBytes(), mimeType));
        parts.add(Part.fromText(anonymizePrompt(prompt)));

        Content content = Content.fromParts(parts.toArray(new Part[0]));
        return generateStreamWithFallback(content);
    }

    /**
     * Llamada multimodal texto+imagen para tareas de verificación (ej. comprobar que una
     * imagen generada corresponde al enunciado que la referencia). Reutiliza el mismo
     * modelo de texto principal, que acepta entrada de imagen como cualquier chat de Gemini.
     */
    public GenerateContentResponse askGeminiConImagen(String promptTexto, byte[] imagenBytes, String mimeType) {
        if (geminiSimulado) {
            log.info("🤖 MOCK GEMINI ACTIVE (askGeminiConImagen): validación simulada como válida.");
            String responseJson = "{\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"" +
                    "{\\\"valida\\\": true, \\\"motivo\\\": \\\"Simulado\\\"}\"}]}}]}";
            return GenerateContentResponse.fromJson(responseJson);
        }
        List<Part> parts = new ArrayList<>();
        parts.add(Part.fromBytes(imagenBytes, mimeType != null ? mimeType : "image/png"));
        parts.add(Part.fromText(promptTexto));
        Content content = Content.fromParts(parts.toArray(new Part[0]));
        return generateWithRetryAndFallback(primaryModel, content);
    }

    public String generarImagenConImagen3(String promptText) {
        int maxAttempts = 3;
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("Llamando a Gemini Image usando gemini-2.5-flash-image, intento {}/{}", attempt, maxAttempts);
                var response = client.models.generateContent("gemini-2.5-flash-image", anonymizePrompt(promptText), null);
                if (response.candidates() != null && response.candidates().isPresent()) {
                    var list = response.candidates().get();
                    if (!list.isEmpty()) {
                        var candidate = list.get(0);
                        var content = candidate.content();
                        if (content != null && content.isPresent()) {
                            var parts = content.get().parts();
                            if (parts != null && parts.isPresent()) {
                                for (var part : parts.get()) {
                                    if (part.inlineData() != null && part.inlineData().isPresent()) {
                                        var blob = part.inlineData().get();
                                        byte[] dataBytes = blob.data().orElse(new byte[0]);
                                        return java.util.Base64.getEncoder().encodeToString(dataBytes);
                                    }
                                }
                            }
                        }
                    }
                }
                throw new RuntimeException("La API de Gemini no devolvió ninguna imagen.");
            } catch (Exception e) {
                lastException = e;
                log.warn("Fallo al generar imagen en intento {}/{}: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(1500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        log.error("[IMAGE-GENERATION] Fallo crítico al generar imagen con Gemini Image tras reintentos (cuota excedida o error API). Retornando fallback transparente. Error original: {}", lastException != null ? lastException.getMessage() : "desconocido");
        return "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";
    }

    public Iterable<GenerateContentResponse> askGeminiStreamWithVideo(
            String prompt, MultipartFile video) throws Exception {

        List<Part> parts = new ArrayList<>();
        String mimeType = video.getContentType() != null ? video.getContentType() : "video/webm";
        parts.add(Part.fromBytes(video.getBytes(), mimeType));
        parts.add(Part.fromText(anonymizePrompt(prompt)));

        Content content = Content.fromParts(parts.toArray(new Part[0]));
        return generateStreamWithFallback(content);
    }

    /**
     * Filtro de anonimización previo al envío al LLM (RNF7).
     *
     * Alcance deliberadamente acotado al usuario de la sesión en curso. La versión anterior
     * recorría userRepository.findAll() en cada llamada y reemplazaba cada fragmento del
     * nombre de cada usuario del sistema, con dos consecuencias: un escaneo completo de la
     * tabla por prompt, y la corrupción del material didáctico cuando un nombre propio
     * coincide con vocabulario común (una alumna "Rosa Flores" borraba "rosa" y "flores"
     * de cualquier texto de Lenguaje o Ciencias).
     */
    private String anonymizePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return prompt;
        }

        // 1. Correos electrónicos: patrón cerrado, sin riesgo de falsos positivos.
        String emailPattern = "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}";
        String sanitized = prompt.replaceAll(emailPattern, "[CORREO_ANONIMIZADO]");

        // 2. Nombre del usuario autenticado, solo si aparece completo.
        try {
            String correo = correoDeLaSesion();
            if (correo != null) {
                String nombre = userRepository.findByCorreo(correo)
                        .map(u -> u.getNombre())
                        .filter(n -> n != null && n.trim().length() > 3)
                        .orElse(null);
                if (nombre != null) {
                    sanitized = sanitized.replaceAll(
                            "(?i)" + java.util.regex.Pattern.quote(nombre.trim()), "[NOMBRE_ANONIMIZADO]");
                }
            }
        } catch (Exception e) {
            log.warn("Error at prompt anonymization: {}", e.getMessage());
        }

        return sanitized;
    }

    /** Correo del principal autenticado, o null fuera de un contexto de petición. */
    private String correoDeLaSesion() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return auth.getName();
    }
}