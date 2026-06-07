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

    private static final int TOP_K = 8;

    public List<ChunkRelevante> recuperar(String consulta, String archivoId) {
        log.info("[RAGRetriever] Buscando: '{}' | archivoId={}", consulta, archivoId);

        Embedding queryEmbedding = embeddingModel.embed(consulta).content();

        // INTENTO 1: Búsqueda estricta (Score 0.65)
        List<ChunkRelevante> matches = ejecutarBusqueda(queryEmbedding, archivoId, 0.65);

        // INTENTO 2: Búsqueda flexible (Score 0.50) si la estricta falló
        if (matches.isEmpty()) {
            log.warn("[RAGRetriever] 0 chunks con score 0.65. Reintentando con score 0.50...");
            matches = ejecutarBusqueda(queryEmbedding, archivoId, 0.50);
        }

        // INTENTO 3: Modo desesperado (Score 0.30) para asegurar que el Avatar tenga contexto
        if (matches.isEmpty()) {
            log.warn("[RAGRetriever] 0 chunks con score 0.50. Modo desesperado (score 0.30)...");
            matches = ejecutarBusqueda(queryEmbedding, archivoId, 0.30);
        }

        log.info("[RAGRetriever] {} chunks finales encontrados", matches.size());
        return matches;
    }

    private List<ChunkRelevante> ejecutarBusqueda(Embedding queryEmbedding, String archivoId, double minScore) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(archivoId != null ? TOP_K * 2 : TOP_K) // Margen para filtrar por Mongo ID
                .minScore(minScore)
                .build();

        List<EmbeddingMatch<TextSegment>> todos = embeddingStore.search(request).matches();

        // Filtro por archivo en memoria
        List<EmbeddingMatch<TextSegment>> filtrados = (archivoId != null)
                ? todos.stream()
                .filter(m -> archivoId.equals(m.embedded().metadata().getString("archivoId")))
                .limit(TOP_K)
                .toList()
                : todos.stream().limit(TOP_K).toList();

        return filtrados.stream()
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