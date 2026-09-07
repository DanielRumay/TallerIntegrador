package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.service.analitica.ProgresoAlumnoService.Rango;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Los rangos son de PROGRESO, no de competencia cognitiva. Estas pruebas fijan la escala y,
 * sobre todo, la separacion entre ambas cosas: si algun dia alguien renombra un rango como
 * "Avanzado", el alumno creera que subir de rango cambia la dificultad de sus preguntas, y
 * no es asi. La ultima prueba de esta clase existe para impedirlo.
 */
class ProgresoAlumnoServiceTest {

    @Test
    @DisplayName("Un alumno que empieza esta en el primer rango")
    void empiezaEnElPrimero() {
        assertEquals("Explorador", ProgresoAlumnoService.rangoDe(0).nombre());
        assertEquals("Explorador", ProgresoAlumnoService.rangoDe(149).nombre());
    }

    @Test
    @DisplayName("Se sube de rango justo al alcanzar el umbral, no despues")
    void subeAlAlcanzarElUmbral() {
        assertEquals("Constante", ProgresoAlumnoService.rangoDe(150).nombre());
        assertEquals("Analista", ProgresoAlumnoService.rangoDe(400).nombre());
        assertEquals("Estratega", ProgresoAlumnoService.rangoDe(800).nombre());
        assertEquals("Maestro", ProgresoAlumnoService.rangoDe(1500).nombre());
    }

    @Test
    @DisplayName("Por encima del ultimo rango no se rompe ni se queda sin nombre")
    void masAllaDelUltimoRango() {
        assertEquals("Maestro", ProgresoAlumnoService.rangoDe(99_999).nombre());
        assertNull(ProgresoAlumnoService.siguienteRango(99_999),
                "en el rango mas alto no hay siguiente");
    }

    @Test
    @DisplayName("El siguiente rango es siempre el inmediato superior")
    void siguienteRangoCorrecto() {
        assertEquals("Constante", ProgresoAlumnoService.siguienteRango(0).nombre());
        assertEquals("Analista", ProgresoAlumnoService.siguienteRango(150).nombre());
        assertEquals("Analista", ProgresoAlumnoService.siguienteRango(399).nombre());
    }

    @Test
    @DisplayName("La barra de progreso va de 0 a 1 dentro del rango")
    void barraDeProgreso() {
        Rango explorador = ProgresoAlumnoService.rangoDe(0);
        Rango constante = ProgresoAlumnoService.siguienteRango(0);

        assertEquals(0.0, ProgresoAlumnoService.progresoEnRango(0, explorador, constante), 1e-9);
        assertEquals(0.5, ProgresoAlumnoService.progresoEnRango(75, explorador, constante), 1e-9);
        assertEquals(1.0, ProgresoAlumnoService.progresoEnRango(150, explorador, constante), 1e-9);
    }

    @Test
    @DisplayName("En el rango maximo la barra esta llena, no vacia ni rota")
    void barraEnRangoMaximo() {
        Rango maestro = ProgresoAlumnoService.rangoDe(2000);
        assertEquals(1.0, ProgresoAlumnoService.progresoEnRango(2000, maestro, null), 1e-9);
    }

    @Test
    @DisplayName("La barra nunca se sale del rango 0-1")
    void barraAcotada() {
        Rango explorador = ProgresoAlumnoService.rangoDe(0);
        Rango constante = ProgresoAlumnoService.siguienteRango(0);

        for (int p = -50; p <= 300; p += 7) {
            double v = ProgresoAlumnoService.progresoEnRango(p, explorador, constante);
            assertTrue(v >= 0.0 && v <= 1.0, "con " + p + " puntos la barra dio " + v);
        }
    }

    @Test
    @DisplayName("Dominar conceptos vale mas que acumular intentos")
    void dominarValeMasQueInsistir() {
        // Es la regla que impide que la gamificacion premie la cantidad sobre la calidad.
        assertTrue(ProgresoAlumnoService.PUNTOS_POR_CONCEPTO_DOMINADO
                        > ProgresoAlumnoService.PUNTOS_POR_EVALUACION * 2,
                "un concepto dominado debe valer mas que un par de evaluaciones cualesquiera");
    }

    @Test
    @DisplayName("Resolver solo vale mas que resolver con pista, pero con pista no vale cero")
    void escalonarLaRecompensa() {
        assertTrue(ProgresoAlumnoService.PUNTOS_RESOLVER_SOLO
                > ProgresoAlumnoService.PUNTOS_RESOLVER_CON_PISTA);
        assertTrue(ProgresoAlumnoService.PUNTOS_RESOLVER_CON_PISTA > 0,
                "necesitar una pista no debe valer cero: seguir intentandolo cuenta");
    }

    @Test
    @DisplayName("NINGUN rango se llama como un nivel de dominio")
    void losRangosNoSeConfundenConLosNiveles() {
        // La proteccion mas importante de esta clase. Los puntos miden constancia; el nivel de
        // dominio mide competencia y decide la dificultad. Si un rango se llamara "Avanzado",
        // el alumno creeria que acumulando puntos cambia la dificultad de sus preguntas.
        for (Rango r : ProgresoAlumnoService.RANGOS) {
            String n = r.nombre().toUpperCase();
            assertFalse(n.contains("PRINCIPIANTE") || n.contains("INTERMEDIO") || n.contains("AVANZADO"),
                    "el rango '" + r.nombre() + "' se confunde con un nivel de dominio");
        }
    }
}
