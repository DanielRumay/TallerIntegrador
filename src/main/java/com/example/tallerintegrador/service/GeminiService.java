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

            // se recibe la Lista de embeddings
            if (response.embeddings() != null && response.embeddings().isPresent()) {
                var listaEmbeddings = response.embeddings().get();

                if (!listaEmbeddings.isEmpty()) {
                    //Obtenemos la llamada a .values()
                    var valoresOptional = listaEmbeddings.get(0).values();

                    if (valoresOptional != null && valoresOptional.isPresent()) {
                        return valoresOptional.get();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error obteniendo embeddings: " + e.getMessage());
        }

        return new ArrayList<>();
    }
}