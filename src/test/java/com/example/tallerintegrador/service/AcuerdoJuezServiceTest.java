package com.example.tallerintegrador.service;

import com.example.tallerintegrador.service.metricas.AcuerdoJuezService;
import com.example.tallerintegrador.service.metricas.AcuerdoJuezService.ParCalificacion;
import com.example.tallerintegrador.service.metricas.AcuerdoJuezService.ResultadoAcuerdo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Esta cifra va a aparecer en el informe de tesis como evidencia de que el juez de IA
 * califica como un docente. Si la fórmula está mal, el resultado reportado es falso y nadie
 * lo notaría mirando la aplicación. Por eso se prueba contra un caso calculado a mano.
 */
class AcuerdoJuezServiceTest {

    private AcuerdoJuezService servicio;

    @BeforeEach
    void setUp() {
        servicio = new AcuerdoJuezService();
    }

    private ParCalificacion par(int ia, int docente) {
        return new ParCalificacion(ia, docente);
    }

    @Test
    @DisplayName("Caso calculado a mano: kappa cuadrática = 0,800 en escala 1-4")
    void kappaCuadraticaCasoConocido() {
        // Cuatro respuestas: dos coincidencias exactas y dos desacuerdos de un punto.
        //
        // Verificación manual (k=4, peso = (i-j)^2 / (k-1)^2 = (i-j)^2 / 9):
        //   numerador   = 0 + 0 + 1/9 + 1/9 = 2/9      = 0,2222
        //   esperada    = 1*1/4 = 0,25 en las 16 celdas
        //   suma pesos  = 40/9 = 4,4444  →  denominador = 4,4444 * 0,25 = 1,1111
        //   kappa       = 1 - 0,2222/1,1111 = 0,800
        List<ParCalificacion> pares = List.of(
                par(1, 1),
                par(2, 2),
                par(3, 4),
                par(4, 3));

        ResultadoAcuerdo r = servicio.calcular(pares, 1, 4);

        assertEquals(0.800, r.kappaCuadratica(), 0.001);
        assertEquals(4, r.n());
    }

    @Test
    @DisplayName("La ponderación importa: el mismo caso da 0,333 sin ponderar")
    void kappaSinPonderarEsMasSeveraConDesacuerdosPequenos() {
        // po = 2/4 = 0,5 · pe = 4/16 = 0,25 · (0,5-0,25)/(1-0,25) = 0,3333
        // La kappa sin ponderar castiga igual confundir un 3 con un 4 que un 1 con un 4.
        // Es exactamente la razón por la que se reporta la ponderada como cifra principal.
        List<ParCalificacion> pares = List.of(
                par(1, 1),
                par(2, 2),
                par(3, 4),
                par(4, 3));

        ResultadoAcuerdo r = servicio.calcular(pares, 1, 4);

        assertEquals(0.333, r.kappaSinPonderar(), 0.001);
        assertTrue(r.kappaCuadratica() > r.kappaSinPonderar(),
                "con desacuerdos de un solo punto, la ponderada debe ser más alta");
    }

    @Test
    @DisplayName("Acuerdo perfecto da kappa 1 y sin sesgo")
    void acuerdoPerfecto() {
        List<ParCalificacion> pares = List.of(
                par(1, 1), par(2, 2), par(3, 3), par(4, 4), par(2, 2));

        ResultadoAcuerdo r = servicio.calcular(pares, 1, 4);

        assertEquals(1.0, r.kappaCuadratica(), 1e-9);
        assertEquals(1.0, r.kappaSinPonderar(), 1e-9);
        assertEquals(1.0, r.acuerdoExacto(), 1e-9);
        assertEquals(0.0, r.sesgoMedio(), 1e-9);
    }

    @Test
    @DisplayName("Detecta que la IA califica sistemáticamente por encima del docente")
    void detectaSesgoDeLaIa() {
        // La IA pone siempre un punto más. El acuerdo exacto es cero, pero el adyacente es
        // total: es un sesgo corregible por calibración, no ruido aleatorio, y la
        // interpretación debe decirlo.
        List<ParCalificacion> pares = List.of(
                par(2, 1), par(3, 2), par(4, 3), par(2, 1), par(3, 2));

        ResultadoAcuerdo r = servicio.calcular(pares, 1, 4);

        assertEquals(0.0, r.acuerdoExacto(), 1e-9);
        assertEquals(1.0, r.acuerdoAdyacente(), 1e-9);
        assertEquals(1.0, r.sesgoMedio(), 1e-9);
        assertTrue(r.interpretacion().contains("por encima"),
                "la interpretación debe señalar la dirección del sesgo: " + r.interpretacion());
    }

    @Test
    @DisplayName("Detecta el sesgo contrario")
    void detectaSesgoALaBaja() {
        List<ParCalificacion> pares = List.of(par(1, 3), par(2, 4), par(1, 3));

        ResultadoAcuerdo r = servicio.calcular(pares, 1, 4);

        assertEquals(-2.0, r.sesgoMedio(), 1e-9);
        assertTrue(r.interpretacion().contains("por debajo"), r.interpretacion());
    }

    @Test
    @DisplayName("Si ambos usan siempre la misma categoría, no hay variación que el azar explique")
    void sinVariacionNoHayDesacuerdo() {
        // Caso degenerado: kappa es matemáticamente indefinida (denominador 0). Se devuelve 1
        // porque coinciden en todo, en vez de propagar un NaN al informe.
        List<ParCalificacion> pares = List.of(par(4, 4), par(4, 4), par(4, 4));

        ResultadoAcuerdo r = servicio.calcular(pares, 1, 4);

        assertEquals(1.0, r.kappaCuadratica(), 1e-9);
        assertFalse(Double.isNaN(r.kappaCuadratica()), "no debe devolverse NaN al informe");
    }

    @Test
    @DisplayName("Desacuerdo total en los extremos da kappa negativa o nula")
    void desacuerdoTotal() {
        List<ParCalificacion> pares = List.of(par(1, 4), par(4, 1), par(1, 4), par(4, 1));

        ResultadoAcuerdo r = servicio.calcular(pares, 1, 4);

        assertTrue(r.kappaCuadratica() <= 0.0,
                "un desacuerdo sistemático no puede dar acuerdo positivo, fue " + r.kappaCuadratica());
    }

    @Test
    @DisplayName("Sin datos no se inventa un número")
    void sinDatos() {
        ResultadoAcuerdo r = servicio.calcular(List.of(), 1, 4);

        assertEquals(0, r.n());
        assertTrue(r.interpretacion().toLowerCase().contains("sin datos"), r.interpretacion());
    }

    @Test
    @DisplayName("Avisa cuando la muestra es demasiado pequeña para reportarse")
    void avisaMuestraInsuficiente() {
        List<ParCalificacion> pares = List.of(par(3, 3), par(2, 2));

        ResultadoAcuerdo r = servicio.calcular(pares, 1, 4);

        assertTrue(r.interpretacion().contains("ATENCIÓN"),
                "con n<50 debe avisar de la inestabilidad: " + r.interpretacion());
    }

    @Test
    @DisplayName("Con 50 o más respuestas ya no avisa")
    void noAvisaConMuestraSuficiente() {
        List<ParCalificacion> pares = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            pares.add(par(3, 3));
        }

        ResultadoAcuerdo r = servicio.calcular(pares, 1, 4);

        assertFalse(r.interpretacion().contains("ATENCIÓN"), r.interpretacion());
    }

    @Test
    @DisplayName("Una calificación fuera de escala se acota en vez de tumbar el cálculo")
    void calificacionFueraDeEscala() {
        List<ParCalificacion> pares = List.of(par(9, 4), par(4, 4), par(-3, 1));

        ResultadoAcuerdo r = servicio.calcular(pares, 1, 4);

        assertEquals(3, r.n());
        assertFalse(Double.isNaN(r.kappaCuadratica()));
    }

    @Test
    @DisplayName("Funciona igual sobre una escala binaria correcto/incorrecto")
    void escalaBinaria() {
        // Las prácticas normales guardan un booleano, no una escala 1-4. Con k=2 la kappa
        // ponderada coincide con la simple, y debe seguir dando un número válido.
        List<ParCalificacion> pares = List.of(
                par(1, 1), par(1, 1), par(0, 0), par(0, 1), par(1, 0), par(0, 0));

        ResultadoAcuerdo r = servicio.calcular(pares, 0, 1);

        assertEquals(r.kappaSinPonderar(), r.kappaCuadratica(), 1e-9);
        assertEquals(6, r.n());
    }

    @Test
    @DisplayName("Rechaza una escala invertida en vez de devolver basura")
    void escalaInvalida() {
        assertThrows(IllegalArgumentException.class,
                () -> servicio.calcular(List.of(par(1, 1)), 4, 1));
    }
}
