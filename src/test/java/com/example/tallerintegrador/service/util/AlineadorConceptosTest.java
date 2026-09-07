package com.example.tallerintegrador.service.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El agrupamiento tiene dos formas de fallar y las dos rompen la medicion:
 *
 *   - Si agrupa de menos, "Abuso" y "Abuso de poder" siguen siendo conceptos distintos, cada
 *     uno con una observacion, y el BKT nunca llega al umbral de dominio.
 *   - Si agrupa de mas, temas que no tienen nada que ver caen en el mismo saco y el mapa de
 *     calor pierde el detalle que lo hace util.
 *
 * El vocabulario de estas pruebas es el real de Paco Yunque, para que los casos sean los que
 * de verdad se dieron.
 */
class AlineadorConceptosTest {

    private static final List<String> VOCABULARIO = List.of(
            "Abuso de poder",
            "Autoridad escolar",
            "Desigualdad social",
            "Complicidad del profesor",
            "Adaptacion escolar"
    );

    @Test
    @DisplayName("Un concepto suelto se absorbe en el subtema curado que lo contiene")
    void absorbeElConceptoSuelto() {
        var r = AlineadorConceptos.alinear("Abuso", VOCABULARIO);

        assertEquals("Abuso de poder", r.conceptos());
        assertEquals(1, r.alineados());
    }

    @Test
    @DisplayName("Varias formas del mismo concepto acaban en UNA sola etiqueta")
    void variasFormasSeFundenEnUna() {
        // Este es el caso que dejaba a un alumno con 128 puntos y cero temas dominados: tres
        // etiquetas distintas para lo mismo, con una observacion cada una.
        var r = AlineadorConceptos.alinear("Abuso, Mecanismos de abuso, Abuso de poder", VOCABULARIO);

        assertEquals("Abuso de poder", r.conceptos(), "las tres deben colapsar en una");
        assertEquals(3, r.alineados());
    }

    @Test
    @DisplayName("NO agrupa temas distintos que comparten una palabra corriente")
    void noAgrupaTemasDistintos() {
        // "escolar" aparece en media obra. Si bastara compartir una palabra, "Justicia
        // escolar" caeria en "Autoridad escolar" y el mapa perderia el detalle.
        var r = AlineadorConceptos.alinear("Justicia escolar", VOCABULARIO);

        assertEquals("Justicia escolar", r.conceptos());
        assertEquals(0, r.alineados());
        assertEquals(1, r.sinEncaje());
    }

    @Test
    @DisplayName("Un concepto sin encaje se CONSERVA, no se tira")
    void conservaLoQueNoEncaja() {
        // Tirarlo dejaria la pregunta sin etiqueta y el BKT no aprenderia nada de ella: por
        // arreglar la fragmentacion se perderia la medicion.
        var r = AlineadorConceptos.alinear("Ironia narrativa", VOCABULARIO);

        assertEquals("Ironia narrativa", r.conceptos());
        assertEquals(1, r.sinEncaje());
    }

    @Test
    @DisplayName("Sin vocabulario curado no se toca nada")
    void sinVocabularioNoTocaNada() {
        // Materiales antiguos sin subtemas revisados deben seguir comportandose igual que antes.
        assertEquals("Abuso, Miedo", AlineadorConceptos.alinear("Abuso, Miedo", List.of()).conceptos());
        assertEquals("Abuso, Miedo", AlineadorConceptos.alinear("Abuso, Miedo", null).conceptos());
    }

    @Test
    @DisplayName("Mezcla de encajes y no encajes: conserva ambos")
    void mezclaDeEncajes() {
        var r = AlineadorConceptos.alinear("Abuso, Ironia narrativa", VOCABULARIO);

        assertTrue(r.conceptos().contains("Abuso de poder"));
        assertTrue(r.conceptos().contains("Ironia narrativa"));
        assertEquals(1, r.alineados());
        assertEquals(1, r.sinEncaje());
    }

    @Test
    @DisplayName("Las tildes y las mayusculas no impiden el encaje")
    void tildesYMayusculas() {
        assertEquals("Desigualdad social",
                AlineadorConceptos.alinear("DESIGUALDAD SOCIAL", VOCABULARIO).conceptos());
        assertEquals("Adaptacion escolar",
                AlineadorConceptos.alinear("Adaptación escolar", VOCABULARIO).conceptos());
    }

    @Test
    @DisplayName("Texto nulo o vacio no revienta")
    void toleraNuloYVacio() {
        assertNull(AlineadorConceptos.alinear(null, VOCABULARIO).conceptos());
        assertEquals("   ", AlineadorConceptos.alinear("   ", VOCABULARIO).conceptos());
    }
}
