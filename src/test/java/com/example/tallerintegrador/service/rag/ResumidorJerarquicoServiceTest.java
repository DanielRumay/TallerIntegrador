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

    @Test
    @DisplayName("El aviso de avance sube seccion a seccion, no salta de cero al final")
    void elAvanceSubeSeccionASeccion() {
        List<String> vistos = new ArrayList<>();

        servicio.resumirSecciones(secciones(5),
                (hechas, total) -> vistos.add(hechas + "/" + total));

        // Lo que fallaba antes: solo existia el "0 de N" inicial, asi que el docente miraba
        // un cero fijo durante minutos y creia que se habia colgado.
        assertEquals(List.of("0/5", "1/5", "2/5", "3/5", "4/5", "5/5"), vistos);
    }

    @Test
    @DisplayName("El total anunciado es el de secciones agrupadas, no el original")
    void elTotalAnunciadoEsElQueSeVaARecorrer() {
        List<Integer> totales = new ArrayList<>();

        // Por encima del techo: las secciones se agrupan y se hacen menos llamadas.
        servicio.resumirSecciones(secciones(ResumidorJerarquicoService.MAX_RESUMENES_NIVEL_1 + 20),
                (hechas, total) -> totales.add(total));

        int anunciado = totales.get(0);
        assertTrue(totales.stream().allMatch(t -> t == anunciado),
                "el total no puede cambiar a mitad de la barra");
        assertEquals(anunciado, llamadas.get(),
                "el total anunciado debe ser el numero de secciones que de verdad se recorren");
    }

    @Test
    @DisplayName("Una seccion que falla igual cuenta como avanzada")
    void unaSeccionFallidaNoDetieneElContador() {
        // La segunda llamada revienta; las demas responden.
        GenerateContentResponse ok = mock(GenerateContentResponse.class);
        when(ok.text()).thenReturn("Resumen simulado de la seccion.");
        AtomicInteger n = new AtomicInteger();
        when(geminiService.askGemini(anyString())).thenAnswer(inv -> {
            if (n.incrementAndGet() == 2) throw new RuntimeException("timeout simulado");
            return ok;
        });

        List<Integer> hechas = new ArrayList<>();
        servicio.resumirSecciones(secciones(4), (h, t) -> hechas.add(h));

        // Si se contaran solo los resumenes con exito, la barra jamas llegaria al total y se
        // quedaria clavada cerca del final sin que nada estuviera realmente fallando.
        assertEquals(4, hechas.get(hechas.size() - 1));
    }

    @Test
    @DisplayName("Si el aviso de avance revienta, el resumen se completa igual")
    void unFalloAlAvisarNoTumbaLaIngesta() {
        var resumenes = servicio.resumirSecciones(secciones(3), (h, t) -> {
            throw new IllegalStateException("la barra ya no existe");
        });

        // El progreso es cosmetico: no puede costarle al docente el material que subio.
        assertEquals(3, resumenes.size());
    }
}
