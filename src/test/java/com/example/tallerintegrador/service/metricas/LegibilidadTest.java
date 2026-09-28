package com.example.tallerintegrador.service.metricas;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprueba la fórmula de Flesch-Szigriszt y la escala INFLESZ que la interpreta.
 *
 * Se añadió porque Fernández Huerta, la fórmula que se venía usando, arrastra una
 * inconsistencia lógica conocida y no publicó su validación. Szigriszt-Pazos sí fue validada, y
 * su escala se reajustó con 210 textos (Barrio-Cantalejo et al., 2008).
 */
class LegibilidadTest {

    private final MetricasEstandarizadasService metricas = new MetricasEstandarizadasService();

    @Test
    @DisplayName("Un texto llano puntúa más alto que uno técnico")
    void textoLlanoEsMasLegible() {
        double llano = metricas.calcularPerspicuidadSzigriszt("El sujeto es quien hace la acción.");
        double tecnico = metricas.calcularPerspicuidadSzigriszt(
                "La concordancia morfosintáctica establece correspondencias flexivas interdependientes.");
        assertThat(llano).isGreaterThan(tecnico);
    }

    @Test
    @DisplayName("El índice se mantiene dentro del rango 0 a 100")
    void indiceAcotado() {
        assertThat(metricas.calcularPerspicuidadSzigriszt("Sí.")).isBetween(0.0, 100.0);
        assertThat(metricas.calcularPerspicuidadSzigriszt(
                "Interdisciplinariedad epistemológica contemporánea incuestionablemente paradigmática."))
                .isBetween(0.0, 100.0);
    }

    @Test
    @DisplayName("Un texto vacío o nulo devuelve cero y no revienta")
    void textoVacio() {
        assertThat(metricas.calcularPerspicuidadSzigriszt(null)).isZero();
        assertThat(metricas.calcularPerspicuidadSzigriszt("   ")).isZero();
    }

    @Test
    @DisplayName("Los cinco tramos de la escala INFLESZ")
    void tramosInflesz() {
        assertThat(metricas.nivelInflesz(20)).isEqualTo("muy difícil");
        assertThat(metricas.nivelInflesz(45)).isEqualTo("algo difícil");
        assertThat(metricas.nivelInflesz(60)).isEqualTo("normal");
        assertThat(metricas.nivelInflesz(70)).isEqualTo("bastante fácil");
        assertThat(metricas.nivelInflesz(85)).isEqualTo("muy fácil");
    }

    @Test
    @DisplayName("Szigriszt y Fernández Huerta coinciden en el orden, aunque no en el valor")
    void ambasFormulasOrdenanIgual() {
        String facil = "El perro corre. El gato duerme.";
        String dificil = "La implementación arquitectónica requiere consideraciones "
                + "interdisciplinarias sustancialmente complejas y multifactoriales.";
        assertThat(metricas.calcularPerspicuidadSzigriszt(facil))
                .isGreaterThan(metricas.calcularPerspicuidadSzigriszt(dificil));
        assertThat(metricas.calcularLecturabilidad(facil))
                .isGreaterThan(metricas.calcularLecturabilidad(dificil));
    }
}
