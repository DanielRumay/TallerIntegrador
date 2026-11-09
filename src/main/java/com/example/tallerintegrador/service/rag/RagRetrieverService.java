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

import java.util.Arrays;
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
    private static final int CANDIDATOS_VARIADOS = 40;

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

    /**
     * La consulta suele ser el nombre del archivo, así que el ranking sale igual cada vez y los
     * mismos 8 fragmentos producían las mismas preguntas. Desde la segunda vuelta de un alumno se
     * toma otra ventana de un ranking más amplio; al agotarlas, se vuelve a empezar.
     */
    public List<ChunkRelevante> recuperarVariado(String consulta, String archivoId, int vuelta) {
        if (vuelta <= 0) return recuperar(consulta, archivoId);
        Embedding embedding = embeddingModel.embed(consulta).content();
        List<ChunkRelevante> candidatos =
                ejecutarBusqueda(embedding, archivoId, UMBRALES[UMBRALES.length - 1], CANDIDATOS_VARIADOS);
        if (candidatos.isEmpty()) return recuperar(consulta, archivoId);
        log.info("[RAGRetriever] Vuelta {}: ventana de {} candidatos", vuelta, candidatos.size());
        return ventana(candidatos, vuelta, TOP_K);
    }

    static <T> List<T> ventana(List<T> candidatos, int vuelta, int tamano) {
        int n = candidatos.size();
        if (n <= tamano) return candidatos;
        int ventanas = (n + tamano - 1) / tamano;
        int desde = (vuelta % ventanas) * tamano;
        List<T> elegidos = new java.util.ArrayList<>(tamano);
        for (int i = 0; i < tamano; i++) elegidos.add(candidatos.get((desde + i) % n));
        return elegidos;
    }

    public Recuperacion recuperarConTrazabilidad(String consulta, String archivoId) {
        log.info("[RAGRetriever] Buscando: '{}' | archivoId={}", consulta, archivoId);

        Embedding queryEmbedding = embeddingModel.embed(consulta).content();

        for (double umbral : UMBRALES) {
            List<ChunkRelevante> matches = ejecutarBusqueda(queryEmbedding, archivoId, umbral, TOP_K);
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

    private List<ChunkRelevante> ejecutarBusqueda(Embedding queryEmbedding, String archivoId, double minScore, int limite) {
        EmbeddingSearchRequest.EmbeddingSearchRequestBuilder request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(limite)
                .minScore(minScore);

        // El aislamiento por archivo se resuelve dentro de Qdrant (payload filter), no
        // trayendo el doble de resultados para descartarlos después en memoria.
        //
        // Se admite una LISTA separada por comas porque una semana puede tener varios
        // materiales. Antes solo se aceptaba un id, y como el de la semana venía vacío en las
        // subidas de varios archivos, la búsqueda salía sin filtro: una prueba de Paco Yunque
        // recuperaba fragmentos de la ficha de gramática de otra semana y preguntaba por
        // preposiciones.
        if (archivoId != null && !archivoId.isBlank()) {
            List<String> ids = Arrays.stream(archivoId.split(","))
                    .map(String::strip)
                    .filter(x -> !x.isEmpty())
                    .toList();

            if (ids.size() == 1) {
                request.filter(metadataKey("archivoId").isEqualTo(ids.get(0)));
            } else if (ids.size() > 1) {
                request.filter(metadataKey("archivoId").isIn(ids));
            }
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
