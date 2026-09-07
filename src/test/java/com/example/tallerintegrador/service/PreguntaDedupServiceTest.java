package com.example.tallerintegrador.service;
import com.example.tallerintegrador.service.rag.PreguntaDedupService;

import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.service.metricas.TelemetriaIAService;
import com.example.tallerintegrador.repository.MaterialRepository;
import com.example.tallerintegrador.repository.RespuestaUsuarioRepository;
import com.example.tallerintegrador.repository.SemanaRepository;
import com.example.tallerintegrador.repository.UserRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PreguntaDedupServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RespuestaUsuarioRepository respuestaUsuarioRepository;
    @Mock
    private SemanaRepository semanaRepository;
    @Mock
    private MaterialRepository materialRepository;
    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;
    @Mock
    private TelemetriaIAService telemetriaIAService;
    /** Segunda etapa de la deduplicación: confirma si dos preguntas parecidas son la misma. */
    @Mock
    private com.example.tallerintegrador.service.ia.GeminiService geminiService;

    private PreguntaDedupService preguntaDedupService;

    @BeforeEach
    void setUp() {
        preguntaDedupService = new PreguntaDedupService(
                userRepository,
                respuestaUsuarioRepository,
                semanaRepository,
                materialRepository,
                embeddingModel,
                embeddingStore,
                telemetriaIAService,
                geminiService
        );
    }

    @Test
    void testEsPreguntaSimilarExactMatch() {
        // GIVEN
        String pregunta = "¿Qué es el sujeto?";
        List<String> ultimasPreguntas = List.of("¿Qué es el sujeto?", "¿Qué es el predicado?");
        
        // WHEN
        boolean result = preguntaDedupService.esPreguntaSimilar(pregunta, 1L, ultimasPreguntas);
        
        // THEN
        assertTrue(result);
        verifyNoInteractions(embeddingModel);
        verifyNoInteractions(embeddingStore);
    }

    @Test
    void testEsPreguntaSimilarVectorMatch() {
        // GIVEN
        String pregunta = "¿Qué es el sujeto?";
        List<String> ultimasPreguntas = List.of("¿Qué es el predicado?", "definicion de sujeto");
        Long usuarioId = 1L;

        Embedding queryEmbedding = Embedding.from(new float[3072]);
        Response<Embedding> embResponse = Response.from(queryEmbedding);
        when(embeddingModel.embed(pregunta)).thenReturn(embResponse);

        EmbeddingSearchResult<TextSegment> searchResult = mock(EmbeddingSearchResult.class);
        EmbeddingMatch<TextSegment> match = mock(EmbeddingMatch.class);
        TextSegment textSegment = mock(TextSegment.class);

        when(embeddingStore.search(any(EmbeddingSearchRequest.class))).thenReturn(searchResult);
        when(searchResult.matches()).thenReturn(List.of(match));
        when(match.embedded()).thenReturn(textSegment);
        when(textSegment.text()).thenReturn("definicion de sujeto");
        when(match.score()).thenReturn(0.98);

        // WHEN
        boolean result = preguntaDedupService.esPreguntaSimilar(pregunta, usuarioId, ultimasPreguntas);

        // THEN
        assertTrue(result);
        verify(embeddingModel).embed(pregunta);
        verify(embeddingStore).search(any(EmbeddingSearchRequest.class));
    }

    @Test
    void testEsPreguntaSimilarNoVectorMatch() {
        // GIVEN
        String pregunta = "¿Qué es el sujeto?";
        List<String> ultimasPreguntas = List.of("¿Qué es el predicado?");
        Long usuarioId = 1L;

        Embedding queryEmbedding = Embedding.from(new float[3072]);
        Response<Embedding> embResponse = Response.from(queryEmbedding);
        when(embeddingModel.embed(pregunta)).thenReturn(embResponse);

        EmbeddingSearchResult<TextSegment> searchResult = mock(EmbeddingSearchResult.class);
        when(embeddingStore.search(any(EmbeddingSearchRequest.class))).thenReturn(searchResult);
        when(searchResult.matches()).thenReturn(List.of()); // No matches >= 0.95

        // WHEN
        boolean result = preguntaDedupService.esPreguntaSimilar(pregunta, usuarioId, ultimasPreguntas);

        // THEN
        assertFalse(result);
        verify(embeddingModel).embed(pregunta);
        verify(embeddingStore).search(any(EmbeddingSearchRequest.class));
    }

    @Test
    void testEsPreguntaSimilarExceptionHandling() {
        // GIVEN
        String pregunta = "¿Qué es el sujeto?";
        List<String> ultimasPreguntas = List.of("¿Qué es el predicado?");
        Long usuarioId = 1L;

        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("Qdrant connection error"));

        // WHEN
        boolean result = preguntaDedupService.esPreguntaSimilar(pregunta, usuarioId, ultimasPreguntas);

        // THEN
        assertFalse(result);
    }
}
