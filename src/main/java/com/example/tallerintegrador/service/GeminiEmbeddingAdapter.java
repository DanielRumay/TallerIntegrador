package com.example.tallerintegrador.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;


@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiEmbeddingAdapter implements EmbeddingModel {

    private final GeminiService geminiService;

    @Override
    public Response<Embedding> embed(String text) {
        log.info("[ADAPTER] Pidiendo embedding a Gemini para el texto: '{}'", text);

        List<Float> floats = geminiService.getEmbeddings(text);

        if (floats == null || floats.isEmpty()) {
            log.error("[ADAPTER CRÍTICO] Gemini devolvió una lista vacía para el texto '{}'. Abortando búsqueda.", text);
            throw new RuntimeException("La API de Gemini devolvió un vector de tamaño 0.");
        }

        if (floats.size() != 3072) {
            log.warn("[ADAPTER ALERTA] Gemini devolvió un vector de tamaño {}. Esperábamos 3072.", floats.size());
        }

        float[] arr = new float[floats.size()];
        for (int i = 0; i < floats.size(); i++) {
            arr[i] = floats.get(i);
        }

        log.info("[ADAPTER] Vector generado exitosamente con tamaño: {}", arr.length);
        return Response.from(Embedding.from(arr));
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<Embedding> embeddings = textSegments.stream()
                .map(seg -> embed(seg.text()).content())
                .toList();
        return Response.from(embeddings);
    }
}