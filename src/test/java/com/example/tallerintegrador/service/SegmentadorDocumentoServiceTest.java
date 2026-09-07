package com.example.tallerintegrador.service;

import com.example.tallerintegrador.service.rag.SegmentadorDocumentoService;
import com.example.tallerintegrador.service.rag.SegmentadorDocumentoService.Metodo;
import com.example.tallerintegrador.service.rag.SegmentadorDocumentoService.Segmentacion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La segmentacion decide sobre que texto se pediran los subtemas y como quedaran etiquetados
 * los fragmentos. Si parte de mas, genera secciones vacias que producen subtemas inventados;
 * si parte de menos, vuelve el defecto de resumir 300 paginas en tres palabras. Ambos fallos
 * son silenciosos, asi que se prueban los dos lados.
 */
class SegmentadorDocumentoServiceTest {

    private SegmentadorDocumentoService segmentador;

    @BeforeEach
    void setUp() {
        segmentador = new SegmentadorDocumentoService();
    }

    /** Relleno para superar el umbral de documento largo sin ensuciar las aserciones. */
    private String relleno(int caracteres) {
        return "Texto de contenido narrativo continuo para dar cuerpo a la seccion. "
                .repeat(Math.max(1, caracteres / 66));
    }

    @Test
    @DisplayName("Un documento corto no se parte: se trata como una sola seccion")
    void documentoCortoNoSeSegmenta() {
        Segmentacion r = segmentador.segmentar("Contenido breve de un PDF de clase normal.");

        assertEquals(Metodo.DOCUMENTO_COMPLETO, r.metodo());
        assertEquals(1, r.secciones().size());
        assertFalse(r.degradada());
    }

    @Test
    @DisplayName("Detecta capitulos con numeracion romana")
    void detectaCapitulosRomanos() {
        String obra = relleno(3000)
                + "\n\nCAPITULO I\n\n" + relleno(9000)
                + "\n\nCAPITULO II\n\n" + relleno(9000)
                + "\n\nCAPITULO III\n\n" + relleno(9000);

        Segmentacion r = segmentador.segmentar(obra);

        assertEquals(Metodo.ENCABEZADOS, r.metodo());
        assertTrue(r.secciones().size() >= 3, "esperaba al menos 3 secciones, hubo " + r.secciones().size());
        assertFalse(r.degradada());
    }

    @Test
    @DisplayName("Detecta capitulos escritos con letras y con tilde")
    void detectaCapitulosEnLetras() {
        String obra = "Capítulo primero\n\n" + relleno(9000)
                + "\n\nCapítulo segundo\n\n" + relleno(9000)
                + "\n\nCapítulo tercero\n\n" + relleno(9000);

        Segmentacion r = segmentador.segmentar(obra);

        assertEquals(Metodo.ENCABEZADOS, r.metodo());
        assertTrue(r.secciones().size() >= 3);
    }

    @Test
    @DisplayName("Detecta unidades y secciones numeradas de un libro de texto")
    void detectaUnidadesYSecciones() {
        String libro = "UNIDAD 1\n\n" + relleno(8000)
                + "\n\nUNIDAD 2\n\n" + relleno(8000)
                + "\n\nUNIDAD 3\n\n" + relleno(8000);

        Segmentacion r = segmentador.segmentar(libro);

        assertEquals(Metodo.ENCABEZADOS, r.metodo());
    }

    @Test
    @DisplayName("El texto anterior al primer capitulo no se pierde")
    void conservaElPrologo() {
        String prologo = relleno(4000);
        String obra = prologo
                + "\n\nCAPITULO I\n\n" + relleno(9000)
                + "\n\nCAPITULO II\n\n" + relleno(9000);

        Segmentacion r = segmentador.segmentar(obra);

        // Tirar el prologo perderia texto real del documento sin avisar.
        assertNull(r.secciones().get(0).titulo(),
                "la primera seccion debe ser el prologo, sin titulo");
        assertTrue(r.secciones().get(0).texto().length() > 1000);
    }

    @Test
    @DisplayName("Un titulo casi sin contenido debajo se funde con la seccion anterior")
    void fusionaSeccionesVacias() {
        String obra = "CAPITULO I\n\n" + relleno(9000)
                + "\n\nCAPITULO II\n\nFin.\n\n"   // portadilla, practicamente vacia
                + "\n\nCAPITULO III\n\n" + relleno(9000);

        Segmentacion r = segmentador.segmentar(obra);

        // Una seccion vacia produciria subtemas inventados por el modelo.
        for (var s : r.secciones()) {
            assertTrue(s.texto().length() >= 400,
                    "ninguna seccion debe quedar por debajo del minimo: " + s.texto().length());
        }
    }

    @Test
    @DisplayName("Sin ninguna estructura, cae a ventanas fijas y lo declara")
    void sinEstructuraCaeAVentanas() {
        String corrido = relleno(60_000);

        Segmentacion r = segmentador.segmentar(corrido);

        assertEquals(Metodo.VENTANAS, r.metodo());
        assertTrue(r.degradada(), "las ventanas fijas son un modo degradado y debe constar");
        assertTrue(r.secciones().size() >= 3,
                "60k caracteres deben dar varias ventanas, hubo " + r.secciones().size());
    }

    @Test
    @DisplayName("Las ventanas cubren el documento entero sin perder texto")
    void lasVentanasNoPierdenTexto() {
        String corrido = relleno(50_000);

        Segmentacion r = segmentador.segmentar(corrido);

        int total = r.secciones().stream().mapToInt(s -> s.texto().length()).sum();
        // Se permite holgura por los strip() de cada corte, pero no puede faltar un trozo.
        assertTrue(total > corrido.length() * 0.95,
                "se perdio texto: " + total + " de " + corrido.length());
    }

    @Test
    @DisplayName("No trocea de mas: cientos de falsos titulos no cuentan como estructura")
    void noAceptaDemasiadosFalsosTitulos() {
        // Muchas lineas cortas en mayuscula que NO son titulos, sino dialogo o versos.
        StringBuilder versos = new StringBuilder();
        for (int i = 0; i < 900; i++) {
            versos.append("Verso corto numero ").append(i).append("\n");
        }

        Segmentacion r = segmentador.segmentar(versos.toString());

        assertNotEquals(Metodo.TIPOGRAFICO, r.metodo(),
                "con 900 falsos titulos la heuristica no es fiable y debe descartarse");
    }

    @Test
    @DisplayName("Las secciones se numeran de forma consecutiva desde cero")
    void numeracionConsecutiva() {
        String obra = "CAPITULO I\n\n" + relleno(9000)
                + "\n\nCAPITULO II\n\n" + relleno(9000)
                + "\n\nCAPITULO III\n\n" + relleno(9000);

        Segmentacion r = segmentador.segmentar(obra);

        for (int i = 0; i < r.secciones().size(); i++) {
            assertEquals(i, r.secciones().get(i).orden());
        }
    }

    @Test
    @DisplayName("Texto nulo o vacio no lanza excepcion")
    void toleraVacio() {
        assertTrue(segmentador.segmentar(null).secciones().isEmpty());
        assertTrue(segmentador.segmentar("   ").secciones().isEmpty());
    }
}
