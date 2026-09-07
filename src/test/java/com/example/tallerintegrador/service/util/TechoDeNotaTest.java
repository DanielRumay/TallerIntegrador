package com.example.tallerintegrador.service.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El techo existe porque la nota la escribia el propio modelo y nadie la revisaba: dio 4 de 4
 * a una respuesta sin contenido. Estas pruebas fijan los dos limites y, sobre todo, que el
 * techo NO castigue a quien si razona: una buena respuesta al primer intento debe poder
 * seguir sacando el maximo.
 */
class TechoDeNotaTest {

    /** Respuesta con contenido de sobra, como la de un alumno que si analizo el cuento. */
    private static final String RESPUESTA_BUENA =
            "El profesor no castiga a Humberto porque su padre tiene dinero e influencia en el "
            + "pueblo, entonces la autoridad del colegio termina protegiendo al que abusa.";

    @Test
    @DisplayName("Una buena respuesta al primer intento conserva el 4")
    void primerIntentoConservaElMaximo() {
        assertEquals(4, TechoDeNota.aplicar(4, 1, RESPUESTA_BUENA));
    }

    @Test
    @DisplayName("Si necesito una repregunta, el techo baja a 3")
    void segundoEscalonBajaATres() {
        assertEquals(3, TechoDeNota.aplicar(4, 2, RESPUESTA_BUENA));
    }

    @Test
    @DisplayName("Si necesito todo el andamiaje, el techo baja a 2")
    void tercerEscalonBajaADos() {
        // Llegar a la idea despues de que Aria plantee una hipotetica y casi lo explique es
        // desempeno asistido. Puntuarlo igual que resolverlo solo borra la diferencia que el
        // escalon estaba midiendo.
        assertEquals(2, TechoDeNota.aplicar(4, 3, RESPUESTA_BUENA));
    }

    @Test
    @DisplayName("Sin evidencia no hay nota alta, aunque el modelo diga 4")
    void sinEvidenciaNoHayNotaAlta() {
        assertEquals(1, TechoDeNota.aplicar(4, 1, ""));
        assertEquals(1, TechoDeNota.aplicar(4, 1, "no se"));
        assertEquals(1, TechoDeNota.aplicar(4, 1, "si"));
    }

    @Test
    @DisplayName("Evidencia escasa topa en 2")
    void evidenciaEscasaTopaEnDos() {
        assertEquals(2, TechoDeNota.aplicar(4, 1, "El profesor tenia miedo"));
    }

    @Test
    @DisplayName("El techo nunca SUBE la nota del modelo")
    void nuncaSubeLaNota() {
        // Si el modelo puntua bajo, este codigo no lo corrige hacia arriba: eso seria inventar
        // conocimiento que nadie observo.
        assertEquals(1, TechoDeNota.aplicar(1, 1, RESPUESTA_BUENA));
        assertEquals(2, TechoDeNota.aplicar(2, 1, RESPUESTA_BUENA));
    }

    @Test
    @DisplayName("Una puntuacion fuera de rango se encaja en 1..4")
    void encajaValoresFueraDeRango() {
        assertEquals(4, TechoDeNota.aplicar(9, 1, RESPUESTA_BUENA));
        assertEquals(1, TechoDeNota.aplicar(0, 1, RESPUESTA_BUENA));
        assertEquals(1, TechoDeNota.aplicar(-3, 1, RESPUESTA_BUENA));
    }

    @Test
    @DisplayName("Sin puntuacion del modelo no se inventa ninguna")
    void sinPuntuacionDevuelveNull() {
        assertNull(TechoDeNota.aplicar(null, 1, RESPUESTA_BUENA));
    }

    @Test
    @DisplayName("Un escalon fuera de rango no revienta ni abre el techo")
    void escalonFueraDeRango() {
        assertEquals(4, TechoDeNota.aplicar(4, 0, RESPUESTA_BUENA));
        assertEquals(2, TechoDeNota.aplicar(4, 99, RESPUESTA_BUENA));
    }

    @Test
    @DisplayName("Las palabras vacias no cuentan como evidencia")
    void lasPalabrasVaciasNoCuentan() {
        // "que, por, con, para" son conectores: rellenan la frase sin aportar analisis, y si
        // contaran bastaria escribir palabrería para levantar el techo.
        assertEquals(0, TechoDeNota.palabrasConContenido("y que por con para de la el"));
        assertTrue(TechoDeNota.palabrasConContenido(RESPUESTA_BUENA) >= 8);
    }

    @Test
    @DisplayName("Sin canal de texto (voz) solo limita el andamiaje, no la evidencia")
    void sinCanalDeTextoSoloLimitaElAndamiaje() {
        assertEquals(0, TechoDeNota.palabrasConContenido(null));

        // null NO es "respuesta vacia": es "respondio hablando y no hay transcripcion".
        // Tratarlo como vacio pondria un 1 a quien argumento bien en voz alta.
        assertEquals(4, TechoDeNota.aplicar(4, 1, null));
        assertEquals(2, TechoDeNota.aplicar(4, 3, null));

        // El texto vacio de verdad si topa en 1.
        assertEquals(1, TechoDeNota.aplicar(4, 1, ""));
    }
}
