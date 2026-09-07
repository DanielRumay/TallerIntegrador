package com.example.tallerintegrador.service;
import com.example.tallerintegrador.service.academico.ArchivoService;
import com.example.tallerintegrador.service.ia.GeminiService;
import com.example.tallerintegrador.service.rag.ChunkingService;
import com.example.tallerintegrador.service.rag.ProgresoIngestaService;
import com.example.tallerintegrador.service.rag.RagIngestionService;
import com.example.tallerintegrador.service.rag.ResumidorJerarquicoService;
import com.example.tallerintegrador.service.rag.SegmentadorDocumentoService;
import com.example.tallerintegrador.service.rag.TikaExtractorService;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import com.google.genai.types.GenerateContentResponse;
import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RagIngestionServiceTest {

    /**
     * Texto de prueba con longitud realista.
     *
     * Debe superar el mínimo que exige RagIngestionService para dar por extraído el texto: por
     * debajo de ese umbral el canal considera —correctamente— que el documento no trae texto,
     * que es justo como se detecta un PDF escaneado. Antes estas pruebas usaban una frase de
     * 27 caracteres, así que ejercitaban un caso que en producción ahora se rechaza.
     */
    private static final String TEXTO_DE_PRUEBA =
            "Contenido educativo del PDF sobre el signo lingüístico y sus principios. ".repeat(6);

    @Mock
    private TikaExtractorService tikaExtractorService;
    /**
     * No es mock: es una función pura sobre el texto, sin dependencias externas. Usar el
     * servicio real hace que la prueba ejercite la segmentación de verdad en vez de un doble
     * que siempre devuelve lo que le convenga.
     */
    private final SegmentadorDocumentoService segmentadorDocumentoService = new SegmentadorDocumentoService();
    @Mock
    private ChunkingService chunkingService;
    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;
    @Mock
    private ArchivoService archivoService;
    @Mock
    private GeminiService geminiService;
    @Mock
    private QdrantClient qdrantClient;

    private RagIngestionService ragIngestionService;

    @BeforeEach
    void setUp() {
        ragIngestionService = new RagIngestionService(
                tikaExtractorService,
                segmentadorDocumentoService,
                // Real, no mock: solo escribe en un mapa en memoria. Un doble aquí no
                // aportaría nada y ocultaría un fallo si el progreso lanzara excepción.
                new ProgresoIngestaService(),
                new ResumidorJerarquicoService(geminiService),
                chunkingService,
                embeddingModel,
                embeddingStore,
                archivoService,
                geminiService,
                qdrantClient
        );
    }

    @Test
    void testIngestarArchivoSuccess() throws Exception {
        // GIVEN
        MultipartFile mockFile = mock(MultipartFile.class);
        when(mockFile.getOriginalFilename()).thenReturn("test-document.pdf");
        
        when(archivoService.guardarArchivoYRetornarId(any())).thenReturn("mongoId123");
        when(tikaExtractorService.extractText(any())).thenReturn(TEXTO_DE_PRUEBA);

        String geminiText = "[\"Concepto A\", \"Concepto B\"]";
        String responseJson = "{\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"" + geminiText.replace("\"", "\\\"") + "\"}]}}]}";
        GenerateContentResponse mockResponse = GenerateContentResponse.fromJson(responseJson);
        when(geminiService.askGemini(anyString())).thenReturn(mockResponse);

        TextSegment chunk = TextSegment.from("Contenido chunk", dev.langchain4j.data.document.Metadata.from("key", "value"));
        when(chunkingService.chunkear(anyString(), anyString(), anyString())).thenReturn(List.of(chunk));

        Embedding embedding = Embedding.from(new float[3072]);
        Response<Embedding> embResponse = Response.from(embedding);
        when(embeddingModel.embed(anyString())).thenReturn(embResponse);

        // WHEN
        RagIngestionService.IngestaResultado result = ragIngestionService.ingestarArchivo(mockFile);

        // THEN
        assertNotNull(result);
        assertTrue(result.exitoso());
        assertEquals("mongoId123", result.archivoId());
        assertEquals("test-document.pdf", result.nombreArchivo());
        assertEquals(1, result.totalChunks());
        assertNull(result.errorMensaje());

        verify(archivoService).guardarArchivoYRetornarId(mockFile);
        verify(tikaExtractorService).extractText(mockFile);
        verify(geminiService).askGemini(anyString());
        verify(archivoService).actualizarSubtemas(eq("mongoId123"), eq(List.of("Concepto A", "Concepto B")));
        verify(chunkingService).chunkear(TEXTO_DE_PRUEBA, "mongoId123", "test-document.pdf");
        verify(embeddingModel).embed("Contenido chunk");
        verify(embeddingStore).add(eq(embedding), eq(chunk));
    }

    @Test
    void testIngestarArchivoTikaFails() throws Exception {
        // GIVEN
        MultipartFile mockFile = mock(MultipartFile.class);
        when(mockFile.getOriginalFilename()).thenReturn("error-document.pdf");

        when(archivoService.guardarArchivoYRetornarId(any())).thenReturn("mongoId123");
        when(tikaExtractorService.extractText(any())).thenThrow(new RuntimeException("Tika failed to parse PDF"));

        // WHEN
        RagIngestionService.IngestaResultado result = ragIngestionService.ingestarArchivo(mockFile);

        // THEN
        assertNotNull(result);
        assertFalse(result.exitoso());
        assertNull(result.archivoId());
        assertEquals("error-document.pdf", result.nombreArchivo());
        assertEquals(0, result.totalChunks());
        assertEquals("Tika failed to parse PDF", result.errorMensaje());

        verify(archivoService).guardarArchivoYRetornarId(mockFile);
        verify(tikaExtractorService).extractText(mockFile);
        verifyNoInteractions(geminiService);
        verifyNoInteractions(chunkingService);
        verifyNoInteractions(embeddingModel);
        verifyNoInteractions(embeddingStore);
    }

    @Test
    void testIngestarArchivoGeminiFails() throws Exception {
        // GIVEN
        MultipartFile mockFile = mock(MultipartFile.class);
        when(mockFile.getOriginalFilename()).thenReturn("test-document.pdf");
        
        when(archivoService.guardarArchivoYRetornarId(any())).thenReturn("mongoId123");
        when(tikaExtractorService.extractText(any())).thenReturn(TEXTO_DE_PRUEBA);
        when(geminiService.askGemini(anyString())).thenThrow(new RuntimeException("Gemini quota exceeded or PII filter error"));

        TextSegment chunk = TextSegment.from("Contenido chunk", dev.langchain4j.data.document.Metadata.from("key", "value"));
        when(chunkingService.chunkear(anyString(), anyString(), anyString())).thenReturn(List.of(chunk));

        Embedding embedding = Embedding.from(new float[3072]);
        Response<Embedding> embResponse = Response.from(embedding);
        when(embeddingModel.embed(anyString())).thenReturn(embResponse);

        // WHEN
        RagIngestionService.IngestaResultado result = ragIngestionService.ingestarArchivo(mockFile);

        // THEN
        assertNotNull(result);
        assertTrue(result.exitoso()); // Ingestion still succeeds even if subtema extraction fails
        assertEquals("mongoId123", result.archivoId());
        assertEquals(1, result.totalChunks());

        verify(archivoService).guardarArchivoYRetornarId(mockFile);
        verify(tikaExtractorService).extractText(mockFile);
        verify(geminiService).askGemini(anyString());
        verify(archivoService, never()).actualizarSubtemas(anyString(), any());
        verify(chunkingService).chunkear(TEXTO_DE_PRUEBA, "mongoId123", "test-document.pdf");
        verify(embeddingModel).embed("Contenido chunk");
        verify(embeddingStore).add(eq(embedding), eq(chunk));
    }
}
