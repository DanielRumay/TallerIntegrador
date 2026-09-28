package com.example.tallerintegrador.service.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifica que una correccion de estilo haya arreglado la redaccion SIN cambiar lo que la
 * pregunta mide.
 *
 * EL RIESGO CONCRETO. Un modelo al que se le pide "mejora la redaccion" reescribe de mas: le
 * quita el "no" a un enunciado y convierte un verdadero en falso; cambia "todos" por
 * "algunos"; redondea un 15 a 20; o reordena las alternativas y deja la clave apuntando a
 * otra. Nada de eso lo detecta un esquema JSON, porque el objeto sigue estando bien formado.
 *
 * Esta guardia es determinista a proposito: lo que se puede comprobar con una regla no se le
 * pregunta a un modelo. Mismo principio que EnunciadoGuard y VerificadorAgent.
 *
 * QUE EXIGE:
 *   1. Que haya enunciado y que no se haya disparado ni encogido (0.6x a 2.0x del original).
 *      El techo se subio a 2.0 a proposito: el corrector puede ANADIR palabras para
 *      dejar clara la tarea, y un enunciado mas largo y preciso es mejor que uno breve
 *      y ambiguo. El suelo de 0.6 sigue atrapando el recorte que se come la pregunta.
 *   2. Que se conserven las negaciones y los cuantificadores: cambiarlos altera el valor de
 *      verdad del reactivo, no su estilo.
 *   3. Que se conserven todos los numeros del enunciado original.
 *   4. Que el numero de alternativas sea el mismo y que ninguna quede duplicada.
 *   5. Que la respuesta correcta siga estando entre las alternativas.
 *   6. Que la reescritura no introduzca referencias al documento fuente (reusa EnunciadoGuard).
 *   7. Que algo haya cambiado: si el texto es identico, no era una correccion.
 */
public final class CorreccionEstiloGuard {

    private CorreccionEstiloGuard() {}

    /** Palabras cuyo cambio altera el significado logico del enunciado, no su estilo. */
    private static final Set<String> PALABRAS_CRITICAS = Set.of(
            "no", "nunca", "jamas", "ningun", "ninguna", "ninguno", "tampoco", "sin",
            "todos", "todas", "todo", "toda", "siempre", "cada", "unicamente", "solo",
            "algunos", "algunas", "varios", "varias", "mayoria", "minoria",
            "excepto", "salvo", "incorrecto", "incorrecta", "falso", "falsa",
            "verdadero", "verdadera", "correcto", "correcta");

    private static final Pattern NUMEROS = Pattern.compile("\\d+");
    private static final Pattern PALABRAS = Pattern.compile("[\\p{L}]+");

    public record Veredicto(boolean valido, String motivo) {
        public static Veredicto ok() { return new Veredicto(true, null); }
        public static Veredicto no(String motivo) { return new Veredicto(false, motivo); }
    }

    /**
     * @param enunciadoOriginal  enunciado tal como lo genero el modelo
     * @param opcionesOriginales alternativas originales; vacio o null en preguntas abiertas
     * @param respuestaCorrecta  texto de la respuesta correcta; puede ser null
     * @param enunciadoNuevo     enunciado propuesto por el corrector
     * @param opcionesNuevas     alternativas propuestas por el corrector
     */
    public static Veredicto revisar(String enunciadoOriginal,
                                    List<String> opcionesOriginales,
                                    String respuestaCorrecta,
                                    String enunciadoNuevo,
                                    List<String> opcionesNuevas) {

        if (enunciadoNuevo == null || enunciadoNuevo.isBlank()) {
            return Veredicto.no("La correccion no trae enunciado.");
        }
        if (enunciadoOriginal == null || enunciadoOriginal.isBlank()) {
            return Veredicto.no("No hay enunciado original con el que comparar.");
        }

        String original = enunciadoOriginal.trim();
        String nuevo = enunciadoNuevo.trim();

        if (original.equals(nuevo) && iguales(opcionesOriginales, opcionesNuevas)) {
            return Veredicto.no("La correccion es identica al original.");
        }

        // Acortar es legitimo y hasta deseable: quitar material irrelevante del enunciado es una
        // de las reglas de Haladyna. Lo que no se permite es que el enunciado quede tan corto
        // que deje de plantear la pregunta, ni que crezca hasta cambiar de naturaleza.
        int palabrasNuevas = nuevo.trim().split("\\s+").length;
        if (palabrasNuevas < 4 || nuevo.length() < 20) {
            return Veredicto.no("La reescritura deja un enunciado demasiado corto: la extension cae a "
                    + palabrasNuevas + " palabra(s).");
        }
        double proporcion = (double) nuevo.length() / original.length();
        if (proporcion < 0.6 || proporcion > 2.0) {
            return Veredicto.no("La reescritura cambia demasiado la extension del enunciado ("
                    + Math.round(proporcion * 100) + "% del original).");
        }

        List<String> criticasPerdidas = criticasFaltantes(original, nuevo);
        if (!criticasPerdidas.isEmpty()) {
            return Veredicto.no("La reescritura altera palabras que cambian el significado: "
                    + String.join(", ", criticasPerdidas) + ".");
        }

        Set<String> numerosOriginales = numeros(original);
        Set<String> numerosNuevos = numeros(nuevo);
        if (!numerosNuevos.containsAll(numerosOriginales)) {
            return Veredicto.no("La reescritura perdio o cambio datos numericos del enunciado.");
        }

        if (EnunciadoGuard.contieneReferenciaEstructural(nuevo)) {
            return Veredicto.no("La reescritura introduce una referencia al documento fuente.");
        }

        boolean teniaOpciones = opcionesOriginales != null && !opcionesOriginales.isEmpty();
        if (teniaOpciones) {
            if (opcionesNuevas == null || opcionesNuevas.size() != opcionesOriginales.size()) {
                return Veredicto.no("La correccion cambia el numero de alternativas.");
            }
            Set<String> sinRepetir = new LinkedHashSet<>();
            for (String opcion : opcionesNuevas) {
                if (opcion == null || opcion.isBlank()) {
                    return Veredicto.no("Hay una alternativa vacia tras la correccion.");
                }
                if (!sinRepetir.add(normalizar(sinEtiqueta(opcion)))) {
                    return Veredicto.no("La correccion deja dos alternativas equivalentes.");
                }
            }
            if (respuestaCorrecta != null && !respuestaCorrecta.isBlank()
                    && !contieneRespuesta(opcionesNuevas, respuestaCorrecta)) {
                return Veredicto.no("La respuesta correcta ya no figura entre las alternativas.");
            }
        }

        return Veredicto.ok();
    }

    /** Palabras criticas que estaban en el original y desaparecieron (o al reves). */
    private static List<String> criticasFaltantes(String original, String nuevo) {
        List<String> diferencias = new ArrayList<>();
        Set<String> enOriginal = criticasDe(original);
        Set<String> enNuevo = criticasDe(nuevo);
        for (String palabra : enOriginal) {
            if (!enNuevo.contains(palabra)) diferencias.add(palabra);
        }
        for (String palabra : enNuevo) {
            if (!enOriginal.contains(palabra)) diferencias.add(palabra + " (anadida)");
        }
        return diferencias;
    }

    private static Set<String> criticasDe(String texto) {
        Set<String> encontradas = new LinkedHashSet<>();
        Matcher m = PALABRAS.matcher(normalizar(texto));
        while (m.find()) {
            if (PALABRAS_CRITICAS.contains(m.group())) encontradas.add(m.group());
        }
        return encontradas;
    }

    private static Set<String> numeros(String texto) {
        Set<String> encontrados = new LinkedHashSet<>();
        Matcher m = NUMEROS.matcher(texto);
        while (m.find()) encontrados.add(m.group());
        return encontrados;
    }

    private static boolean contieneRespuesta(List<String> opciones, String respuesta) {
        String buscada = normalizar(sinEtiqueta(respuesta));
        for (String opcion : opciones) {
            String candidata = normalizar(sinEtiqueta(opcion));
            if (candidata.equals(buscada) || candidata.contains(buscada) || buscada.contains(candidata)) {
                return true;
            }
        }
        return false;
    }

    private static boolean iguales(List<String> a, List<String> b) {
        if (a == null || a.isEmpty()) return b == null || b.isEmpty();
        if (b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!normalizar(a.get(i)).equals(normalizar(b.get(i)))) return false;
        }
        return true;
    }

    /**
     * Quita la etiqueta de la alternativa ("A)", "b.", "3 -") antes de comparar.
     *
     * Sin esto, "A) El sujeto" y "B) el sujeto" parecerian alternativas distintas y el reactivo
     * llegaria al alumno con dos opciones equivalentes, que es justo uno de los defectos que el
     * critico de forma rechaza.
     */
    private static String sinEtiqueta(String opcion) {
        if (opcion == null) return "";
        return opcion.trim().replaceFirst("^[\\p{L}\\d]\\s*[)\\.\\-:]\\s*", "");
    }

    /** Minusculas y sin tildes, para comparar sin que una tilde cuente como cambio de sentido. */
    private static String normalizar(String texto) {
        if (texto == null) return "";
        String limpio = java.text.Normalizer.normalize(texto.trim().toLowerCase(Locale.ROOT),
                java.text.Normalizer.Form.NFD);
        return limpio.replaceAll("\\p{M}", "").replaceAll("\\s+", " ");
    }
}
