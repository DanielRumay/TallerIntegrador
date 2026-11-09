package com.example.tallerintegrador.service.ia;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El perfil traduce el nombre del grado, tal como lo escribe cada colegio, a ciclo del CNEB,
 * edad tipica y umbral de legibilidad. Si se equivoca de ciclo, todos los prompts le hablan al
 * alumno con la edad equivocada; por eso, ante un nombre que no entiende, prefiere no adaptar.
 */
class PerfilAudienciaTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}.° ciclo {2}")
    @CsvSource({
            "1er Año de Secundaria, 1, VI, 12, 13, 55",
            "2do Año de Secundaria, 2, VI, 13, 14, 55",
            "5to Año de Secundaria, 5, VII, 16, 17, 50",
            "Tercero de secundaria, 3, VII, 14, 15, 50",
            "CUARTO AÑO,            4, VII, 15, 16, 50",
            "4to primaria,          4, IV, 9, 10, 65",
            "Sexto de Primaria,     6, V, 11, 12, 65",
    })
    @DisplayName("Reconoce los nombres de grado habituales")
    void reconoceNombresDeGrado(String nombre, int grado, String ciclo, int edadMin, int edadMax, double umbral) {
        PerfilAudiencia p = PerfilAudiencia.desdeNombreGrado(nombre);

        assertThat(p.grado()).isEqualTo(grado);
        assertThat(p.ciclo()).isEqualTo(ciclo);
        assertThat(p.edadMinima()).isEqualTo(edadMin);
        assertThat(p.edadMaxima()).isEqualTo(edadMax);
        assertThat(p.umbralInflesz()).isEqualTo(umbral);
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL", "''", "Grado único", "7mo de secundaria"}, nullValues = "NULL")
    @DisplayName("Ante un nombre que no entiende, no adapta: perfil general")
    void sinGradoReconocibleEsGeneral(String nombre) {
        assertThat(PerfilAudiencia.desdeNombreGrado(nombre)).isEqualTo(PerfilAudiencia.GENERAL);
    }

    @Test
    @DisplayName("Con grado conocido manda el umbral del ciclo; sin él, el configurado")
    void umbralEfectivo() {
        assertThat(PerfilAudiencia.desdeNombreGrado("2do Año de Secundaria").umbralInfleszO(40)).isEqualTo(55);
        assertThat(PerfilAudiencia.GENERAL.umbralInfleszO(40)).isEqualTo(40);
    }

    @Test
    @DisplayName("La instrucción nombra grado, edad y ciclo, y aclara que no cambia el nivel de Bloom")
    void instruccionDelCicloVI() {
        String instruccion = PerfilAudiencia.desdeNombreGrado("2do Año de Secundaria").instruccion();

        assertThat(instruccion)
                .contains("2.° de secundaria (13 a 14 años)")
                .contains("ciclo VI")
                .contains("nivel de Bloom");
    }

    @Test
    @DisplayName("Cada grado tiene su largo de oración y los ciclos de primaria se distinguen")
    void diferenciaPorGrado() {
        assertThat(PerfilAudiencia.desdeNombreGrado("1er Año de Secundaria").instruccion()).contains("18 palabras");
        assertThat(PerfilAudiencia.desdeNombreGrado("2do Año de Secundaria").instruccion()).contains("20 palabras");
        assertThat(PerfilAudiencia.desdeNombreGrado("1ro primaria").instruccion()).contains("10 palabras").contains("casa y la escuela");
        assertThat(PerfilAudiencia.desdeNombreGrado("6to primaria").instruccion()).contains("18 palabras").doesNotContain("casa y la escuela");
    }

    @Test
    @DisplayName("Los ciclos VI y VII piden un lenguaje distinto")
    void cicloVIyVIIDifieren() {
        String vi = PerfilAudiencia.desdeNombreGrado("1er Año de Secundaria").instruccion();
        String vii = PerfilAudiencia.desdeNombreGrado("5to Año de Secundaria").instruccion();

        assertThat(vi).contains("Vocabulario cotidiano");
        assertThat(vii).contains("terminología especializada");
    }

    @Test
    @DisplayName("Sin grado, la descripción conserva la de antes: secundaria de 12 a 17 años")
    void generalConservaElComportamientoAnterior() {
        assertThat(PerfilAudiencia.GENERAL.descripcion()).isEqualTo("un estudiante de secundaria (12 a 17 años)");
        assertThat(PerfilAudiencia.GENERAL.instruccion()).contains("el grado exacto no se conoce");
    }
}
