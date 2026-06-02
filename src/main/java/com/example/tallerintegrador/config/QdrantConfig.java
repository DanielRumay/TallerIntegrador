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

@Slf4j
@Configuration
public class QdrantConfig {

    public static final String COLLECTION_NAME     = "textos_educativos";
    public static final int    EMBEDDING_DIMENSION = 768; // text-embedding-004

    @Bean
    public EmbeddingModel embeddingModel(GeminiEmbeddingAdapter adapter) {
        return adapter;
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {

        // 1. Construir el cliente gRPC nativo de Qdrant
        QdrantClient qdrantClient = new QdrantClient(
                QdrantGrpcClient.newBuilder("localhost", 6334, false).build()
        );

        // 2. Crear la colección si no existe (aquí sí se define la dimensión)
        try {
            boolean existe = qdrantClient.collectionExistsAsync(COLLECTION_NAME).get();
            if (!existe) {
                qdrantClient.createCollectionAsync(
                        COLLECTION_NAME,
                        VectorParams.newBuilder()
                                .setSize(EMBEDDING_DIMENSION)
                                .setDistance(Distance.Cosine)
                                .build()
                ).get();
                log.info("Colección '{}' creada en Qdrant (dim={})", COLLECTION_NAME, EMBEDDING_DIMENSION);
            } else {
                log.info("Colección '{}' ya existe en Qdrant", COLLECTION_NAME);
            }
        } catch (Exception e) {
            log.error("Error verificando/creando colección Qdrant: {}", e.getMessage());
        }

        // 3. Conectar QdrantEmbeddingStore pasando el client (sin .dimension())
        return QdrantEmbeddingStore.builder()
                .client(qdrantClient)
                .collectionName(COLLECTION_NAME)
                .build();
    }
}