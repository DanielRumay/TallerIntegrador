package com.example.tallerintegrador.service;

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

    @Mock
    private TikaExtractorService tikaExtractorService;
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
        when(tikaExtractorService.extractText(any())).thenReturn("Contenido educativo del PDF");

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
        verify(chunkingService).chunkear("Contenido educativo del PDF", "mongoId123", "test-document.pdf");
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
        when(tikaExtractorService.extractText(any())).thenReturn("Contenido educativo del PDF");
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
        verify(chunkingService).chunkear("Contenido educativo del PDF", "mongoId123", "test-document.pdf");
        verify(embeddingModel).embed("Contenido chunk");
        verify(embeddingStore).add(eq(embedding), eq(chunk));
    }
}
