package com.example.tallerintegrador.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Adapter que envuelve GeminiService.getEmbeddings() (ya funcional)
 * como un EmbeddingModel estándar de LangChain4J.
 * Evita depender de GoogleAiGeminiEmbeddingModel que no es estable en beta1.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiEmbeddingAdapter implements EmbeddingModel {

    private final GeminiService geminiService;

    @Override
    public Response<Embedding> embed(String text) {
        List<Float> floats = geminiService.getEmbeddings(text);

        float[] arr = new float[floats.size()];
        for (int i = 0; i < floats.size(); i++) {
            arr[i] = floats.get(i);
        }

        return Response.from(Embedding.from(arr));
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<Embedding> embeddings = textSegments.stream()
                .map(seg -> {
                    List<Float> floats = geminiService.getEmbeddings(seg.text());
                    float[] arr = new float[floats.size()];
                    for (int i = 0; i < floats.size(); i++) arr[i] = floats.get(i);
                    return Embedding.from(arr);
                })
                .toList();
        return Response.from(embeddings);
    }
}