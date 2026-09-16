package com.example.tallerintegrador.service.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReactivoDeteccionErroresGuardTest {

    private Map<String, Object> reactivo(String enunciado, List<String> errores, String correcciones) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enunciado", enunciado);
        m.put("opciones_o_respuesta", errores);
        m.put("respuesta_correcta", correcciones);
        return m;
    }

    private void valido(Map<String, Object> r) {
        var v = ReactivoDeteccionErroresGuard.revisar(r);
        assertTrue(v.valido(), "debía ser válido, motivo: " + v.motivo());
    }

    private void invalido(Map<String, Object> r) {
        assertFalse(ReactivoDeteccionErroresGuard.revisar(r).valido());
    }

    @Test
    @DisplayName("Errores de una o dos palabras que concuerdan con su artículo: válido")
    void casoBueno() {
        valido(reactivo(
                "La célula animal tiene una pared celular que la protege y su nucleolo guarda el ADN.",
                List.of("pared celular", "nucleolo"),
                "membrana celular | núcleo"));
    }

    @Test
    @DisplayName("Errores de más de dos palabras se descartan")
    void demasiadoLargo() {
        invalido(reactivo(
                "El spinner recorre una megalópolis densamente poblada.",
                List.of("megalópolis densamente poblada"),
                "territorios rurales"));
    }

    @Test
    @DisplayName("Correcciones de más de dos palabras se descartan")
    void correccionLarga() {
        invalido(reactivo("El ADN está en el nucleolo.", List.of("nucleolo"), "el núcleo de la célula"));
    }

    @Test
    @DisplayName("El artículo no puede ir dentro del hueco: se queda en el texto")
    void articuloDentro() {
        invalido(reactivo("Recorre una megalópolis enorme.", List.of("una megalópolis"), "territorios"));
    }

    @Test
    @DisplayName("'una territorios': la corrección no concuerda en número con el artículo")
    void numeroNoConcuerda() {
        invalido(reactivo("El spinner recorre una megalópolis al amanecer.",
                List.of("megalópolis"), "territorios"));
    }

    @Test
    @DisplayName("'los' exige corrección en plural")
    void pluralExigido() {
        invalido(reactivo("Los mamíferos respiran por los branquias.", List.of("branquias"), "pulmón"));
        valido(reactivo("Los mamíferos respiran por los branquias.", List.of("branquias"), "pulmones"));
    }

    @Test
    @DisplayName("Género: 'la' con corrección en -o, o 'el' con corrección en -ción, se descartan")
    void generoNoConcuerda() {
        invalido(reactivo("El ADN se guarda en la membrana.", List.of("membrana"), "núcleo"));
        invalido(reactivo("Las plantas realizan el proceso de noche.", List.of("proceso"), "respiración"));
    }

    @Test
    @DisplayName("Singulares invariables en -is no se confunden con plurales")
    void singularEnIs() {
        valido(reactivo("El texto presenta un resumen del problema.", List.of("resumen"), "análisis"));
    }

    @Test
    @DisplayName("Un error que no aparece en el enunciado se descarta")
    void noAparece() {
        invalido(reactivo("El agua hierve a cien grados.", List.of("nucleolo"), "núcleo"));
    }

    @Test
    @DisplayName("Distinta cantidad de errores y correcciones se descarta")
    void cantidadesDistintas() {
        invalido(reactivo("El sol gira alrededor de la tierra.", List.of("sol", "tierra"), "tierra"));
    }

    @Test
    @DisplayName("Una corrección idéntica al error no es corrección")
    void igualAlError() {
        invalido(reactivo("El agua es un líquido.", List.of("líquido"), "Liquido"));
    }

    @Test
    @DisplayName("Una palabra no cuenta como error si solo aparece dentro de otra")
    void palabraCompleta() {
        assertNull(ReactivoDeteccionErroresGuard.palabraAnterior("La celularidad aumenta.", "celular"));
    }
}
