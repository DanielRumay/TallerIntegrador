package com.example.tallerintegrador.service.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comprueba que un reactivo de DETECCIÓN DE ERRORES generado por el modelo se pueda usar.
 *
 * QUÉ SE PIDE AHORA. Cada error es de UNA o DOS palabras, y la corrección también. El alumno
 * escribe la palabra correcta en el hueco; el resto de la oración no cambia.
 *
 * EL PROBLEMA QUE HAY QUE EVITAR. Al reemplazar el error por la corrección, la oración tiene
 * que seguir siendo gramatical. Antes se vio "recorre una territorios rurales": el artículo
 * quedaba fuera del error y la corrección no concordaba con él. La solución anterior fue
 * meter el artículo dentro del error, pero eso alargaba los errores a cuatro o cinco
 * palabras. Aquí se hace al revés: el artículo se queda FUERA y se comprueba que la
 * corrección concuerde con él.
 *
 * POR QUÉ DESCARTAR Y NO ARREGLAR. Un reactivo mal formado no se repara sin inventar
 * contenido. El generador reintenta, y una pregunta menos es mejor que una que enseña mal
 * español o que no tiene respuesta posible.
 *
 * Las comprobaciones de género y número son HEURÍSTICAS y deliberadamente conservadoras: solo
 * rechazan terminaciones que en español casi nunca fallan (-ción es femenino; -os/-as/-es
 * tras "los/las" es plural). No intentan resolver todos los casos: el prompt también lo exige.
 */
public final class ReactivoDeteccionErroresGuard {

    private ReactivoDeteccionErroresGuard() {}

    public static final int MAX_PALABRAS = 2;
    public static final int MAX_ERRORES = 3;

    public record Veredicto(boolean valido, String motivo) {
        static Veredicto ok() { return new Veredicto(true, null); }
        static Veredicto no(String motivo) { return new Veredicto(false, motivo); }
    }

    /** Palabras que no pueden abrir un error ni una corrección: deben quedarse en el texto. */
    private static final Set<String> FUNCIONALES = Set.of(
            "el", "la", "los", "las", "lo", "un", "una", "unos", "unas",
            "de", "del", "al", "a", "en", "con", "por", "para", "sin", "sobre",
            "y", "e", "o", "u", "que", "su", "sus");

    private static final Set<String> DET_PLURAL = Set.of(
            "los", "las", "unos", "unas", "estos", "estas", "esos", "esas", "aquellos", "aquellas",
            "sus", "mis", "tus", "nuestros", "nuestras", "algunos", "algunas", "varios", "varias",
            "muchos", "muchas", "pocos", "pocas", "otros", "otras");

    private static final Set<String> DET_SINGULAR = Set.of(
            "el", "la", "un", "una", "este", "esta", "ese", "esa", "aquel", "aquella", "al", "del");

    private static final Set<String> DET_FEMENINO = Set.of("la", "una", "esta", "esa", "aquella");
    private static final Set<String> DET_MASCULINO = Set.of("el", "un", "este", "ese", "aquel", "al", "del");

    /** Singulares que terminan como un plural. */
    private static final Set<String> SINGULARES_EN_S = Set.of("caos", "cosmos", "atlas", "pancreas", "torax");

    /** Femeninos terminados en -o que sí existen y no deben rechazarse. */
    private static final Set<String> FEMENINOS_EN_O = Set.of("mano", "foto", "radio", "moto", "libido", "seo");

    public static Veredicto revisar(Map<String, Object> reactivo) {
        if (reactivo == null) return Veredicto.no("reactivo vacío");

        String enunciado = texto(reactivo.get("enunciado"));
        if (enunciado.isBlank()) return Veredicto.no("sin enunciado");

        if (!(reactivo.get("opciones_o_respuesta") instanceof List<?> lista)) {
            return Veredicto.no("no trae la lista de palabras con error");
        }
        List<String> errores = lista.stream().map(ReactivoDeteccionErroresGuard::texto)
                .filter(t -> !t.isBlank()).toList();
        if (errores.isEmpty()) return Veredicto.no("no marca ningún error");
        if (errores.size() > MAX_ERRORES) return Veredicto.no("más de " + MAX_ERRORES + " errores");

        List<String> correcciones = new ArrayList<>();
        for (String c : texto(reactivo.get("respuesta_correcta")).split("\\|")) {
            if (!c.isBlank()) correcciones.add(c.strip());
        }
        if (correcciones.size() != errores.size()) {
            return Veredicto.no("hay " + errores.size() + " errores pero "
                    + correcciones.size() + " correcciones");
        }

        Set<String> vistos = new HashSet<>();
        for (int i = 0; i < errores.size(); i++) {
            String error = errores.get(i);
            String correccion = correcciones.get(i);

            if (!vistos.add(normalizar(error))) return Veredicto.no("error repetido: " + error);

            if (palabras(error).size() > MAX_PALABRAS) {
                return Veredicto.no("el error '" + error + "' tiene más de " + MAX_PALABRAS + " palabras");
            }
            if (palabras(correccion).size() > MAX_PALABRAS) {
                return Veredicto.no("la corrección '" + correccion + "' tiene más de " + MAX_PALABRAS + " palabras");
            }
            if (FUNCIONALES.contains(primera(error)) || FUNCIONALES.contains(primera(correccion))) {
                return Veredicto.no("'" + error + "' → '" + correccion
                        + "' empieza con artículo o conector; debe quedarse fuera del hueco");
            }
            if (normalizar(error).equals(normalizar(correccion))) {
                return Veredicto.no("la corrección de '" + error + "' es igual al error");
            }

            String anterior = palabraAnterior(enunciado, error);
            if (anterior == null) {
                return Veredicto.no("el error '" + error + "' no aparece en el enunciado");
            }

            String motivo = concordancia(anterior, primera(correccion), primera(error));
            if (motivo != null) return Veredicto.no(motivo);
        }
        return Veredicto.ok();
    }

    /**
     * Comprueba que la corrección concuerde con el determinante que la precede en el texto.
     * Devuelve el motivo del rechazo, o null si no hay problema detectable.
     */
    private static String concordancia(String determinante, String correccion, String error) {
        if (determinante.isEmpty()) return null;

        // -os/-as casi siempre es plural; -es es ambiguo ("análisis" no, pero "mes" o "lunes"),
        // así que solo cuenta si el error original no terminaba ya en s.
        boolean pluralClaro = correccion.length() > 3
                && (correccion.endsWith("os") || correccion.endsWith("as"))
                && !SINGULARES_EN_S.contains(correccion);
        boolean pluralDudoso = correccion.length() > 3 && correccion.endsWith("es") && !error.endsWith("s");

        if (DET_PLURAL.contains(determinante) && !correccion.endsWith("s")) {
            return "'" + determinante + " " + correccion + "': la corrección debería ir en plural";
        }
        if (DET_SINGULAR.contains(determinante) && (pluralClaro || pluralDudoso)) {
            return "'" + determinante + " " + correccion + "': la corrección debería ir en singular";
        }
        if (DET_FEMENINO.contains(determinante)
                && (correccion.endsWith("o") || correccion.endsWith("aje"))
                && !FEMENINOS_EN_O.contains(correccion)) {
            return "'" + determinante + " " + correccion + "': no concuerda en género";
        }
        if (DET_MASCULINO.contains(determinante)
                && (correccion.endsWith("cion") || correccion.endsWith("sion") || correccion.endsWith("dad")
                    || correccion.endsWith("tud") || correccion.endsWith("umbre"))) {
            return "'" + determinante + " " + correccion + "': no concuerda en género";
        }
        return null;
    }

    /**
     * La palabra que precede al error en el enunciado ("" si está al principio), o null si el
     * error no aparece como palabra completa.
     */
    static String palabraAnterior(String enunciado, String error) {
        Pattern p = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(error.strip()) + "(?![\\p{L}\\p{N}])");
        Matcher m = p.matcher(enunciado);
        if (!m.find()) return null;
        List<String> previas = palabras(enunciado.substring(0, m.start()));
        return previas.isEmpty() ? "" : previas.get(previas.size() - 1);
    }

    private static List<String> palabras(String t) {
        List<String> salida = new ArrayList<>();
        for (String w : normalizar(t).split("[^\\p{L}\\p{N}]+")) {
            if (!w.isBlank()) salida.add(w);
        }
        return salida;
    }

    private static String primera(String t) {
        List<String> w = palabras(t);
        return w.isEmpty() ? "" : w.get(0);
    }

    /** Minúsculas y sin tildes: "Núcleo" y "nucleo" son la misma palabra. */
    static String normalizar(String t) {
        String sinTildes = Normalizer.normalize(t == null ? "" : t, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return sinTildes.toLowerCase().strip();
    }

    private static String texto(Object o) {
        return o == null ? "" : String.valueOf(o).strip();
    }
}
