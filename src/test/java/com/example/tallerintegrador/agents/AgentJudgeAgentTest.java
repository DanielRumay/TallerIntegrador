package com.example.tallerintegrador.agents;

import com.example.tallerintegrador.agents.judge.JuezDeRespuestaService;
import com.example.tallerintegrador.agents.judge.VeredictoJuez;
import com.example.tallerintegrador.service.metricas.TelemetriaIAService;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Desde la migración a salida estructurada, el juez ya no parsea texto: recibe un
 * VeredictoJuez tipado directamente de JuezDeRespuestaService (LangChain4j AiServices).
 * Estas pruebas mockean ese contrato en vez de la respuesta cruda de Gemini — no queda
 * ningún camino de "JSON malformado" que probar porque el esquema lo impide a nivel de API;
 * el único fallo posible es que la llamada no se complete, cubierto por
 * testFalloDeLlamadaProduceEvaluacionSegura.
 */
@ExtendWith(MockitoExtension.class)
public class AgentJudgeAgentTest {

    @Mock
    private JuezDeRespuestaService juezDeRespuestaService;
    @Mock
    private TelemetriaIAService telemetriaIAService;

    private AgentJudgeAgent agentJudgeAgent;

    @BeforeEach
    void setUp() {
        agentJudgeAgent = new AgentJudgeAgent(juezDeRespuestaService, telemetriaIAService);
    }

    private Result<VeredictoJuez> resultadoCon(VeredictoJuez veredicto) {
        return Result.<VeredictoJuez>builder()
                .content(veredicto)
                .tokenUsage(new TokenUsage(10, 20))
                .build();
    }

    @Test
    @DisplayName("Evalúa una respuesta abierta correcta y escala el puntaje sobre el peso de la pregunta")
    void testEvaluarRespuestaAbiertaCorrecta() {
        VeredictoJuez veredicto = new VeredictoJuez(true, 100, "Excelente respuesta", List.of(), null);
        when(juezDeRespuestaService.evaluar(anyString())).thenReturn(resultadoCon(veredicto));

        Map<String, Object> result = agentJudgeAgent.evaluarRespuestaUnitaria(
                "¿Qué es el sujeto?", "El elemento que realiza la acción",
                "El sujeto es quien realiza la acción", 1, "ABIERTA");

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> evaluacion = (Map<String, Object>) result.get("evaluacion");
        assertEquals(true, evaluacion.get("esCorrecta"));
        assertEquals(20.0, evaluacion.get("puntaje")); // 100% de 20.0 (única pregunta)
        assertEquals("Excelente respuesta", evaluacion.get("explicacion"));
    }

    @Test
    @DisplayName("Las preguntas binarias ignoran el puntaje intermedio del modelo y fuerzan 0 o 100")
    void testPreguntaBinariaFuerzaPuntajeBinario() {
        // El modelo devuelve esCorrecta=false con un puntaje de 40 — no coherente para
        // una binaria, y eso es exactamente lo que la regla de negocio debe corregir.
        VeredictoJuez veredicto = new VeredictoJuez(false, 40, "Incorrecto", List.of(), null);
        when(juezDeRespuestaService.evaluar(anyString())).thenReturn(resultadoCon(veredicto));

        Map<String, Object> result = agentJudgeAgent.evaluarRespuestaUnitaria(
                "¿Es Lima la capital de Perú?", "VERDADERO", "FALSO", 1, "VERDADERO_FALSO");

        @SuppressWarnings("unchecked")
        Map<String, Object> evaluacion = (Map<String, Object>) result.get("evaluacion");
        assertEquals(0, evaluacion.get("puntaje_porcentaje"));
        assertEquals(0.0, evaluacion.get("puntaje"));
    }

    @Test
    @DisplayName("DETECCION_ERRORES conserva los detalles y el texto corregido en el mapa de salida")
    void testDeteccionErroresIncluyeDetallesYTextoCorregido() {
        VeredictoJuez veredicto = new VeredictoJuez(
                true, 100, "Corrigió ambos errores correctamente.",
                List.of(new VeredictoJuez.DetalleCorreccion("pared celular", true)),
                "La célula animal contiene membrana celular...");
        when(juezDeRespuestaService.evaluar(anyString())).thenReturn(resultadoCon(veredicto));

        Map<String, Object> result = agentJudgeAgent.evaluarRespuestaUnitaria(
                "Texto con errores", "membrana celular", "corregí: membrana celular", 1, "DETECCION_ERRORES");

        @SuppressWarnings("unchecked")
        Map<String, Object> evaluacion = (Map<String, Object>) result.get("evaluacion");
        assertTrue(evaluacion.containsKey("detalles"));
        assertEquals("La célula animal contiene membrana celular...", evaluacion.get("texto_corregido"));
    }

    @Test
    @DisplayName("Las preguntas no-DETECCION_ERRORES no exponen los campos detalles/texto_corregido")
    void testPreguntaAbiertaNoExponeCamposDeDeteccionErrores() {
        VeredictoJuez veredicto = new VeredictoJuez(true, 100, "Correcto", List.of(), null);
        when(juezDeRespuestaService.evaluar(anyString())).thenReturn(resultadoCon(veredicto));

        Map<String, Object> result = agentJudgeAgent.evaluarRespuestaUnitaria(
                "Pregunta", "esperada", "respuesta", 1, "ABIERTA");

        @SuppressWarnings("unchecked")
        Map<String, Object> evaluacion = (Map<String, Object>) result.get("evaluacion");
        assertFalse(evaluacion.containsKey("detalles"));
        assertFalse(evaluacion.containsKey("texto_corregido"));
    }

    @Test
    @DisplayName("Un fallo total de la llamada produce una evaluación segura en vez de propagar la excepción")
    void testFalloDeLlamadaProduceEvaluacionSegura() {
        when(juezDeRespuestaService.evaluar(anyString())).thenThrow(new RuntimeException("timeout de la API"));

        Map<String, Object> result = agentJudgeAgent.evaluarRespuestaUnitaria(
                "Pregunta", "esperada", "respuesta", 1, "ABIERTA");

        @SuppressWarnings("unchecked")
        Map<String, Object> evaluacion = (Map<String, Object>) result.get("evaluacion");
        assertEquals(false, evaluacion.get("esCorrecta"));
        assertEquals(0, evaluacion.get("puntaje_porcentaje"));
    }

    @Test
    @DisplayName("El puntaje se escala correctamente cuando hay varias preguntas en la evaluación")
    void testEscalaDePuntajeConVariasPreguntas() {
        VeredictoJuez veredicto = new VeredictoJuez(true, 80, "Buena respuesta", List.of(), null);
        when(juezDeRespuestaService.evaluar(anyString())).thenReturn(resultadoCon(veredicto));

        // 4 preguntas → peso máximo por pregunta = 20/4 = 5.0
        Map<String, Object> result = agentJudgeAgent.evaluarRespuestaUnitaria(
                "Pregunta", "esperada", "respuesta", 4, "ABIERTA");

        @SuppressWarnings("unchecked")
        Map<String, Object> evaluacion = (Map<String, Object>) result.get("evaluacion");
        assertEquals(5.0, evaluacion.get("puntaje_maximo"));
        assertEquals(4.0, evaluacion.get("puntaje")); // 80% de 5.0
    }
}
