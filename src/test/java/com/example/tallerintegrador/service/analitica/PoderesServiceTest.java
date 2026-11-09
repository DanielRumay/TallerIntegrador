package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.Intento;
import com.example.tallerintegrador.entidades.postgres.MovimientoMonedas;
import com.example.tallerintegrador.repository.IntentoRepository;
import com.example.tallerintegrador.repository.MovimientoMonedasRepository;
import com.example.tallerintegrador.service.ia.GeminiService;
import com.google.genai.types.GenerateContentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PoderesServiceTest {

    @Mock private MonedasService monedasService;
    @Mock private MovimientoMonedasRepository movimientoRepository;
    @Mock private IntentoRepository intentoRepository;
    @Mock private GeminiService geminiService;

    private PoderesService poderes;
    private static final String PARTIDA = "partida-1234";
    private final PoderesService.Pregunta pregunta = new PoderesService.Pregunta(
            "¿Cuál es el núcleo del sujeto?", List.of("A) El sustantivo", "B) El verbo"), "A) El sustantivo", "el sujeto");

    @BeforeEach
    void setUp() {
        poderes = new PoderesService(monedasService, movimientoRepository, intentoRepository, geminiService, mock(com.example.tallerintegrador.service.metricas.TelemetriaIAService.class));
    }

    private MovimientoMonedas gasto(String motivo) {
        return new MovimientoMonedas(1L, -10, MovimientoMonedas.Tipo.GASTO, motivo, "poder:" + PARTIDA + ":x");
    }

    private GenerateContentResponse respuestaIa(String texto) {
        GenerateContentResponse r = mock(GenerateContentResponse.class);
        when(r.text()).thenReturn(texto);
        return r;
    }

    @Test
    void laPistaSubeDePrecioDentroDeLaMismaPartida() {
        when(movimientoRepository.findByUsuarioIdAndClaveStartingWith(1L, "poder:" + PARTIDA + ":"))
                .thenReturn(List.of(), List.of(gasto("Pista de Aria")), List.of(gasto("Pista de Aria"), gasto("Pista de Aria")));
        assertEquals(10, poderes.precioDe(1L, PARTIDA, PoderesService.Poder.PISTA));
        assertEquals(20, poderes.precioDe(1L, PARTIDA, PoderesService.Poder.PISTA));
        assertEquals(40, poderes.precioDe(1L, PARTIDA, PoderesService.Poder.PISTA));
    }

    @Test
    void noSeUsanMasDeTresPoderesPorPartida() {
        when(movimientoRepository.findByUsuarioIdAndClaveStartingWith(1L, "poder:" + PARTIDA + ":"))
                .thenReturn(List.of(gasto("50/50"), gasto("Congelar el tiempo"), gasto("Reloj de arena (+15 s)")));
        assertThrows(IllegalStateException.class, () -> poderes.usar(1L, PARTIDA, PoderesService.Poder.TIEMPO, null));
        verify(monedasService, never()).gastar(any(), anyInt(), any(), any());
    }

    @Test
    void usarUnPoderCobraConClaveUnicaPorUso() {
        when(movimientoRepository.findByUsuarioIdAndClaveStartingWith(1L, "poder:" + PARTIDA + ":")).thenReturn(List.of(gasto("50/50")));
        when(monedasService.saldo(1L)).thenReturn(100);
        when(monedasService.gastar(1L, 10, "Déjame pensar (+15 s)", "poder:" + PARTIDA + ":2")).thenReturn(90);

        Map<String, Object> r = poderes.usar(1L, PARTIDA, PoderesService.Poder.TIEMPO, null);

        assertEquals(90, r.get("saldo"));
        assertEquals(1, r.get("restantes"));
    }

    @Test
    void laPistaNuncaRevelaLaRespuesta() {
        GenerateContentResponse r = respuestaIa("La respuesta es el sustantivo, claro.");
        when(geminiService.askGemini(anyString())).thenReturn(r);
        String pista = poderes.generarPista(pregunta);
        assertFalse(pista.toLowerCase().contains("sustantivo"));
        assertTrue(pista.contains("el sujeto"), "usa la pista genérica con el concepto");
    }

    @Test
    void unaPistaValidaDeLaIaSeUsaTalCual() {
        GenerateContentResponse r = respuestaIa("Piensa en qué palabra nombra a quien realiza la acción.");
        when(geminiService.askGemini(anyString())).thenReturn(r);
        assertEquals("Piensa en qué palabra nombra a quien realiza la acción.", poderes.generarPista(pregunta));
    }

    @Test
    void siLaIaFallaHayPistaGenerica() {
        when(geminiService.askGemini(anyString())).thenThrow(new RuntimeException("503"));
        assertEquals(PoderesService.pistaGenerica(pregunta), poderes.generarPista(pregunta));
    }

    @Test
    void elBonoEstaAcotado() {
        assertEquals(0, PoderesService.bonoDe(0, 1));
        assertEquals(3 + 2, PoderesService.bonoDe(3, 4));
        assertEquals(10, PoderesService.bonoDe(50, 50));
        assertEquals(0, PoderesService.bonoDe(-5, 0));
    }

    @Test
    void sinUnIntentoRecienteNoHayBono() {
        Intento viejo = new Intento();
        viejo.setFecha(LocalDateTime.now().minusHours(2));
        when(intentoRepository.findByUsuarioIdOrderByFechaDesc(1L)).thenReturn(List.of(viejo));

        assertThrows(IllegalStateException.class, () -> poderes.cobrarBono(1L, PARTIDA, 5, 5));
        verify(monedasService, never()).premiar(any(), anyInt(), any(), any());
    }

    @Test
    void elBonoSeCobraUnaVezPorPartidaYCincoAlDia() {
        Intento reciente = new Intento();
        reciente.setFecha(LocalDateTime.now().minusMinutes(1));
        when(intentoRepository.findByUsuarioIdOrderByFechaDesc(1L)).thenReturn(List.of(reciente));
        when(movimientoRepository.findByUsuarioIdAndClaveStartingWith(1L, "partida:")).thenReturn(List.of());
        when(monedasService.premiar(eq(1L), eq(10), any(), eq("partida:" + PARTIDA))).thenReturn(true);
        assertEquals(10, poderes.cobrarBono(1L, PARTIDA, 5, 5));

        List<MovimientoMonedas> cinco = new ArrayList<>();
        for (int i = 0; i < 5; i++) cinco.add(new MovimientoMonedas(1L, 5, MovimientoMonedas.Tipo.PREMIO, "b", "partida:" + i));
        when(movimientoRepository.findByUsuarioIdAndClaveStartingWith(1L, "partida:")).thenReturn(cinco);
        assertEquals(0, poderes.cobrarBono(1L, "otra-partida-99", 5, 5));
    }

    @Test
    void unIdDePartidaRaroSeRechaza() {
        assertThrows(IllegalArgumentException.class, () -> poderes.precioDe(1L, "../../x", PoderesService.Poder.PISTA));
    }
}
