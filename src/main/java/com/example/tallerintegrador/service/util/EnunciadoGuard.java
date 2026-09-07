package com.example.tallerintegrador.service.util;
import com.example.tallerintegrador.service.ia.PromptTemplateService;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Guardia determinista contra referencias estructurales al documento fuente en el
 * enunciado de un reactivo generado ("según la sección 3", "en base al punto 2",
 * "como se menciona en el párrafo 5", "en la página 4").
 *
 * PromptTemplateService ya le pide al modelo que no las use (SYSTEM_PROMPT, regla 1), pero
 * una instrucción en el prompt es una petición probabilística: el modelo la ignora con
 * frecuencia no despreciable, sobre todo cuando el contexto RAG llega fragmentado en
 * "puntos" o "secciones" numeradas. Pedirle mejor al modelo no elimina el fallo; lo que lo
 * elimina es no aceptar la salida que lo contiene y forzar una regeneración, igual que ya
 * se hace con la deduplicación semántica.
 *
 * Deliberadamente NO usa el LLM para esta comprobación: una regla de negación léxica es
 * más barata, 100% determinista y no puede "alucinar" que un enunciado limpio es sospechoso.
 */
public final class EnunciadoGuard {

    private EnunciadoGuard() {}

    /**
     * Patrones de referencia estructural. Cubren núcleo + variantes de conector, sin
     * exigir la frase exacta del ejemplo del prompt.
     */
    private static final List<Pattern> PATRONES = List.of(
            // "según la sección 3", "según el párrafo 2", "según el punto 4"
            Pattern.compile("(?iu)seg[uú]n\\s+(la|el)\\s+(secci[oó]n|p[aá]rrafo|punto|apartado|inciso)\\s+\\d+"),
            // "como se menciona/indica/señala en el párrafo 5"
            Pattern.compile("(?iu)como\\s+se\\s+(menciona|indica|se[ñn]ala|describe|explica)\\s+en\\s+(el|la)\\s+(p[aá]rrafo|secci[oó]n|punto|apartado)"),
            // "en base al punto 3", "de acuerdo al punto 2", "conforme al apartado 4"
            Pattern.compile("(?iu)(en\\s+base|de\\s+acuerdo|conforme)\\s+(a|al)\\s+(lo\\s+)?(el\\s+|la\\s+)?(punto|secci[oó]n|apartado|p[aá]rrafo|inciso)"),
            // "en la página 2", "en la pág. 4"
            Pattern.compile("(?iu)en\\s+la\\s+p[aá]g(ina)?\\.?\\s*\\d+"),
            // "el texto anterior/citado/mencionado" — referencia a algo fuera de pantalla
            Pattern.compile("(?iu)el\\s+texto\\s+(anterior|citado|antes\\s+mencionado|previamente\\s+mencionado)"),
            // referencias numeradas sueltas: "punto 3", "sección 2", "párrafo 4" sin verbo
            Pattern.compile("(?iu)\\b(punto|secci[oó]n|p[aá]rrafo|apartado|inciso)\\s+n?[°º]?\\s*\\d+\\b")
    );

    /** true si el enunciado contiene una referencia a la estructura del documento original. */
    public static boolean contieneReferenciaEstructural(String enunciado) {
        if (enunciado == null || enunciado.isBlank()) return false;
        return PATRONES.stream().anyMatch(p -> p.matcher(enunciado).find());
    }
}
