package com.example.tallerintegrador.service.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El corrector de estilo puede reescribir de mas y cambiar lo que el reactivo mide: quitar un
 * "no" invierte un verdadero/falso, cambiar "todos" por "algunos" cambia la respuesta, y
 * reordenar alternativas deja la clave apuntando a otra. Un esquema JSON no detecta nada de
 * eso, porque el objeto sigue bien formado. Estas pruebas cubren cada caso.
 */
class CorreccionEstiloGuardTest {

    private static final List<String> CUATRO = List.of(
            "A) El sujeto", "B) El predicado", "C) El verbo", "D) El complemento");

    @Test
    @DisplayName("Acepta una reescritura que solo mejora la redaccion")
    void aceptaMejoraDeRedaccion() {
        // Desenreda la sintaxis sin recortar a la mitad: eso es aclarar.
        var v = CorreccionEstiloGuard.revisar(
                "¿Cual de las siguientes alternativas es la que corresponde al nucleo del sujeto?",
                CUATRO, "A) El sujeto",
                "¿Cual de estas alternativas es el nucleo del sujeto?",
                CUATRO);
        assertThat(v.valido()).isTrue();
    }

    @Test
    @DisplayName("Acepta ALARGAR el enunciado: aclarar la tarea vale mas que acortarla")
    void aceptaQueSeAlargueParaAclarar() {
        var v = CorreccionEstiloGuard.revisar(
                "¿Que diferencia al participio irregular?",
                CUATRO, "A) El sujeto",
                "¿Que diferencia a un participio irregular de uno regular en sus terminaciones?",
                CUATRO);
        assertThat(v.valido()).isTrue();
    }

    @Test
    @DisplayName("Rechaza el recorte que se come la pregunta, aunque suene mas simple")
    void rechazaRecorteExcesivo() {
        // 78 caracteres a 33: no es redactar mejor, es preguntar otra cosa mas facil.
        var v = CorreccionEstiloGuard.revisar(
                "¿Cual de las siguientes alternativas es la que corresponde al nucleo del sujeto?",
                CUATRO, "A) El sujeto",
                "¿Cual es el nucleo?",
                CUATRO);
        assertThat(v.valido()).isFalse();
    }

    @Test
    @DisplayName("Rechaza si desaparece una negacion: cambia el valor de verdad")
    void rechazaPerdidaDeNegacion() {
        var v = CorreccionEstiloGuard.revisar(
                "El sujeto no siempre aparece al inicio de la oracion.", List.of(), "Verdadero",
                "El sujeto aparece al inicio de la oracion.", List.of());
        assertThat(v.valido()).isFalse();
        assertThat(v.motivo()).contains("significado");
    }

    @Test
    @DisplayName("Rechaza si se agrega una negacion que no estaba")
    void rechazaNegacionAnadida() {
        var v = CorreccionEstiloGuard.revisar(
                "El verbo concuerda con el sujeto en numero.", List.of(), "Verdadero",
                "El verbo no concuerda con el sujeto en numero.", List.of());
        assertThat(v.valido()).isFalse();
    }

    @Test
    @DisplayName("Rechaza si cambia un cuantificador")
    void rechazaCambioDeCuantificador() {
        var v = CorreccionEstiloGuard.revisar(
                "Todos los verbos tienen sujeto.", List.of(), "Verdadero",
                "Algunos verbos tienen sujeto.", List.of());
        assertThat(v.valido()).isFalse();
    }

    @Test
    @DisplayName("Rechaza si pierde un dato numerico")
    void rechazaPerdidaDeNumero() {
        var v = CorreccionEstiloGuard.revisar(
                "Senala las 3 partes de la oracion simple.", List.of(), "Sujeto, verbo y predicado",
                "Senala las partes de la oracion simple.", List.of());
        assertThat(v.valido()).isFalse();
        assertThat(v.motivo()).contains("numericos");
    }

    @Test
    @DisplayName("Rechaza si cambia el numero de alternativas")
    void rechazaCambioDeCantidadDeOpciones() {
        var v = CorreccionEstiloGuard.revisar(
                "¿Cual es el nucleo del sujeto?", CUATRO, "A) El sujeto",
                "¿Cual es el nucleo del sujeto, exactamente?",
                List.of("A) El sujeto", "B) El predicado", "C) El verbo"));
        assertThat(v.valido()).isFalse();
        assertThat(v.motivo()).contains("numero de alternativas");
    }

    @Test
    @DisplayName("Rechaza si deja dos alternativas equivalentes")
    void rechazaAlternativasDuplicadas() {
        var v = CorreccionEstiloGuard.revisar(
                "¿Cual es el nucleo del sujeto?", CUATRO, "A) El sujeto",
                "¿Cual es el nucleo del sujeto?",
                List.of("A) El sujeto", "B) el  sujeto", "C) El verbo", "D) El complemento"));
        assertThat(v.valido()).isFalse();
        assertThat(v.motivo()).contains("equivalentes");
    }

    @Test
    @DisplayName("Rechaza si la respuesta correcta desaparece de las alternativas")
    void rechazaRespuestaCorrectaAusente() {
        var v = CorreccionEstiloGuard.revisar(
                "¿Cual es el nucleo del sujeto?", CUATRO, "A) El sujeto",
                "¿Cual es el nucleo del sujeto?",
                List.of("A) La oracion", "B) El predicado", "C) El verbo", "D) El complemento"));
        assertThat(v.valido()).isFalse();
        assertThat(v.motivo()).contains("respuesta correcta");
    }

    @Test
    @DisplayName("Rechaza si introduce una referencia al documento fuente")
    void rechazaReferenciaEstructural() {
        var v = CorreccionEstiloGuard.revisar(
                "¿Cual es el nucleo del sujeto en una oracion simple?", List.of(), "El sustantivo",
                "Segun el texto anterior, ¿cual es el nucleo del sujeto?", List.of());
        assertThat(v.valido()).isFalse();
    }

    @Test
    @DisplayName("Rechaza una reescritura identica: no era una correccion")
    void rechazaTextoIdentico() {
        var v = CorreccionEstiloGuard.revisar(
                "¿Cual es el nucleo del sujeto?", List.of(), "El sustantivo",
                "¿Cual es el nucleo del sujeto?", List.of());
        assertThat(v.valido()).isFalse();
        assertThat(v.motivo()).contains("identica");
    }

    @Test
    @DisplayName("Rechaza si la reescritura dispara o encoge demasiado el enunciado")
    void rechazaCambioDeExtension() {
        var v = CorreccionEstiloGuard.revisar(
                "¿Cual es el nucleo del sujeto en la oracion simple que acabas de leer con atencion?",
                List.of(), "El sustantivo",
                "¿Nucleo?", List.of());
        assertThat(v.valido()).isFalse();
        assertThat(v.motivo()).contains("extension");
    }

    @Test
    @DisplayName("Una tilde o una mayuscula no cuentan como cambio de significado")
    void toleraTildesYMayusculas() {
        var v = CorreccionEstiloGuard.revisar(
                "El sujeto NO siempre va al inicio de una oracion cualquiera.", List.of(), "Verdadero",
                "El sujeto no siempre va al inicio de la oración.", List.of());
        assertThat(v.valido()).isTrue();
    }

    @Test
    @DisplayName("Rechaza una correccion sin enunciado")
    void rechazaEnunciadoVacio() {
        var v = CorreccionEstiloGuard.revisar("¿Cual es el sujeto?", List.of(), "El sustantivo", "  ", List.of());
        assertThat(v.valido()).isFalse();
    }
}
