package com.example.tallerintegrador.service.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Si esta función agrupa de más, dos conceptos distintos se fusionan y la probabilidad de
 * dominio del alumno queda corrompida sin que nada lo señale. Si agrupa de menos, el BKT
 * sigue fragmentado. Ambos fallos son silenciosos, así que se prueban los dos lados.
 */
class NormalizadorConceptoTest {

    @ParameterizedTest(name = "\"{0}\" agrupa con \"fotosintesis\"")
    @ValueSource(strings = {
            "Fotosíntesis",
            "fotosíntesis",
            "LA FOTOSÍNTESIS",
            "La fotosíntesis",
            "  fotosintesis  ",
            "fotosíntesis.",
            "\"Fotosíntesis\"",
            "El  fotosíntesis",
    })
    @DisplayName("Las variantes del mismo concepto colapsan en una sola clave")
    void variantesDelMismoConcepto(String variante) {
        assertEquals("fotosintesis", NormalizadorConcepto.canonizar(variante));
    }

    @ParameterizedTest
    @CsvSource({
            "Los ecosistemas,           ecosistemas",
            "Las células vegetales,     celulas vegetales",
            "de la Revolución Francesa, revolucion francesa",
            "Un triángulo isósceles,    triangulo isosceles",
            "EL Sistema Solar,          sistema solar",
    })
    @DisplayName("Quita el artículo inicial y los diacríticos")
    void quitaArticuloYTildes(String entrada, String esperado) {
        assertEquals(esperado, NormalizadorConcepto.canonizar(entrada));
    }

    @Test
    @DisplayName("NO fusiona conceptos que solo se parecen")
    void noFusionaConceptosDistintos() {
        // El riesgo real de agrupar de más. Estos deben seguir siendo claves distintas.
        assertNotEquals(
                NormalizadorConcepto.canonizar("Fotosíntesis"),
                NormalizadorConcepto.canonizar("Respiración celular"));
        assertNotEquals(
                NormalizadorConcepto.canonizar("Células vegetales"),
                NormalizadorConcepto.canonizar("Células animales"));
        assertNotEquals(
                NormalizadorConcepto.canonizar("Ecosistema"),
                NormalizadorConcepto.canonizar("Ecosistemas acuáticos"));
    }

    @Test
    @DisplayName("Quita un solo prefijo, el más largo que encaje, y no encadena")
    void noEncadenaPrefijos() {
        assertEquals("palabras", NormalizadorConcepto.canonizar("Las palabras"));

        // "de la " se comprueba antes que "de ", así que se consume entero de una vez.
        assertEquals("casa", NormalizadorConcepto.canonizar("De la casa"));

        // Y aquí está lo que de verdad protege el barrido único: tras quitar "la ", NO se
        // vuelve a pasar. Si se encadenara, "las palabras" acabaría en "palabras" y el
        // concepto perdería parte de su nombre.
        assertEquals("las palabras", NormalizadorConcepto.canonizar("La las palabras"));
    }

    @Test
    @DisplayName("No deja el concepto vacío si el nombre ES el artículo")
    void noVaciaConceptosCortos() {
        // El guardarraíl `length > prefijo.length()` evita convertir "El" en cadena vacía y
        // perder la fila entera.
        assertEquals("el", NormalizadorConcepto.canonizar("El"));
        assertEquals("la", NormalizadorConcepto.canonizar("la"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", ".", "---"})
    @DisplayName("Entradas sin contenido dan cadena vacía, no excepción")
    void entradasVacias(String entrada) {
        assertEquals("", NormalizadorConcepto.canonizar(entrada));
    }

    @Test
    @DisplayName("Un null no rompe el guardado del intento")
    void toleraNull() {
        assertEquals("", NormalizadorConcepto.canonizar(null));
        assertEquals("", NormalizadorConcepto.paraMostrar(null));
    }

    @Test
    @DisplayName("Es idempotente: canonizar lo ya canónico no lo cambia")
    void idempotente() {
        String una = NormalizadorConcepto.canonizar("La Fotosíntesis");
        assertEquals(una, NormalizadorConcepto.canonizar(una));
    }

    @ParameterizedTest
    @CsvSource({
            "la fotosíntesis,       Fotosíntesis",
            "LAS CÉLULAS,           CÉLULAS",
            "  ecosistemas  ,       Ecosistemas",
            "revolución francesa,   Revolución francesa",
    })
    @DisplayName("La forma legible conserva las tildes para mostrarla al alumno")
    void formaLegible(String entrada, String esperado) {
        assertEquals(esperado, NormalizadorConcepto.paraMostrar(entrada));
    }

    @Test
    @DisplayName("La clave agrupa aunque la forma legible difiera")
    void claveYEtiquetaSonIndependientes() {
        // Es el punto del diseño: se agrupa por la canónica, se muestra la legible.
        assertEquals(
                NormalizadorConcepto.canonizar("La fotosíntesis"),
                NormalizadorConcepto.canonizar("FOTOSINTESIS"));
        assertNotEquals(
                NormalizadorConcepto.paraMostrar("La fotosíntesis"),
                NormalizadorConcepto.paraMostrar("FOTOSINTESIS"));
    }
}
