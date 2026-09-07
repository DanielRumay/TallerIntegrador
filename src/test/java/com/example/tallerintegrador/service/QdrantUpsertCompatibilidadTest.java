package com.example.tallerintegrador.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Comprueba que el cliente de Qdrant y el servidor hablan el mismo dialecto al guardar un
 * vector.
 *
 * POR QUE EXISTE ESTA PRUEBA. El proyecto estuvo descartando TODOS los fragmentos de cada
 * ingesta con el error "Vector dimension error: expected dim: 3072, got 0". El embedding se
 * generaba bien (3072 flotantes, y el log lo decia), pero al servidor le llegaba vacio: el
 * cliente escribe el vector en el campo `dense`, que solo existe en el protocolo de Qdrant
 * desde la version 1.13, y el servidor era 1.12.1. Un campo que el servidor no conoce no da
 * error de protocolo: se ignora en silencio, el punto llega sin vector y el rechazo aparece
 * como si el problema fuera la dimension.
 *
 * Ninguna prueba unitaria podia detectar eso, porque el fallo esta en la frontera entre dos
 * procesos. Esta si: hace un upsert real contra el Qdrant configurado.
 *
 * Si Qdrant no esta levantado, la prueba se salta en vez de fallar. Un entorno sin Qdrant no
 * es un defecto del codigo, y hacerla fallar ahi solo entrenaria a ignorarla.
 */
class QdrantUpsertCompatibilidadTest {

    private static final String HOST = System.getProperty("qdrant.host", "localhost");
    private static final int PUERTO = Integer.getInteger("qdrant.port", 6334);

    /** Coleccion propia y desechable: no se toca la del curso. */
    private static final String COLECCION = "prueba_compatibilidad_upsert";
    private static final int DIMENSION = 3072;

    @Test
    @DisplayName("Un vector de 3072 llega completo al servidor (no 'got 0')")
    void elVectorLlegaCompletoAlServidor() throws Exception {
        QdrantClient cliente = conectar();
        Assumptions.assumeTrue(cliente != null, "Qdrant no esta levantado; se omite.");

        try {
            try {
                cliente.createCollectionAsync(
                        COLECCION,
                        VectorParams.newBuilder()
                                .setSize(DIMENSION)
                                .setDistance(Distance.Cosine)
                                .build()).get();
            } catch (Exception yaExistia) {
                // Reejecutar la prueba no debe fallar por la coleccion de la vez anterior.
            }

            EmbeddingStore<TextSegment> store = QdrantEmbeddingStore.builder()
                    .client(cliente)
                    .collectionName(COLECCION)
                    .build();

            // Vector arbitrario pero de la dimension real: lo que se comprueba es el
            // transporte, no la semantica del embedding.
            Random azar = new Random(42);
            float[] valores = new float[DIMENSION];
            for (int i = 0; i < DIMENSION; i++) {
                valores[i] = azar.nextFloat();
            }

            String id = store.add(
                    Embedding.from(valores),
                    TextSegment.from("fragmento de prueba de compatibilidad"));

            // Si el servidor no entendiera el campo del vector, este add lanzaria
            // INVALID_ARGUMENT "expected dim: 3072, got 0" y la prueba fallaria aqui.
            assertNotNull(id, "el punto deberia haberse guardado y devolver su id");
        } finally {
            try {
                cliente.deleteCollectionAsync(COLECCION).get();
            } catch (Exception ignorado) {
                // Limpieza best-effort: no cambia el resultado de la prueba.
            }
            cliente.close();
        }
    }

    /** Devuelve null (en vez de lanzar) si no hay servidor, para poder omitir la prueba. */
    private QdrantClient conectar() {
        try {
            QdrantClient cliente = new QdrantClient(
                    QdrantGrpcClient.newBuilder(HOST, PUERTO, false).build());
            cliente.listCollectionsAsync().get();
            return cliente;
        } catch (Exception sinServidor) {
            return null;
        }
    }
}
