package com.example.tallerintegrador.service;

import com.example.tallerintegrador.service.analitica.UbicacionPorBloomService;
import com.example.tallerintegrador.entidades.postgres.NivelConocimiento;
import com.example.tallerintegrador.service.analitica.UbicacionPorBloomService.RespuestaUbicacion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La ubicación por Bloom sustituye al ACRA como criterio de nivel. A diferencia del
 * criterio anterior (sumar respuestas Likert sobre hábitos de estudio), este se puede
 * probar de forma exhaustiva: es una función pura sobre aciertos reales.
 */
class UbicacionPorBloomServiceTest {

    private UbicacionPorBloomService servicio;

    @BeforeEach
    void setUp() {
        servicio = new UbicacionPorBloomService();
    }

    private RespuestaUbicacion r(String nivel, boolean acierto) {
        return new RespuestaUbicacion(nivel, acierto);
    }

    @Test
    @DisplayName("Falla todo: se ubica en PRINCIPIANTE")
    void fallaTodoEsPrincipiante() {
        var resultado = servicio.determinarNivel(List.of(
                r("Comprender", false), r("Comprender", false),
                r("Analizar", false), r("Analizar", false),
                r("Evaluar", false), r("Evaluar", false)
        ));
        assertEquals(NivelConocimiento.PRINCIPIANTE, resultado.nivel());
    }

    @Test
    @DisplayName("Solo alcanza Comprender: PRINCIPIANTE")
    void soloComprenderEsPrincipiante() {
        var resultado = servicio.determinarNivel(List.of(
                r("Comprender", true), r("Comprender", true),
                r("Analizar", false), r("Analizar", false),
                r("Evaluar", false), r("Evaluar", false)
        ));
        assertEquals(NivelConocimiento.PRINCIPIANTE, resultado.nivel());
    }

    @Test
    @DisplayName("Alcanza Analizar: INTERMEDIO")
    void alcanzaAnalizarEsIntermedio() {
        var resultado = servicio.determinarNivel(List.of(
                r("Comprender", true), r("Comprender", true),
                r("Analizar", true), r("Analizar", false),
                r("Evaluar", false), r("Evaluar", false)
        ));
        assertEquals(NivelConocimiento.INTERMEDIO, resultado.nivel());
    }

    @Test
    @DisplayName("Alcanza Evaluar: AVANZADO")
    void alcanzaEvaluarEsAvanzado() {
        var resultado = servicio.determinarNivel(List.of(
                r("Comprender", true), r("Comprender", true),
                r("Analizar", true), r("Analizar", true),
                r("Evaluar", true), r("Evaluar", false)
        ));
        assertEquals(NivelConocimiento.AVANZADO, resultado.nivel());
    }

    @Test
    @DisplayName("Escalograma: alcanzar un estrato alto no exige perfección en los bajos")
    void escalogramaNoExigePerfeccionEnEstratosBajos() {
        // Falla en Comprender por descuido, pero demuestra dominio en Evaluar.
        // La ubicación se rige por el estrato más alto alcanzado (Guttman), no por el promedio.
        var resultado = servicio.determinarNivel(List.of(
                r("Comprender", false), r("Comprender", false),
                r("Analizar", true), r("Analizar", true),
                r("Evaluar", true), r("Evaluar", true)
        ));
        assertEquals(NivelConocimiento.AVANZADO, resultado.nivel());
    }

    @Test
    @DisplayName("Exactamente en el umbral (50%) cuenta como alcanzado")
    void umbralExactoCuentaComoAlcanzado() {
        var resultado = servicio.determinarNivel(List.of(
                r("Analizar", true), r("Analizar", false)
        ));
        assertEquals(NivelConocimiento.INTERMEDIO, resultado.nivel());
    }

    @Test
    @DisplayName("Sin respuestas: nivel inicial y justificación explícita, no un error")
    void sinRespuestasDevuelveNivelInicial() {
        var resultado = servicio.determinarNivel(List.of());
        assertEquals(NivelConocimiento.PRINCIPIANTE, resultado.nivel());
        assertTrue(resultado.justificacion().toLowerCase().contains("sin respuestas"));

        var nulo = servicio.determinarNivel(null);
        assertEquals(NivelConocimiento.PRINCIPIANTE, nulo.nivel());
    }

    @Test
    @DisplayName("El resultado explica en qué se basó la ubicación")
    void resultadoIncluyeJustificacionYDesempeno() {
        var resultado = servicio.determinarNivel(List.of(
                r("Comprender", true), r("Comprender", true),
                r("Analizar", true), r("Analizar", true)
        ));
        assertEquals(1.0, resultado.desempenoPorEstrato().get("Comprender"));
        assertEquals(1.0, resultado.desempenoPorEstrato().get("Analizar"));
        assertTrue(resultado.justificacion().contains("Analizar"));
    }

    @Test
    @DisplayName("La composición de la prueba cubre los tres estratos")
    void composicionCubreLosTresEstratos() {
        var composicion = servicio.composicionDeLaPrueba();
        assertEquals(3, composicion.size());
        assertTrue(composicion.containsKey("Comprender"));
        assertTrue(composicion.containsKey("Analizar"));
        assertTrue(composicion.containsKey("Evaluar"));
        composicion.values().forEach(cantidad ->
                assertEquals(UbicacionPorBloomService.REACTIVOS_POR_NIVEL, cantidad));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Patrones que violan el supuesto acumulativo del escalograma
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Un acierto AISLADO en el estrato medio no promueve")
    void aciertoAisladoNoPromueve() {
        // Caso real observado: Comprender 0% · Analizar 100% (1 de 2) · Evaluar 0%.
        // Antes ubicaba en INTERMEDIO a alguien que había fallado todo lo básico y todo lo
        // difícil. Con dos reactivos, acertar uno al azar en opción múltiple pasa el 44% de
        // las veces: no es evidencia de dominio.
        var resultado = servicio.determinarNivel(List.of(
                r("Comprender", false), r("Comprender", false),
                r("Analizar", true), r("Analizar", false),
                r("Evaluar", false), r("Evaluar", false)
        ));
        assertEquals(NivelConocimiento.PRINCIPIANTE, resultado.nivel());
        assertTrue(resultado.patronInconsistente(),
                "el patrón viola el supuesto acumulativo y debe quedar señalado");
    }

    @Test
    @DisplayName("Acertar solo el estrato MÁS ALTO, fallando los dos inferiores, no promueve")
    void soloEvaluarAisladoNoPromueve() {
        var resultado = servicio.determinarNivel(List.of(
                r("Comprender", false), r("Comprender", false),
                r("Analizar", false), r("Analizar", false),
                r("Evaluar", true), r("Evaluar", false)
        ));
        assertEquals(NivelConocimiento.PRINCIPIANTE, resultado.nivel());
        assertTrue(resultado.patronInconsistente());
    }

    @Test
    @DisplayName("Dominio respaldado por el estrato superior SÍ promueve (Guttman no exige perfección abajo)")
    void aciertoRespaldadoPorArribaSiPromueve() {
        // Falla Comprender por descuido pero acierta Analizar Y Evaluar: cuatro aciertos
        // seguidos en lo difícil no se explican por azar (~0.4%). Se conserva AVANZADO.
        var resultado = servicio.determinarNivel(List.of(
                r("Comprender", false), r("Comprender", false),
                r("Analizar", true), r("Analizar", true),
                r("Evaluar", true), r("Evaluar", true)
        ));
        assertEquals(NivelConocimiento.AVANZADO, resultado.nivel());
        assertTrue(resultado.patronInconsistente(),
                "sigue siendo un patrón no acumulativo, aunque la ubicación se mantenga");
    }

    @Test
    @DisplayName("Un patrón acumulativo normal no se marca como inconsistente")
    void patronAcumulativoNoSeMarca() {
        var resultado = servicio.determinarNivel(List.of(
                r("Comprender", true), r("Comprender", true),
                r("Analizar", true), r("Analizar", false),
                r("Evaluar", false), r("Evaluar", false)
        ));
        assertEquals(NivelConocimiento.INTERMEDIO, resultado.nivel());
        assertFalse(resultado.patronInconsistente());
    }

    @Test
    @DisplayName("Prueba parcial: sin estratos vecinos medidos, el umbral basta")
    void pruebaParcialNoSeCastiga() {
        // Solo se preguntó Analizar. Exigir respaldo de un estrato que nadie preguntó
        // castigaría al alumno por una pregunta que no se le hizo.
        var resultado = servicio.determinarNivel(List.of(
                r("Analizar", true), r("Analizar", false)
        ));
        assertEquals(NivelConocimiento.INTERMEDIO, resultado.nivel());
        assertFalse(resultado.patronInconsistente());
    }
}
