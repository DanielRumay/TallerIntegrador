package com.example.tallerintegrador.service.util;

import java.util.List;
import java.util.Map;

/**
 * Comprueba que un reactivo generado como OPCION_MULTIPLE lo sea de verdad.
 *
 * EL PROBLEMA. Al generador se le pide un tipo, pero lo que devuelve es texto de un modelo: no
 * hay garantía de que obedezca. En la prueba de ubicación —que pide OPCION_MULTIPLE en los tres
 * estratos, porque su corrección debe ser objetiva— el modelo devolvió un reactivo de pregunta
 * ABIERTA, con su rúbrica de corrección en el campo de las alternativas. Nadie lo comprobaba,
 * así que llegó a la pantalla y el alumno vio esto como si fuera la opción A:
 *
 *     A — Rubrica: 1. Identifica el acto juzgado. 2. Aplica un criterio de justicia...
 *
 * Es decir, se le entregaron los criterios con los que se le iba a calificar, en una prueba que
 * decide su nivel de toda la semana. Y como la "alternativa" era única, cualquier alumno
 * acertaba el 100% de ese reactivo pulsando lo único que había.
 *
 * QUÉ HACE. Descarta el reactivo en vez de intentar arreglarlo. Un ítem mal formado no se puede
 * reparar sin inventar contenido, y la prueba funciona perfectamente con uno menos; entregarlo
 * roto contamina la medición del nivel, que es lo que esta prueba existe para producir.
 */
public final class ReactivoOpcionMultipleGuard {

    private ReactivoOpcionMultipleGuard() {}

    /** Menos de esto no es una pregunta de alternativas, sea lo que sea. */
    private static final int MINIMO_ALTERNATIVAS = 2;

    public record Veredicto(boolean valido, String motivo) {}

    /**
     * @param reactivo el mapa tal como lo devolvió el generador
     */
    public static Veredicto revisar(Map<String, Object> reactivo) {
        if (reactivo == null || reactivo.isEmpty()) {
            return new Veredicto(false, "reactivo vacio");
        }

        Object enunciado = reactivo.get("enunciado");
        if (enunciado == null || String.valueOf(enunciado).isBlank()) {
            return new Veredicto(false, "sin enunciado");
        }

        Object crudo = reactivo.get("opciones_o_respuesta");
        if (!(crudo instanceof List<?> lista)) {
            return new Veredicto(false, "no trae lista de alternativas");
        }

        List<String> alternativas = lista.stream()
                .map(o -> o == null ? "" : String.valueOf(o).strip())
                .filter(t -> !t.isEmpty())
                .toList();

        if (alternativas.size() < MINIMO_ALTERNATIVAS) {
            // Una sola "alternativa" es la firma de una pregunta abierta colada aqui: el
            // esquema del generador pone la rubrica como unico elemento de esta lista.
            return new Veredicto(false,
                    "solo " + alternativas.size() + " alternativa(s); no es opcion multiple");
        }

        for (String a : alternativas) {
            if (a.toLowerCase().matches("^r[uú]brica\\s*:.*")) {
                return new Veredicto(false, "una alternativa es en realidad la rubrica de correccion");
            }
        }

        Object correcta = reactivo.get("respuesta_correcta");
        if (correcta == null || String.valueOf(correcta).isBlank()) {
            // Sin respuesta correcta no se puede calificar, y el alumno acabaria con un fallo
            // que no es suyo.
            return new Veredicto(false, "sin respuesta correcta");
        }

        return new Veredicto(true, null);
    }
}
