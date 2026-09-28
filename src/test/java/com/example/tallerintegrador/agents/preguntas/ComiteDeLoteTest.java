package com.example.tallerintegrador.agents.preguntas;

import dev.langchain4j.service.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La revisión en lote es lo que hace viable conectar el comité: con diez preguntas pasa de
 * treinta llamadas al modelo a tres. Estas pruebas cubren lo que puede salir mal al juzgar
 * varios reactivos de una vez, sin gastar una sola llamada real.
 */
class ComiteDeLoteTest {

    private CriticoDeLote contenido;
    private CriticoDeLote pedagogico;
    private CriticoDeLote forma;
    private ComiteDePreguntasService comite;

    private static final List<ComiteDePreguntasService.ReactivoARevisar> DOS = List.of(
            new ComiteDePreguntasService.ReactivoARevisar(
                    "¿Cuál es el núcleo del sujeto?", "El sustantivo", List.of("A) El sustantivo", "B) El verbo")),
            new ComiteDePreguntasService.ReactivoARevisar(
                    "¿Qué función cumple el predicado?", "Informar sobre el sujeto", List.of()));

    @BeforeEach
    void setUp() {
        contenido = mock(CriticoDeLote.class);
        pedagogico = mock(CriticoDeLote.class);
        forma = mock(CriticoDeLote.class);
        comite = new ComiteDePreguntasService(
                mock(CriticoDePreguntas.class), mock(CriticoDePreguntas.class), mock(CriticoDePreguntas.class),
                contenido, pedagogico, forma);
        todosAprueban(contenido);
        todosAprueban(pedagogico);
        todosAprueban(forma);
    }

    private void todosAprueban(CriticoDeLote critico) {
        responder(critico,
                new VeredictoDeReactivo(1, true, 5, "", "", "cita", ""),
                new VeredictoDeReactivo(2, true, 4, "", "", "cita", ""));
    }

    private void responder(CriticoDeLote critico, VeredictoDeReactivo... veredictos) {
        when(critico.revisar(anyString()))
                .thenReturn(Result.<DictamenDeLote>builder()
                        .content(new DictamenDeLote(List.of(veredictos)))
                        .build());
    }

    @Test
    @DisplayName("Con los tres críticos aprobando, todo el lote pasa y se llama una vez a cada uno")
    void loteAprobado() {
        List<ComiteDePreguntasService.Dictamen> dictamenes = comite.revisarLote(DOS, "material", "Comprender", null);

        assertThat(dictamenes).hasSize(2);
        assertThat(dictamenes).allMatch(ComiteDePreguntasService.Dictamen::aprobada);
        verify(contenido).revisar(anyString());
        verify(pedagogico).revisar(anyString());
        verify(forma).revisar(anyString());
    }

    @Test
    @DisplayName("El rechazo cae solo sobre el reactivo señalado por su id, no sobre todo el lote")
    void rechazoSoloAlReactivoSenalado() {
        responder(forma,
                new VeredictoDeReactivo(1, true, 5, "", "", "cita", ""),
                new VeredictoDeReactivo(2, false, 2, "Enunciado ambiguo", "Acota la extensión esperada", "cita", ""));

        List<ComiteDePreguntasService.Dictamen> dictamenes = comite.revisarLote(DOS, "material", "Comprender", null);

        assertThat(dictamenes.get(0).aprobada()).isTrue();
        assertThat(dictamenes.get(1).aprobada()).isFalse();
        assertThat(dictamenes.get(1).instruccionesDeCorreccion()).contains("Acota");
    }

    @Test
    @DisplayName("Un crítico caído no tumba el lote: se marca -1 y las preguntas siguen")
    void criticoCaidoNoTumbaElLote() {
        when(forma.revisar(anyString())).thenThrow(new RuntimeException("timeout"));

        List<ComiteDePreguntasService.Dictamen> dictamenes = comite.revisarLote(DOS, "material", "Comprender", null);

        assertThat(dictamenes).allMatch(ComiteDePreguntasService.Dictamen::aprobada);
        assertThat(dictamenes.get(0).puntuaciones()).containsEntry("forma", -1);
    }

    @Test
    @DisplayName("Si falta el veredicto de un reactivo, no se rechaza: queda sin puntuar")
    void veredictoFaltante() {
        responder(pedagogico, new VeredictoDeReactivo(1, true, 5, "", "", "cita", ""));

        List<ComiteDePreguntasService.Dictamen> dictamenes = comite.revisarLote(DOS, "material", "Comprender", null);

        assertThat(dictamenes.get(1).aprobada()).isTrue();
        assertThat(dictamenes.get(1).puntuaciones()).containsEntry("pedagogico", -1);
    }

    @Test
    @DisplayName("Un id fuera de rango se ignora en vez de romper la revisión")
    void idFueraDeRango() {
        responder(contenido,
                new VeredictoDeReactivo(99, false, 1, "No está en el material", "Regenera", "cita", ""),
                new VeredictoDeReactivo(1, true, 5, "", "", "cita", ""),
                new VeredictoDeReactivo(2, true, 5, "", "", "cita", ""));

        List<ComiteDePreguntasService.Dictamen> dictamenes = comite.revisarLote(DOS, "material", "Comprender", null);

        assertThat(dictamenes).allMatch(ComiteDePreguntasService.Dictamen::aprobada);
    }

    @Test
    @DisplayName("Sin material no se consulta al crítico de contenido: no tendría con qué contrastar")
    void sinMaterialNoSeConsultaContenido() {
        comite.revisarLote(DOS, "  ", "Comprender", null);

        verify(contenido, never()).revisar(anyString());
        verify(pedagogico).revisar(anyString());
    }

    @Test
    @DisplayName("Una pregunta que cita el documento se rechaza sin gastar llamadas al modelo")
    void rechazoDeterministaAntesDeGastar() {
        List<ComiteDePreguntasService.ReactivoARevisar> conReferencia = List.of(
                new ComiteDePreguntasService.ReactivoARevisar(
                        "Según el punto 3 del texto, ¿cuál es el sujeto?", "El sustantivo", List.of()));

        List<ComiteDePreguntasService.Dictamen> dictamenes =
                comite.revisarLote(conReferencia, "material", "Recordar", null);

        assertThat(dictamenes.get(0).aprobada()).isFalse();
        assertThat(dictamenes.get(0).puntuaciones()).containsKey("estructura");
        verify(contenido, never()).revisar(anyString());
    }

    @Test
    @DisplayName("El nivel de lectura estimado por el crítico pedagógico no rechaza por sí solo")
    void nivelDeLecturaNoRechaza() {
        responder(pedagogico,
                new VeredictoDeReactivo(1, true, 4, "", "", "cita", "superior"),
                new VeredictoDeReactivo(2, true, 4, "", "", "cita", "1-2 secundaria"));

        assertThat(comite.revisarLote(DOS, "material", "Comprender", null))
                .allMatch(ComiteDePreguntasService.Dictamen::aprobada);
    }

    @Test
    @DisplayName("Un lote vacío no llama a nadie")
    void loteVacio() {
        assertThat(comite.revisarLote(List.of(), "material", "Comprender", null)).isEmpty();
        verify(contenido, never()).revisar(anyString());
    }
}
