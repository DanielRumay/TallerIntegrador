package com.example.tallerintegrador.config;

import com.example.tallerintegrador.service.GeminiEmbeddingAdapter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.beans.factory.annotation.Value;

@Slf4j
@Configuration
public class QdrantConfig {

    public static final String COLLECTION_NAME     = "textos_educativos_v3"; // Versión 3
    public static final int    EMBEDDING_DIMENSION = 3072; // gemini-embedding-001

    @Value("${qdrant.host}")
    private String qdrantHost;

    @Value("${qdrant.port}")
    private int qdrantPort;

    @Bean
    public EmbeddingModel embeddingModel(GeminiEmbeddingAdapter adapter) {
        return adapter;
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {

        log.info("Conectando con Qdrant en {}:{}", qdrantHost, qdrantPort);
        QdrantClient qdrantClient = new QdrantClient(
                QdrantGrpcClient.newBuilder(qdrantHost, qdrantPort, false).build()
        );

        // ¡Estrategia Tanque de Guerra! Cero preguntas, solo creación directa.
        try {
            log.info("Intentando crear colección '{}' (dim={})...", COLLECTION_NAME, EMBEDDING_DIMENSION);
            qdrantClient.createCollectionAsync(
                    COLLECTION_NAME,
                    VectorParams.newBuilder()
                            .setSize(EMBEDDING_DIMENSION)
                            .setDistance(Distance.Cosine)
                            .build()
            ).get();
            log.info("¡Colección '{}' creada con éxito!", COLLECTION_NAME);
        } catch (Exception e) {
            // Si entra aquí, es porque la colección ya existía. Lo ignoramos elegantemente.
            log.warn("La colección ya existía (ignóralo): {}", e.getMessage());
        }

        return QdrantEmbeddingStore.builder()
                .client(qdrantClient)
                .collectionName(COLLECTION_NAME)
                .build();
    }
}