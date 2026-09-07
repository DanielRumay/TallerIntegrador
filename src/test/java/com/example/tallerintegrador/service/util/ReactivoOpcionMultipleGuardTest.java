package com.example.tallerintegrador.service.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La prueba de ubicacion decide la dificultad de toda la semana del alumno, asi que un reactivo
 * mal formado ahi no molesta: contamina la medida sin que se note.
 *
 * El caso que motiva esto ocurrio de verdad: se pidio OPCION_MULTIPLE y el modelo devolvio un
 * reactivo de pregunta ABIERTA, con su rubrica de correccion ocupando el lugar de las
 * alternativas. El alumno leyo los criterios con los que iban a calificarlo como si fueran la
 * opcion A — y al ser la unica, acertaba pulsandola.
 */
class ReactivoOpcionMultipleGuardTest {

    private Map<String, Object> reactivo(Object alternativas, Object correcta) {
        return Map.of(
                "enunciado", "¿Por que el profesor trata distinto a Humberto?",
                "opciones_o_respuesta", alternativas == null ? List.of() : alternativas,
                "respuesta_correcta", correcta == null ? "" : correcta);
    }

    @Test
    @DisplayName("Una opcion multiple bien formada pasa")
    void unaOpcionMultipleValidaPasa() {
        var v = ReactivoOpcionMultipleGuard.revisar(reactivo(
                List.of("A) Porque su padre tiene dinero",
                        "B) Porque llego tarde",
                        "C) Porque no hizo la tarea"),
                "A) Porque su padre tiene dinero"));

        assertTrue(v.valido(), "motivo inesperado: " + v.motivo());
    }

    @Test
    @DisplayName("Se descarta el reactivo cuya unica 'alternativa' es la rubrica")
    void descartaLaRubricaDisfrazadaDeOpcion() {
        // Texto real del caso que aparecio en pantalla.
        var v = ReactivoOpcionMultipleGuard.revisar(reactivo(
                List.of("Rubrica: 1. Identifica el acto juzgado. 2. Aplica un criterio de justicia."),
                "Rubrica"));

        assertFalse(v.valido());
        assertNotNull(v.motivo());
    }

    @Test
    @DisplayName("Tambien se descarta si la rubrica viene acompanada de otra alternativa")
    void descartaLaRubricaAunqueNoEsteSola() {
        var v = ReactivoOpcionMultipleGuard.revisar(reactivo(
                List.of("Rúbrica: criterio uno. criterio dos.", "B) Otra cosa"),
                "B) Otra cosa"));

        assertFalse(v.valido(), "una rubrica entre las alternativas invalida el reactivo");
    }

    @Test
    @DisplayName("Una sola alternativa nunca es opcion multiple")
    void unaSolaAlternativaNoVale() {
        // Aunque no diga "rubrica": con una unica opcion, acertar es pulsar lo unico que hay.
        var v = ReactivoOpcionMultipleGuard.revisar(reactivo(
                List.of("A) La desigualdad social"), "A) La desigualdad social"));

        assertFalse(v.valido());
    }

    @Test
    @DisplayName("Sin respuesta correcta se descarta: el alumno fallaria por un defecto ajeno")
    void sinRespuestaCorrectaSeDescarta() {
        var v = ReactivoOpcionMultipleGuard.revisar(reactivo(
                List.of("A) Una", "B) Otra"), ""));

        assertFalse(v.valido());
    }

    @Test
    @DisplayName("Sin enunciado se descarta")
    void sinEnunciadoSeDescarta() {
        var v = ReactivoOpcionMultipleGuard.revisar(Map.of(
                "enunciado", "",
                "opciones_o_respuesta", List.of("A) Una", "B) Otra"),
                "respuesta_correcta", "A) Una"));

        assertFalse(v.valido());
    }

    @Test
    @DisplayName("Entradas nulas o incompletas no revientan")
    void toleraEntradasIncompletas() {
        assertFalse(ReactivoOpcionMultipleGuard.revisar(null).valido());
        assertFalse(ReactivoOpcionMultipleGuard.revisar(Map.of()).valido());
        assertFalse(ReactivoOpcionMultipleGuard.revisar(
                Map.of("enunciado", "algo", "opciones_o_respuesta", "no soy una lista")).valido());
    }

    @Test
    @DisplayName("Las alternativas vacias no cuentan para el minimo")
    void lasAlternativasVaciasNoCuentan() {
        // Dos elementos, pero uno es basura: sigue siendo una sola opcion real.
        var v = ReactivoOpcionMultipleGuard.revisar(reactivo(
                java.util.Arrays.asList("A) La unica", "   "), "A) La unica"));

        assertFalse(v.valido());
    }
}
