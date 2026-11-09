package com.example.tallerintegrador.service.ia;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrato del prompt frente a la audiencia: el bloque de audiencia llega al modelo y va antes
 * de la tarea, sin desplazar el nivel de Bloom. No mide si el modelo obedece; eso lo mide la
 * legibilidad de los reactivos en produccion (ver ControlDeCalidadReactivos).
 */
class PromptAudienciaTest {

    private final PromptTemplateService prompts = new PromptTemplateService();

    @Test
    @DisplayName("Las tres técnicas incluyen la audiencia del grado y conservan el nivel de Bloom")
    void lasTresTecnicasIncluyenLaAudiencia() {
        PerfilAudiencia segundo = PerfilAudiencia.desdeNombreGrado("2do Año de Secundaria");
        for (String tecnica : List.of(PromptTemplateService.FEW_SHOT,
                PromptTemplateService.CHAIN_OF_THOUGHT, PromptTemplateService.STRUCTURED_OUTPUT)) {
            String prompt = prompts.build(tecnica, "OPCION_MULTIPLE", "Evaluar", "INTERMEDIO",
                    "Texto de prueba", 2, List.of(), segundo);

            assertThat(prompt).as(tecnica)
                    .contains("AUDIENCIA: Un estudiante de 2.° de secundaria (13 a 14 años), ciclo VI")
                    .contains("Evaluar");
            assertThat(prompt.indexOf("AUDIENCIA:")).as(tecnica).isLessThan(prompt.indexOf("Texto de prueba"));
        }
    }

    @Test
    @DisplayName("Sin audiencia, el prompt declara secundaria en general")
    void sinAudienciaEsSecundariaGeneral() {
        String prompt = prompts.build(PromptTemplateService.STRUCTURED_OUTPUT, "OPCION_MULTIPLE",
                "Comprender", "INTERMEDIO", "Texto", 1);

        assertThat(prompt).contains("el grado exacto no se conoce");
    }

    @Test
    @DisplayName("El prompt no pide tres niveles, ni trampas, ni reglas solo de gramática")
    void sinContradiccionesNiSesgoDeArea() {
        for (String nivel : List.of("Recordar", "Comprender", "Aplicar", "Analizar", "Evaluar")) {
            String prompt = prompts.build(PromptTemplateService.STRUCTURED_OUTPUT, "OPCION_MULTIPLE", nivel, "INTERMEDIO", "Texto", 1);
            assertThat(prompt).as(nivel)
                    .doesNotContainIgnoringCase("tres niveles")
                    .doesNotContainIgnoringCase("distractores trampa")
                    .doesNotContainIgnoringCase("regla gramatical")
                    .doesNotContainIgnoringCase("comprensión lectora");
        }
    }

    @Test
    @DisplayName("Crear solo se pide en preguntas abiertas; en alternativas se pide hasta Evaluar")
    void crearSoloEnAbiertas() {
        String cerrada = prompts.build(PromptTemplateService.STRUCTURED_OUTPUT, "OPCION_MULTIPLE", "Crear", "INTERMEDIO", "Texto", 1);
        String abierta = prompts.build(PromptTemplateService.STRUCTURED_OUTPUT, "ABIERTA", "Crear", "INTERMEDIO", "Texto", 1);

        assertThat(cerrada).contains("NIVEL BLOOM OBJETIVO: Evaluar").doesNotContain("NIVEL BLOOM OBJETIVO: Crear");
        assertThat(abierta).contains("NIVEL BLOOM OBJETIVO: Crear");
    }

    @Test
    @DisplayName("El juez de la práctica ya no se presenta como profesor de quinto grado")
    void juezSinGradoFijo() {
        assertThat(PromptTemplateService.PROMPT_LLM_JUEZ).doesNotContainIgnoringCase("quinto");
    }
}
