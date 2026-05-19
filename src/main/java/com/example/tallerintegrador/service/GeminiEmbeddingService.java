package com.example.tallerintegrador.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GeminiEmbeddingService {

    private final EmbeddingModel embeddingModel;

    // Spring Boot inyecta el valor automáticamente al construir el servicio
    public GeminiEmbeddingService(@Value("${GOOGLE_API_KEY}") String apiKey) {
        this.embeddingModel = GoogleAiEmbeddingModel.builder()
                .apiKey(apiKey) // Usamos la variable inyectada
                .modelName("text-embedding-004")
                .build();
    }

    public List<Float> obtenerVector(String texto) {
        // 1. Mandamos el pedazo de texto a la IA de Gemini
        Embedding embedding = embeddingModel.embed(texto).content();

        // 2. Gemini nos devuelve el vector matemático y lo retornamos como Lista
        return embedding.vectorAsList();
    }
}