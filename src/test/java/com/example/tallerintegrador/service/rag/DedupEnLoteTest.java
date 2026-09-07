package com.example.tallerintegrador.service.rag;

import com.example.tallerintegrador.service.ia.GeminiService;
import com.example.tallerintegrador.service.metricas.TelemetriaIAService;
import com.example.tallerintegrador.repository.MaterialRepository;
import com.example.tallerintegrador.repository.RespuestaUsuarioRepository;
import com.example.tallerintegrador.repository.SemanaRepository;
import com.example.tallerintegrador.repository.UserRepository;
import com.google.genai.types.GenerateContentResponse;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Deduplicacion DENTRO del mismo examen.
 *
 * Es el caso mas probable de repeticion y el que mas tiempo estuvo sin cubrir: las preguntas
 * de un mismo lote no estan aun en Qdrant —se indexan al terminar el examen—, asi que el
 * filtro historico no puede verlas. Dos reformulaciones generadas en la misma llamada
 * llegaban ambas al alumno.
 *
 * Los vectores se fabrican a mano para poder fijar la similitud exacta de cada par y probar
 * los tres tramos de decision sin depender de un modelo real.
 */
class DedupEnLoteTest {

    private EmbeddingModel embeddingModel;
    private GeminiService geminiService;
    private PreguntaDedupService servicio;

    /** texto -> vector, para controlar la similitud de cada par. */
    private final Map<String, float[]> vectores = new HashMap<>();

    private static final String ORIGINAL = "Por que las plantas necesitan luz solar?";
    /** Vector casi identico al de ORIGINAL: coseno ~0,999. */
    private static final String CASI_IDENTICA = "Por que las plantas necesitan la luz del sol?";
    /** Parecida pero no identica: coseno ~0,88, la zona donde decide el modelo. */
    private static final String REFORMULADA = "Que funcion cumple la luz solar en las plantas?";
    /** Claramente distinta: coseno ~0,0. */
    private static final String DISTINTA = "En que anio ocurrio la Revolucion Francesa?";

    @BeforeEach
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        geminiService = mock(GeminiService.class);

        vectores.put(ORIGINAL,      new float[]{1.0f, 0.0f, 0.0f});
        vectores.put(CASI_IDENTICA, new float[]{0.999f, 0.045f, 0.0f});
        vectores.put(REFORMULADA,   new float[]{0.88f, 0.475f, 0.0f});
        vectores.put(DISTINTA,      new float[]{0.0f, 0.0f, 1.0f});

        when(embeddingModel.embed(anyString())).thenAnswer(inv -> {
            String texto = inv.getArgument(0);
            float[] v = vectores.getOrDefault(texto, new float[]{0f, 0f, 1f});
            return Response.from(Embedding.from(v));
        });

        servicio = new PreguntaDedupService(
                mock(UserRepository.class),
                mock(RespuestaUsuarioRepository.class),
                mock(SemanaRepository.class),
                mock(MaterialRepository.class),
                embeddingModel,
                mock(EmbeddingStore.class),
                mock(TelemetriaIAService.class),
                geminiService);
    }

    private void elModeloDice(String veredicto) {
        GenerateContentResponse r = mock(GenerateContentResponse.class);
        when(r.text()).thenReturn(veredicto);
        when(geminiService.askGemini(anyString())).thenReturn(r);
    }

    @Test
    @DisplayName("Una pregunta casi identica del mismo lote se rechaza sin consultar al modelo")
    void casiIdenticaSeRechazaSola() {
        boolean repite = servicio.repiteAlgunaDelLote(CASI_IDENTICA, List.of(ORIGINAL));

        assertTrue(repite);
        verify(geminiService, never()).askGemini(anyString());
    }

    @Test
    @DisplayName("Una reformulacion se detecta preguntando al modelo — el hueco que existia")
    void reformulacionSeDetectaConElModelo() {
        // Con el filtro anterior esta pasaba: texto distinto y sin estar en Qdrant todavia.
        elModeloDice("MISMA");

        assertTrue(servicio.repiteAlgunaDelLote(REFORMULADA, List.of(ORIGINAL)));
        verify(geminiService).askGemini(anyString());
    }

    @Test
    @DisplayName("Parecidas pero distintas se aceptan: no se rechaza por compartir tema")
    void parecidaPeroDistintaSeAcepta() {
        // "Que es la fotosintesis?" y "Donde ocurre la fotosintesis?" comparten vocabulario y
        // son preguntas legitimas. Bajar el umbral sin segunda etapa las habria rechazado.
        elModeloDice("DISTINTAS");

        assertFalse(servicio.repiteAlgunaDelLote(REFORMULADA, List.of(ORIGINAL)));
    }

    @Test
    @DisplayName("Una pregunta de otro tema ni siquiera llega al modelo")
    void distintaNoConsultaAlModelo() {
        assertFalse(servicio.repiteAlgunaDelLote(DISTINTA, List.of(ORIGINAL)));
        verify(geminiService, never()).askGemini(anyString());
    }

    @Test
    @DisplayName("Se compara contra TODAS las aceptadas, no solo la ultima")
    void comparaContraTodoElLote() {
        elModeloDice("DISTINTAS");

        // La repetida es de la PRIMERA del lote. Si solo se mirara la ultima, pasaria.
        boolean repite = servicio.repiteAlgunaDelLote(CASI_IDENTICA, List.of(ORIGINAL, DISTINTA));

        assertTrue(repite);
    }

    @Test
    @DisplayName("La primera pregunta del examen no tiene con que compararse")
    void primeraPreguntaSiempreSeAcepta() {
        assertFalse(servicio.repiteAlgunaDelLote(ORIGINAL, List.of()));
        assertFalse(servicio.repiteAlgunaDelLote(ORIGINAL, null));
    }

    @Test
    @DisplayName("Si falla el modelo de embeddings se acepta la pregunta, no se cae el examen")
    void falloDeEmbeddingsNoRompeLaGeneracion() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("sin red"));

        assertFalse(servicio.repiteAlgunaDelLote(CASI_IDENTICA, List.of(ORIGINAL)),
                "ante un fallo tecnico se acepta: una repetida es mejor que un examen sin generar");
    }

    @Test
    @DisplayName("Entradas vacias no lanzan excepcion")
    void toleraEntradasVacias() {
        assertFalse(servicio.repiteAlgunaDelLote(null, List.of(ORIGINAL)));
        assertFalse(servicio.repiteAlgunaDelLote("   ", List.of(ORIGINAL)));
    }
}
