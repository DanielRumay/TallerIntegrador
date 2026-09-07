package com.example.tallerintegrador.service.rag;
import com.example.tallerintegrador.service.metricas.TelemetriaIAService;

import com.example.tallerintegrador.entidades.postgres.EventoMetricaIA;
import com.example.tallerintegrador.entidades.postgres.TipoEventoIA;
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

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrieverService {

    private final EmbeddingModel              embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final TelemetriaIAService         telemetriaIAService;

    private static final int TOP_K = 8;

    /**
     * Escalera de umbrales de relevancia. Se recorre de más estricto a más laxo.
     * El último escalón es una degradación consciente: el contexto recuperado a 0.30 puede
     * no ser curricularmente pertinente, por eso el resultado marca la recuperación como
     * degradada en vez de presentarla como equivalente a una recuperación estricta.
     */
    private static final double[] UMBRALES = {0.65, 0.50, 0.30};
    private static final double UMBRAL_CONFIABLE = 0.50;

    /**
     * Resultado de una recuperación, con la trazabilidad del umbral que hizo falta para
     * obtenerla. Quien genera preguntas necesita saber si el contexto es fiable.
     */
    public record Recuperacion(
            List<ChunkRelevante> chunks,
            double umbralEfectivo,
            boolean degradada
    ) {
        public boolean vacia() { return chunks.isEmpty(); }
    }

    /** Compatibilidad con los llamadores existentes: devuelve solo los chunks. */
    public List<ChunkRelevante> recuperar(String consulta, String archivoId) {
        return recuperarConTrazabilidad(consulta, archivoId).chunks();
    }

    public Recuperacion recuperarConTrazabilidad(String consulta, String archivoId) {
        log.info("[RAGRetriever] Buscando: '{}' | archivoId={}", consulta, archivoId);

        Embedding queryEmbedding = embeddingModel.embed(consulta).content();

        for (double umbral : UMBRALES) {
            List<ChunkRelevante> matches = ejecutarBusqueda(queryEmbedding, archivoId, umbral);
            if (!matches.isEmpty()) {
                boolean degradada = umbral < UMBRAL_CONFIABLE;
                if (degradada) {
                    log.warn("[RAGRetriever] Contexto recuperado en modo degradado (umbral {}). " +
                            "La pertinencia curricular de las preguntas generadas no está garantizada.", umbral);
                }
                log.info("[RAGRetriever] {} chunks con umbral {}", matches.size(), umbral);
                registrar(umbral, matches.size(), degradada);
                return new Recuperacion(matches, umbral, degradada);
            }
            log.warn("[RAGRetriever] 0 chunks con umbral {}. Reintentando con el siguiente escalón...", umbral);
        }

        log.warn("[RAGRetriever] Sin contexto recuperable para la consulta");
        registrar(0.0, 0, true);
        return new Recuperacion(List.of(), 0.0, true);
    }

    private void registrar(double umbral, int chunks, boolean degradada) {
        EventoMetricaIA evento = EventoMetricaIA.de(
                TipoEventoIA.RAG_RECUPERACION,
                chunks == 0 ? "SIN_CONTEXTO" : "UMBRAL_" + String.valueOf(umbral).replace('.', '_'));
        evento.setValor(umbral);
        evento.setDetalle("chunks=%d, degradada=%b".formatted(chunks, degradada));
        telemetriaIAService.registrar(evento);
    }

    private List<ChunkRelevante> ejecutarBusqueda(Embedding queryEmbedding, String archivoId, double minScore) {
        EmbeddingSearchRequest.EmbeddingSearchRequestBuilder request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(TOP_K)
                .minScore(minScore);

        // El aislamiento por archivo se resuelve dentro de Qdrant (payload filter), no
        // trayendo el doble de resultados para descartarlos después en memoria.
        if (archivoId != null) {
            request.filter(metadataKey("archivoId").isEqualTo(archivoId));
        }

        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(request.build()).matches();

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
