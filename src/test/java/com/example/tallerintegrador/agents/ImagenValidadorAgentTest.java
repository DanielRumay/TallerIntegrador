package com.example.tallerintegrador.agents;

import com.example.tallerintegrador.service.ia.GeminiService;
import com.example.tallerintegrador.service.metricas.TelemetriaIAService;
import com.google.genai.types.GenerateContentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ImagenValidadorAgent es lo que separa "la API de Gemini devolvió algún byte de imagen" de
 * "la imagen corresponde a lo que el enunciado dice que muestra". Estas pruebas fijan las
 * tres salidas posibles del veredicto y confirman que un fallo del validador nunca bloquea
 * la entrega del reactivo (falla abierto, no cerrado).
 */
@ExtendWith(MockitoExtension.class)
class ImagenValidadorAgentTest {

    @Mock
    private GeminiService geminiService;
    @Mock
    private TelemetriaIAService telemetriaIAService;

    private ImagenValidadorAgent agente;

    private static final String IMAGEN_BASE64 = Base64.getEncoder().encodeToString("contenido-simulado".getBytes());

    @BeforeEach
    void setUp() {
        agente = new ImagenValidadorAgent(geminiService, telemetriaIAService);
    }

    private GenerateContentResponse respuestaCon(String jsonInterno) {
        String responseJson = "{\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"" +
                jsonInterno.replace("\"", "\\\"") + "\"}]}}]}";
        return GenerateContentResponse.fromJson(responseJson);
    }

    @Test
    @DisplayName("Marca como válida una imagen que el modelo confirma alineada al enunciado")
    void aceptaImagenAlineada() {
        when(geminiService.askGeminiConImagen(anyString(), any(byte[].class), anyString()))
                .thenReturn(respuestaCon("{\"valida\": true, \"motivo\": \"Contiene la flecha indicada.\"}"));

        var veredicto = agente.validar("diagrama del ciclo del agua", "Observa la flecha marcada", IMAGEN_BASE64, 1L);

        assertTrue(veredicto.valida());
        assertEquals("Contiene la flecha indicada.", veredicto.motivo());
    }

    @Test
    @DisplayName("Marca como inválida una imagen que el modelo señala como no correspondiente")
    void rechazaImagenNoAlineada() {
        when(geminiService.askGeminiConImagen(anyString(), any(byte[].class), anyString()))
                .thenReturn(respuestaCon("{\"valida\": false, \"motivo\": \"No se observa la flecha exigida por el enunciado.\"}"));

        var veredicto = agente.validar("diagrama del ciclo del agua", "Observa la flecha marcada", IMAGEN_BASE64, 1L);

        assertFalse(veredicto.valida());
        assertTrue(veredicto.motivo().contains("flecha"));
    }

    @Test
    @DisplayName("Sin imagen que validar, el veredicto es inválido sin llamar al modelo")
    void sinImagenEsInvalidaSinLlamarAlModelo() {
        var veredicto = agente.validar("prompt", "enunciado", null, 1L);

        assertFalse(veredicto.valida());
    }

    @Test
    @DisplayName("Un fallo del validador falla abierto: se acepta la imagen, no se bloquea el reactivo")
    void fallaAbiertoAnteErrorDelValidador() {
        when(geminiService.askGeminiConImagen(anyString(), any(byte[].class), anyString()))
                .thenThrow(new RuntimeException("timeout de la API"));

        var veredicto = agente.validar("prompt", "enunciado", IMAGEN_BASE64, 1L);

        assertTrue(veredicto.valida(), "Un error del validador no debe impedir entregar el reactivo");
    }

    @Test
    @DisplayName("Registra telemetría en cada evaluación, sea cual sea el resultado")
    void registraTelemetriaSiempre() {
        when(geminiService.askGeminiConImagen(anyString(), any(byte[].class), anyString()))
                .thenReturn(respuestaCon("{\"valida\": true, \"motivo\": \"ok\"}"));

        agente.validar("prompt", "enunciado", IMAGEN_BASE64, 7L);

        verify(telemetriaIAService).registrar(any(com.example.tallerintegrador.entidades.postgres.EventoMetricaIA.class));
    }
}
