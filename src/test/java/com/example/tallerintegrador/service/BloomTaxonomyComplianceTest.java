package com.example.tallerintegrador.service;

import com.example.tallerintegrador.agents.ContextSelectorAgent;
import com.example.tallerintegrador.agents.EvaluationOrchestratorAgent;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.entidades.postgres.NivelConocimiento;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.GenerateContentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class BloomTaxonomyComplianceTest {

    private static final Logger log = LoggerFactory.getLogger(BloomTaxonomyComplianceTest.class);

    @Mock
    private GeminiService geminiService;
    @Mock
    private RagRetrieverService ragRetrieverService;
    @Mock
    private ContextSelectorAgent contextSelectorAgent;
    @Mock
    private PreguntaDedupService preguntaDedupService;
    
    private PromptTemplateService promptTemplateService;
    private EvaluationOrchestratorAgent evaluationOrchestratorAgent;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        promptTemplateService = new PromptTemplateService();
        evaluationOrchestratorAgent = new EvaluationOrchestratorAgent(
                ragRetrieverService,
                contextSelectorAgent,
                geminiService,
                null, // agentJudgeAgent no es requerido para generación
                promptTemplateService,
                preguntaDedupService
        );
    }

    @Test
    void testEvaluationGenerationBloomComplianceRate() throws Exception {
        // GIVEN
        String tema = "Los elementos de la comunicación";
        String targetBloomLevel = "Analizar";
        
        // Mock de dependencias
        when(ragRetrieverService.recuperar(anyString(), any())).thenReturn(Collections.emptyList());
        when(contextSelectorAgent.seleccionarContexto(anyList(), anyString(), anyString())).thenReturn("Contexto RAG de prueba");
        
        Usuario mockUsuario = new Usuario();
        mockUsuario.setId(1L);
        mockUsuario.setNivelConocimiento(NivelConocimiento.INTERMEDIO);
        when(preguntaDedupService.obtenerUsuarioPorEmail(anyString())).thenReturn(mockUsuario);
        when(preguntaDedupService.obtenerPreguntasEvitar(anyString(), any())).thenReturn(Collections.emptyList());
        when(preguntaDedupService.esPreguntaSimilar(anyString(), anyLong(), anyList())).thenReturn(false);

        // Simulamos respuestas válidas que cumplen con el nivel "Analizar"
        String conformantResponse = """
            {
              "preguntas": [
                {
                  "enunciado": "¿Qué pasaría si el emisor usa un código que el receptor no comprende en absoluto?",
                  "opciones_o_respuesta": ["A) Hay ruido", "B) Se interrumpe la comunicación", "C) No hay decodificación", "D) Todas las anteriores"],
                  "respuesta_correcta": "C) No hay decodificación",
                  "justificacion_pregunta": "Evalúa la relación y consecuencias lógicas del código."
                }
              ],
              "evaluacion_bloom": {
                  "nivel_bloom": "Analizar",
                  "nivel_bloom_orden": 4,
                  "es_hots": true
              }
            }
            """;
        
        String responseJson = "{\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"" + conformantResponse.replace("\"", "\\\"").replace("\n", " ") + "\"}]}}]}";
        GenerateContentResponse mockResponse = GenerateContentResponse.fromJson(responseJson);
        
        when(geminiService.askGemini(anyString())).thenReturn(mockResponse);

        // WHEN
        int totalTestRuns = 10;
        int conformantCount = 0;

        for (int i = 0; i < totalTestRuns; i++) {
            Map<String, Object> result = evaluationOrchestratorAgent.generarEvaluacion(
                    tema, null, "OPCION_MULTIPLE", targetBloomLevel, "STRUCTURED_OUTPUT", 1, "test@student.com"
            );
            
            assertNotNull(result);
            Map<String, Object> preguntasJson = (Map<String, Object>) result.get("preguntas_json");
            assertNotNull(preguntasJson);
            
            Map<String, Object> bloomMetadata = (Map<String, Object>) preguntasJson.get("evaluacion_bloom");
            if (bloomMetadata != null) {
                String actualLevel = (String) bloomMetadata.get("nivel_bloom");
                if (targetBloomLevel.equalsIgnoreCase(actualLevel)) {
                    conformantCount++;
                }
            }
        }

        // THEN
        double complianceRate = (double) conformantCount / totalTestRuns;
        log.info("Bloom Compliance Rate calculated: {}%", complianceRate * 100);
        
        // Asertamos que la tasa de cumplimiento es >= 90% (0.90)
        assertTrue(complianceRate >= 0.90, "La tasa de conformidad con la taxonomía de Bloom debe ser mayor o igual al 90%");
    }
    
    @Test
    void testPromptStructureIncludesBloomDirectives() {
        // GIVEN
        String targetBloom = "Evaluar";
        
        // WHEN
        String prompt = promptTemplateService.build("STRUCTURED_OUTPUT", "OPCION_MULTIPLE", targetBloom, "INTERMEDIO", "Texto de prueba", 1);
        
        // THEN
        assertNotNull(prompt);
        assertTrue(prompt.contains("Bloom"), "El prompt debe mencionar la taxonomía de Bloom");
        assertTrue(prompt.contains("Nivel Bloom objetivo: " + targetBloom), "El prompt debe especificar el nivel Bloom objetivo");
        assertTrue(prompt.contains("evaluacion_bloom"), "El prompt debe exigir el esquema de retorno con el objeto evaluacion_bloom");
    }
}
