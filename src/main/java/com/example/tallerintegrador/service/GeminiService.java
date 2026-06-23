package com.example.tallerintegrador.service;

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

    @Value("${langchain4j.google-ai-gemini.chat-model.model-name:gemini-3.1-flash-lite}")
    private String primaryModel;

    @Value("${gemini.chat-model.fallback-name:gemini-2.5-flash}")
    private String fallbackModel;

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
        return generateWithRetryAndFallback(primaryModel, prompt);
    }

    public Iterable<GenerateContentResponse> askGeminiStream(String prompt) {
        try {
            return generateStreamWithFallback(prompt);
        } catch (Exception e) {
            throw new RuntimeException("Error al iniciar stream con Gemini", e);
        }
    }

    public GenerateContentResponse askGeminiWithPdfs(String prompt, List<MultipartFile> pdfs) throws Exception {
        List<Part> parts = new ArrayList<>();
        for (MultipartFile pdf : pdfs) {
            parts.add(Part.fromBytes(pdf.getBytes(), "application/pdf"));
        }
        parts.add(Part.fromText(prompt));

        Content content = Content.fromParts(parts.toArray(new Part[0]));
        return generateWithRetryAndFallback(primaryModel, content);
    }

    public List<Float> getEmbeddings(String text) {
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
        parts.add(Part.fromText(prompt));

        Content content = Content.fromParts(parts.toArray(new Part[0]));
        return generateStreamWithFallback(content);
    }

    public Iterable<GenerateContentResponse> askGeminiStreamWithAudio(
            String prompt, MultipartFile audio) throws Exception {

        List<Part> parts = new ArrayList<>();
        String mimeType = audio.getContentType() != null ? audio.getContentType() : "audio/webm";
        parts.add(Part.fromBytes(audio.getBytes(), mimeType));
        parts.add(Part.fromText(prompt));

        Content content = Content.fromParts(parts.toArray(new Part[0]));
        return generateStreamWithFallback(content);
    }

    public String generarImagenConImagen3(String promptText) {
        int maxAttempts = 3;
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("Llamando a Gemini Image usando gemini-2.5-flash-image, intento {}/{}", attempt, maxAttempts);
                var response = client.models.generateContent("gemini-2.5-flash-image", promptText, null);
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
        parts.add(Part.fromText(prompt));

        Content content = Content.fromParts(parts.toArray(new Part[0]));
        return generateStreamWithFallback(content);
    }
}