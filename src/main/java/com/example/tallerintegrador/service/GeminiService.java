package com.example.tallerintegrador.service;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GeminiService {

    private final Client client;

    public GenerateContentResponse askGemini(String prompt) {
        return client.models.generateContent("gemini-3-flash-preview", prompt, null);
    }

    public Iterable<GenerateContentResponse> askGeminiStream(String prompt) {
        return client.models.generateContentStream(
                "gemini-3-flash-preview", prompt,
                null);
    }

    public GenerateContentResponse askGeminiWithPdfs(String prompt, List<MultipartFile> pdfs) throws Exception {
        List<Part> parts = new ArrayList<>();
        for (MultipartFile pdf : pdfs) {
            parts.add(Part.fromBytes(pdf.getBytes(), "application/pdf"));
        }
        parts.add(Part.fromText(prompt));

        Content content = Content.fromParts(parts.toArray(new Part[0]));
        return client.models.generateContent("gemini-3-flash-preview", content, null);
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
        return client.models.generateContentStream("gemini-3-flash-preview", content, null);
    }

    public Iterable<GenerateContentResponse> askGeminiStreamWithAudio(
            String prompt, MultipartFile audio) throws Exception {

        List<Part> parts = new ArrayList<>();
        String mimeType = audio.getContentType() != null ? audio.getContentType() : "audio/webm";
        parts.add(Part.fromBytes(audio.getBytes(), mimeType));
        parts.add(Part.fromText(prompt));

        Content content = Content.fromParts(parts.toArray(new Part[0]));
        return client.models.generateContentStream("gemini-3-flash-preview", content, null);
    }

    public String generarImagenConImagen3(String promptText) {
        try {
            var response = client.models.generateImages("imagen-3.0-generate-002", promptText, null);
            if (response.generatedImages() != null && response.generatedImages().isPresent()) {
                var list = response.generatedImages().get();
                if (!list.isEmpty()) {
                    var firstImage = list.get(0);
                    var imageOpt = firstImage.image();
                    if (imageOpt != null && imageOpt.isPresent()) {
                        byte[] bytes = imageOpt.get().imageBytes().get();
                        return java.util.Base64.getEncoder().encodeToString(bytes);
                    }
                }
            }
            throw new RuntimeException("La API de Google Imagen 3 no devolvió ninguna imagen.");
        } catch (Exception e) {
            throw new RuntimeException("Fallo al generar imagen con Imagen 3: " + e.getMessage(), e);
        }
    }

    public Iterable<GenerateContentResponse> askGeminiStreamWithVideo(
            String prompt, MultipartFile video) throws Exception {

        List<Part> parts = new ArrayList<>();
        String mimeType = video.getContentType() != null ? video.getContentType() : "video/webm";
        parts.add(Part.fromBytes(video.getBytes(), mimeType));
        parts.add(Part.fromText(prompt));

        Content content = Content.fromParts(parts.toArray(new Part[0]));
        return client.models.generateContentStream("gemini-3-flash-preview", content, null);
    }
}