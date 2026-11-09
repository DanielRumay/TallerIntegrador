package com.example.tallerintegrador.service.ia;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class MarcoCnebTest {

    private final MarcoCneb marco = new MarcoCneb();

    private PerfilAudiencia secundaria(int grado) {
        return PerfilAudiencia.desdeNombreGrado(grado + "to Año de Secundaria");
    }

    @ParameterizedTest
    @CsvSource({
            "Comunicación 2.° A, Comunicación",
            "Razonamiento Verbal, Comunicación",
            "Álgebra, Matemática",
            "Ciencia y Tecnología 3B, Ciencia y Tecnología",
            "Física, Ciencia y Tecnología",
            "Historia del Perú, Ciencias Sociales",
            "DPCC, 'Desarrollo Personal, Ciudadanía y Cívica'",
            "Inglés, Inglés",
    })
    void reconoceElArea(String curso, String area) {
        assertThat(MarcoCneb.areaDe(curso)).isEqualTo(area);
    }

    @Test
    @DisplayName("Educación Física y un curso desconocido no tienen marco: mejor nada que uno equivocado")
    void sinArea() {
        assertThat(MarcoCneb.areaDe("Educación Física")).isNull();
        assertThat(marco.instruccion("Taller de robótica", secundaria(2))).isEmpty();
    }

    @Test
    @DisplayName("Entrega los desempeños del grado exacto, no los del otro grado del ciclo")
    void desempenosDelGrado() {
        String segundo = marco.instruccion("Comunicación", secundaria(2));
        String primero = marco.instruccion("Comunicación", secundaria(1));

        assertThat(segundo)
                .contains("área de Comunicación, ciclo VI, 2.° de secundaria")
                .contains("Lee diversos tipos de textos escritos en su lengua materna")
                .contains("Obtiene e integra información contrapuesta")
                .contains("competencia_cneb");
        assertThat(primero).doesNotContain("Obtiene e integra información contrapuesta");
    }

    @Test
    @DisplayName("Los ciclos usan su propio estándar")
    void estandarDelCiclo() {
        assertThat(marco.instruccion("Comunicación", secundaria(1))).contains("Estándar del ciclo: Lee diversos tipos de texto con estructuras complejas y vocabulario variado.");
        assertThat(marco.instruccion("Comunicación", secundaria(5))).contains("vocabulario variado y especializado");
    }

    @Test
    @DisplayName("Un área con varias competencias las incluye todas sin los estándares largos")
    void areaConVarias() {
        String mate = marco.instruccion("Matemática", secundaria(4));

        assertThat(mate).contains("Resuelve problemas de cantidad", "Resuelve problemas de gestión de datos e incertidumbre");
        assertThat(mate).doesNotContain("Estándar del ciclo");
    }

    @Test
    @DisplayName("Sin grado o en primaria no se inventa marco")
    void sinGradoNiPrimaria() {
        assertThat(marco.instruccion("Comunicación", PerfilAudiencia.GENERAL)).isEmpty();
        assertThat(marco.instruccion("Comunicación", PerfilAudiencia.desdeNombreGrado("5to primaria"))).isEmpty();
    }

    @Test
    @DisplayName("El marco llega al prompt junto con la audiencia y después de ella")
    void llegaAlPrompt() {
        PerfilAudiencia conMarco = secundaria(2).conMarco(marco.instruccion("Comunicación", secundaria(2)));

        assertThat(conMarco.instruccion()).contains("AUDIENCIA:").contains("MARCO CURRICULAR");
        assertThat(conMarco).isNotEqualTo(secundaria(2));
    }
}
