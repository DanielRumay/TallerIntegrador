package com.example.tallerintegrador.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Pipeline de Ingesta (Patrón Pipeline):
 *   PDF/Archivo → Tika (texto) → Chunking (500 tok) → Embedding → Qdrant
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagIngestionService {

    private final TikaExtractorService   tikaExtractorService;
    private final ChunkingService        chunkingService;
    private final EmbeddingModel         embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EvaluacionIAService    evaluacionIAService;   // para guardar en Mongo también

    /**
     * Punto de entrada principal del pipeline de ingesta.
     * Llamar desde el controlador al subir archivos.
     */
    public IngestaResultado ingestarArchivo(MultipartFile archivo) {
        String nombreArchivo = archivo.getOriginalFilename();
        log.info("=== INICIO INGESTA: {} ===", nombreArchivo);

        try {
            // ETAPA 1 — Guardar en MongoDB (raw bytes) y obtener ID
            String archivoId = evaluacionIAService.guardarArchivoYRetornarId(archivo);
            log.info("[ETAPA 1] Guardado en Mongo: ID={}", archivoId);

            // ETAPA 2 — Extraer texto con Tika
            String textoCompleto = tikaExtractorService.extractText(archivo);
            log.info("[ETAPA 2] Texto extraído: {} caracteres", textoCompleto.length());

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

    public record IngestaResultado(
            String  archivoId,
            String  nombreArchivo,
            int     totalChunks,
            boolean exitoso,
            String  errorMensaje
    ) {}
}