package com.example.tallerintegrador.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrieverService {

    private final EmbeddingModel              embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    private static final int    TOP_K     = 8;
    private static final double MIN_SCORE = 0.65;

    public List<ChunkRelevante> recuperar(String consulta, String archivoId) {
        log.info("[RAGRetriever] Buscando: '{}' | archivoId={}", consulta, archivoId);

        Embedding queryEmbedding = embeddingModel.embed(consulta).content();

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(archivoId != null ? TOP_K * 2 : TOP_K) // margen para filtrar
                .minScore(MIN_SCORE)
                .build();

        List<EmbeddingMatch<TextSegment>> todos = embeddingStore.search(request).matches();

        // Filtro por archivo en memoria — no depende de la API de filtros de Qdrant
        List<EmbeddingMatch<TextSegment>> matches = (archivoId != null)
                ? todos.stream()
                .filter(m -> archivoId.equals(m.embedded().metadata().getString("archivoId")))
                .limit(TOP_K)
                .toList()
                : todos;

        log.info("[RAGRetriever] {} chunks encontrados", matches.size());

        return matches.stream()
                .map(m -> new ChunkRelevante(
                        m.embedded().text(),
                        m.score(),
                        m.embedded().metadata().getString("archivoId"),
                        m.embedded().metadata().getString("nombreArchivo"),
                        parseIndex(m.embedded().metadata().getString("chunkIndex"))
                ))
                .toList();
    }

    private int parseIndex(String val) {
        try { return Integer.parseInt(val); } catch (Exception e) { return 0; }
    }

    public record ChunkRelevante(
            String texto,
            double score,
            String archivoId,
            String nombreArchivo,
            int    chunkIndex
    ) {}
}