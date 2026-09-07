package com.example.tallerintegrador.service.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Los topes de extraccion de subtemas eran dos constantes fijas de 12. Para un PDF de clase
 * estaba bien; para una obra de cuarenta capitulos era absurdo, porque doce temas no
 * describen un libro y el docente veia una lista que dejaba fuera la mayor parte de la obra.
 *
 * Estas pruebas fijan que los topes escalen con el documento sin dispararse.
 */
class TopesProporcionalesTest {

    @Test
    @DisplayName("Un PDF de clase se consulta entero")
    void documentoPequenoSeConsultaEntero() {
        assertEquals(1, RagIngestionService.seccionesAConsultar(1));
        assertEquals(5, RagIngestionService.seccionesAConsultar(5));
        assertEquals(8, RagIngestionService.seccionesAConsultar(8));
    }

    @Test
    @DisplayName("Un libro mediano consulta aproximadamente la mitad de sus secciones")
    void libroMedianoConsultaLaMitad() {
        assertEquals(20, RagIngestionService.seccionesAConsultar(40));
        assertEquals(15, RagIngestionService.seccionesAConsultar(30));
    }

    @Test
    @DisplayName("Una obra enorme se corta en el techo: el coste no se dispara")
    void obraEnormeRespetaElTecho() {
        // 358 secciones es lo que da una Biblia segmentada por ventanas.
        assertEquals(RagIngestionService.MAX_SECCIONES_CONSULTADAS,
                RagIngestionService.seccionesAConsultar(358));
        assertEquals(RagIngestionService.MAX_SECCIONES_CONSULTADAS,
                RagIngestionService.seccionesAConsultar(5000));
    }

    @Test
    @DisplayName("Nunca se consulta menos del suelo en documentos de tamano medio")
    void respetaElSuelo() {
        for (int n = 9; n <= 20; n++) {
            assertTrue(RagIngestionService.seccionesAConsultar(n) >= RagIngestionService.MIN_SECCIONES_CONSULTADAS,
                    "con " + n + " secciones se consultaron " + RagIngestionService.seccionesAConsultar(n));
        }
    }

    @Test
    @DisplayName("Nunca se consultan mas secciones de las que hay")
    void nuncaMasDeLasQueExisten() {
        for (int n = 1; n <= 100; n++) {
            assertTrue(RagIngestionService.seccionesAConsultar(n) <= n,
                    "con " + n + " secciones se pidieron " + RagIngestionService.seccionesAConsultar(n));
        }
    }

    @Test
    @DisplayName("Los subtemas conservados escalan con el documento")
    void subtemasEscalan() {
        // Un PDF de una seccion mantiene el minimo util.
        assertEquals(RagIngestionService.MIN_SUBTEMAS, RagIngestionService.subtemasAConservar(1));
        // Un libro de 40 capitulos ya no se queda en 12.
        assertEquals(60, RagIngestionService.subtemasAConservar(40));
        assertTrue(RagIngestionService.subtemasAConservar(40) > RagIngestionService.MIN_SUBTEMAS,
                "una obra debe dar mas temas que una ficha de clase");
    }

    @Test
    @DisplayName("Los subtemas tambien tienen techo")
    void subtemasRespetanElTecho() {
        assertEquals(RagIngestionService.MAX_SUBTEMAS, RagIngestionService.subtemasAConservar(358));
        assertEquals(RagIngestionService.MAX_SUBTEMAS, RagIngestionService.subtemasAConservar(10_000));
    }

    @Test
    @DisplayName("La funcion es monotona: mas secciones nunca dan menos consultas")
    void esMonotona() {
        int previo = 0;
        for (int n = 1; n <= 500; n++) {
            int actual = RagIngestionService.seccionesAConsultar(n);
            assertTrue(actual >= previo,
                    "con " + n + " secciones bajo de " + previo + " a " + actual);
            previo = actual;
        }
    }
}
