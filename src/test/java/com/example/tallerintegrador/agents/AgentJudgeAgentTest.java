package com.example.tallerintegrador.agents;

import com.example.tallerintegrador.service.GeminiService;
import com.google.genai.types.GenerateContentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AgentJudgeAgentTest {

    @Mock
    private GeminiService geminiService;

    private AgentJudgeAgent agentJudgeAgent;

    @BeforeEach
    void setUp() {
        agentJudgeAgent = new AgentJudgeAgent(geminiService);
    }

    @Test
    void testEvaluarRespuestaUnitariaValidJson() {
        // GIVEN
        String promptText = "{\"esCorrecta\": true, \"puntaje\": 100, \"explicacion\": \"Excelente respuesta\"}";
        String responseJson = "{\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"" + promptText.replace("\"", "\\\"") + "\"}]}}]}";
        GenerateContentResponse mockResponse = GenerateContentResponse.fromJson(responseJson);
        
        when(geminiService.askGemini(anyString())).thenReturn(mockResponse);

        // WHEN
        Map<String, Object> result = agentJudgeAgent.evaluarRespuestaUnitaria(
                "¿Qué es el sujeto?", "El elemento que realiza la acción", "El sujeto es quien realiza la acción", 1, "ABIERTA"
        );

        // THEN
        assertNotNull(result);
        assertTrue(result.containsKey("evaluacion"));
        Map<String, Object> evaluacion = (Map<String, Object>) result.get("evaluacion");
        assertEquals(true, evaluacion.get("esCorrecta"));
        assertEquals(20.0, evaluacion.get("puntaje")); // 100% of 20.0
        assertEquals("Excelente respuesta", evaluacion.get("explicacion"));
    }

    @Test
    void testEvaluarRespuestaUnitariaMalformedJsonRegexFallback() {
        // GIVEN
        String promptText = "Texto basura... {\"esCorrecta\": true, \"puntaje\": 80, \"explicacion\": \"Respuesta decente\"} más basura";
        String responseJson = "{\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"" + promptText.replace("\"", "\\\"") + "\"}]}}]}";
        GenerateContentResponse mockResponse = GenerateContentResponse.fromJson(responseJson);

        when(geminiService.askGemini(anyString())).thenReturn(mockResponse);

        // WHEN
        Map<String, Object> result = agentJudgeAgent.evaluarRespuestaUnitaria(
                "¿Qué es el sujeto?", "El elemento que realiza la acción", "Sujeto realiza acción", 1, "ABIERTA"
        );

        // THEN
        assertNotNull(result);
        assertTrue(result.containsKey("evaluacion"));
        Map<String, Object> evaluacion = (Map<String, Object>) result.get("evaluacion");
        assertEquals(true, evaluacion.get("esCorrecta"));
        assertEquals(16.0, evaluacion.get("puntaje")); // 80% of 20.0
        assertEquals("Respuesta decente", evaluacion.get("explicacion"));
    }

    @Test
    void testEvaluarRespuestaUnitariaTotalFailure() {
        // GIVEN
        String promptText = "Completamente roto y sin formato JSON";
        String responseJson = "{\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"" + promptText + "\"}]}}]}";
        GenerateContentResponse mockResponse = GenerateContentResponse.fromJson(responseJson);

        when(geminiService.askGemini(anyString())).thenReturn(mockResponse);

        // WHEN
        Map<String, Object> result = agentJudgeAgent.evaluarRespuestaUnitaria(
                "¿Qué es el sujeto?", "El elemento que realiza la acción", "No lo sé", 1, "ABIERTA"
        );

        // THEN
        assertNotNull(result);
        assertTrue(result.containsKey("evaluacion"));
        Map<String, Object> evaluacion = (Map<String, Object>) result.get("evaluacion");
        assertEquals(false, evaluacion.get("esCorrecta"));
        assertEquals(0.0, evaluacion.get("puntaje"));
        assertEquals("No se pudo procesar la evaluación de la IA.", evaluacion.get("explicacion"));
    }
}
