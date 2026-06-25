package com.example.tallerintegrador.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Pipeline de Ingesta (Patrón Pipeline):
 *   PDF/Archivo → Tika (texto) → Extraer Subtemas (Gemini) → Chunking (500 tok) → Embedding → Qdrant
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagIngestionService {

    private final TikaExtractorService   tikaExtractorService;
    private final ChunkingService        chunkingService;
    private final EmbeddingModel         embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ArchivoService         archivoService;   // para guardar en Mongo también
    private final GeminiService          geminiService;
    private final QdrantClient           qdrantClient;
    private final ObjectMapper           objectMapper = new ObjectMapper();

    public void eliminarVectoresPorArchivoId(String archivoId) {
        log.info("Eliminando vectores del archivo: {}", archivoId);
        try {
            Points.Filter filter = Points.Filter.newBuilder()
                    .addMust(Points.Condition.newBuilder()
                            .setField(Points.FieldCondition.newBuilder()
                                    .setKey("archivoId")
                                    .setMatch(Points.Match.newBuilder().setText(archivoId).build())
                                    .build())
                            .build())
                    .build();

            qdrantClient.deleteAsync(com.example.tallerintegrador.config.QdrantConfig.COLLECTION_NAME, filter).get();
            log.info("Vectores del archivo {} eliminados exitosamente de Qdrant", archivoId);
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error al eliminar vectores de Qdrant para archivo {}: {}", archivoId, e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    public IngestaResultado ingestarArchivo(MultipartFile archivo) {
        String nombreArchivo = archivo.getOriginalFilename();
        log.info("=== INICIO INGESTA: {} ===", nombreArchivo);

        try {
            // ETAPA 1 — Guardar en MongoDB (raw bytes) y obtener ID
            String archivoId = archivoService.guardarArchivoYRetornarId(archivo);
            log.info("[ETAPA 1] Guardado en Mongo: ID={}", archivoId);

            // ETAPA 2 — Extraer texto con Tika
            String textoCompleto = tikaExtractorService.extractText(archivo);
            log.info("[ETAPA 2] Texto extraído: {} caracteres", textoCompleto.length());

            // ETAPA 2.5 — Extraer Subtemas con Gemini
            List<String> subtemasDetectados = new java.util.ArrayList<>();
            try {
                String promptSubtemas = "Analiza el siguiente texto extraído de un documento educativo y extrae los 3 a 5 subtemas o conceptos principales que aborda. "
                        + "Responde ÚNICAMENTE con un array en formato JSON con los nombres de los subtemas, sin ningún otro texto. Ejemplo: [\"Revolución Francesa\", \"Causas Económicas\", \"Consecuencias Políticas\"]. "
                        + "\n\nTEXTO:\n" + textoCompleto;
                
                String respuestaGemini = geminiService.askGemini(promptSubtemas).text();
                String jsonLimpio = cleanJsonString(respuestaGemini);
                subtemasDetectados = objectMapper.readValue(jsonLimpio, new TypeReference<List<String>>() {});
                archivoService.actualizarSubtemas(archivoId, subtemasDetectados);
                log.info("[ETAPA 2.5] Subtemas extraídos y guardados: {}", subtemasDetectados);
            } catch (Exception e) {
                log.warn("[ETAPA 2.5] No se pudieron extraer subtemas: {}", e.getMessage());
            }

            // ETAPA 3 — Chunking (500 tokens, 50 de overlap)
            List<TextSegment> chunks = chunkingService.chunkear(textoCompleto, archivoId, nombreArchivo);
            log.info("[ETAPA 3] Chunks generados: {}", chunks.size());

            // ETAPA 4 — Embeder con Gemini y almacenar en Qdrant
            int embeddingsGuardados = 0;
            for (TextSegment chunk : chunks) {
                Response<Embedding> embResponse = embeddingModel.embed(chunk.text());
                embeddingStore.add(embResponse.content(), chunk);
                embeddingsGuardados++;

                // Pausa breve para no saturar la API de Gemini
                Thread.sleep(200);
            }
            log.info("[ETAPA 4] {} embeddings guardados en Qdrant", embeddingsGuardados);

            return new IngestaResultado(archivoId, nombreArchivo, chunks.size(), true, null);

        } catch (Exception e) {
            log.error("Error en pipeline de ingesta: {}", e.getMessage());
            return new IngestaResultado(null, nombreArchivo, 0, false, e.getMessage());
        }
    }

    private String cleanJsonString(String raw) {
        if (raw == null) return "[]";
        raw = raw.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
        int startIndex = raw.indexOf("[");
        int endIndex   = raw.lastIndexOf("]");
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return raw.substring(startIndex, endIndex + 1);
        }
        return "[]";
    }

    public record IngestaResultado(
            String  archivoId,
            String  nombreArchivo,
            int     totalChunks,
            boolean exitoso,
            String  errorMensaje
    ) {}
}