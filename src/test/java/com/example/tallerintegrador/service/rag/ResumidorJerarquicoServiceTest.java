package com.example.tallerintegrador.service.rag;

import com.example.tallerintegrador.service.ia.GeminiService;
import com.google.genai.types.GenerateContentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Cada resumen es una peticion de red a Gemini. Sin techo, ingerir un libro sin capitulos
 * detectables lanzaba cerca de cien llamadas: lento, caro y facil de que alguna falle. Estas
 * pruebas fijan el techo y, sobre todo, que ponerlo NO haga desaparecer partes de la obra.
 */
class ResumidorJerarquicoServiceTest {

    private GeminiService geminiService;
    private ResumidorJerarquicoService servicio;
    private AtomicInteger llamadas;

    @BeforeEach
    void setUp() {
        geminiService = mock(GeminiService.class);
        llamadas = new AtomicInteger();

        GenerateContentResponse respuesta = mock(GenerateContentResponse.class);
        when(respuesta.text()).thenReturn("Resumen simulado de la seccion.");
        when(geminiService.askGemini(anyString())).thenAnswer(inv -> {
            llamadas.incrementAndGet();
            return respuesta;
        });

        servicio = new ResumidorJerarquicoService(geminiService);
    }

    private List<SegmentadorDocumentoService.Seccion> secciones(int cuantas) {
        List<SegmentadorDocumentoService.Seccion> lista = new ArrayList<>();
        for (int i = 0; i < cuantas; i++) {
            lista.add(new SegmentadorDocumentoService.Seccion(
                    "Capitulo " + (i + 1),
                    "Contenido del capitulo " + (i + 1) + ". ".repeat(20),
                    i));
        }
        return lista;
    }

    @Test
    @DisplayName("Con pocas secciones se resume una por una")
    void pocasSeccionesUnaPorUna() {
        var r = servicio.resumirSecciones(secciones(5));

        assertEquals(5, r.size());
        assertEquals(5, llamadas.get(), "una llamada por seccion");
    }

    @Test
    @DisplayName("Un libro largo no dispara cien llamadas: se agrupan las secciones")
    void libroLargoRespetaElTecho() {
        // 83 bloques es lo que produce el segmentador cuando cae a ventanas fijas con una
        // obra de unas 500 paginas. Antes eran 83 llamadas.
        var r = servicio.resumirSecciones(secciones(83));

        assertTrue(r.size() <= ResumidorJerarquicoService.MAX_RESUMENES_NIVEL_1,
                "se generaron " + r.size() + " resumenes, por encima del techo");
        assertTrue(llamadas.get() <= ResumidorJerarquicoService.MAX_RESUMENES_NIVEL_1,
                "se hicieron " + llamadas.get() + " llamadas");
    }

    @Test
    @DisplayName("Agrupar NO pierde ninguna parte de la obra")
    void agruparConservaTodoElContenido() {
        // Es la diferencia con el muestreo de subtemas: alli se pueden saltar secciones, aqui
        // no. Un capitulo sin resumir queda invisible para las preguntas de comprension
        // global, y nada avisaria de que falta.
        int total = 83;
        servicio.resumirSecciones(secciones(total));

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(geminiService, atLeastOnce()).askGemini(captor.capture());
        String todoLoEnviado = String.join(" ", captor.getAllValues());

        for (int i = 1; i <= total; i++) {
            assertTrue(todoLoEnviado.contains("Contenido del capitulo " + i + "."),
                    "el capitulo " + i + " nunca se envio a resumir");
        }
    }

    @Test
    @DisplayName("Una sola seccion: su resumen YA es el del documento")
    void unaSolaSeccionNoSeVuelveAResumir() {
        var r = servicio.resumirSecciones(secciones(1));
        int llamadasTrasNivel1 = llamadas.get();

        String global = servicio.resumirDocumento(r);

        assertEquals(r.get(0).resumen(), global);
        assertEquals(llamadasTrasNivel1, llamadas.get(),
                "no debe gastarse una llamada en resumir un unico resumen");
    }

    @Test
    @DisplayName("Sin secciones no se llama al modelo ni se devuelve nada")
    void sinSecciones() {
        assertTrue(servicio.resumirSecciones(List.of()).isEmpty());
        assertTrue(servicio.resumirSecciones(null).isEmpty());
        assertNull(servicio.resumirDocumento(List.of()));
        assertNull(servicio.resumirDocumento(null));
        assertEquals(0, llamadas.get());
    }

    @Test
    @DisplayName("Si el modelo falla, la ingesta continua sin resumenes")
    void falloDelModeloNoRompeLaIngesta() {
        when(geminiService.askGemini(anyString())).thenThrow(new RuntimeException("sin red"));

        var r = servicio.resumirSecciones(secciones(4));

        assertTrue(r.isEmpty(), "sin resumenes, pero sin excepcion hacia arriba");
    }
}
