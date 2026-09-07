package com.example.tallerintegrador.service.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Encaja los conceptos que etiqueta el generador de preguntas dentro del vocabulario de
 * subtemas que el docente ya curó.
 *
 * EL PROBLEMA. El sistema manejaba DOS vocabularios distintos, generados por separado:
 *
 *   - Subtemas del material: se extraen al ingerir el PDF, y el docente los revisa y descarta.
 *   - Conceptos de cada pregunta: los inventa el generador en el momento, sin ver los otros.
 *
 * Solo los segundos alimentan el BKT y el mapa de calor. Eso tenía tres consecuencias malas y
 * poco evidentes:
 *
 *   1. Curar no servía de nada para la medición. El docente descartaba "Literatura" y la
 *      etiqueta seguía apareciendo en el mapa de calor del alumno.
 *   2. El dominio se fragmentaba. "Abuso", "Abuso de poder" y "Mecanismos de abuso" eran tres
 *      conceptos independientes, cada uno con una o dos observaciones. Con P_INIT=0.30 bastan
 *      DOS aciertos sobre el MISMO concepto para pasar el umbral de 0.75, así que la
 *      fragmentación es exactamente la razón de que un alumno con 128 puntos tuviera cero
 *      temas dominados.
 *   3. Nada era auditable. Nadie podía comprobar contra qué lista se estaba midiendo.
 *
 * QUÉ HACE. Para cada concepto crudo busca el subtema curado que le corresponde y lo
 * sustituye. "Abuso" y "Mecanismos de abuso" pasan a ser ambos "Abuso de poder", y sus
 * aciertos se acumulan en la misma cuenta.
 *
 * QUÉ NO HACE, Y POR QUÉ. Si un concepto no encaja con ninguno, se CONSERVA tal cual en vez de
 * tirarse. Descartarlo dejaría a esa pregunta sin ninguna etiqueta y el BKT no aprendería nada
 * de ella: por arreglar la fragmentación se perdería la medición entera. Conservarlo es, como
 * mucho, el comportamiento que ya había. Esta clase solo puede mejorar el agrupamiento, nunca
 * empeorarlo.
 */
public final class AlineadorConceptos {

    private AlineadorConceptos() {}

    /**
     * Regla de encaje: el NÚCLEO del subtema curado tiene que aparecer en el concepto.
     *
     * En una frase nominal española el núcleo va delante ("abuso de poder" trata del abuso;
     * "autoridad escolar" trata de la autoridad). Lo de después modifica.
     *
     * Se llegó a esta regla porque el simple solapamiento de palabras NO distingue los dos
     * casos, y una prueba lo demostró:
     *
     *   "Mecanismos de abuso" vs "Abuso de poder"   -> comparten 1 de 2 palabras -> DEBE unir
     *   "Justicia escolar"    vs "Autoridad escolar" -> comparten 1 de 2 palabras -> NO debe
     *
     * Misma proporción, decisiones opuestas. Lo que las separa es CUÁL palabra comparten: en
     * el primer caso es el núcleo del subtema ("abuso"); en el segundo, un adjetivo que
     * aparece en media obra ("escolar"). Exigir el núcleo acierta en los dos.
     */
    private static final double SOLAPE_MINIMO = 0.5;

    /** Palabras que no distinguen nada y no deben contar para el solapamiento. */
    private static final Set<String> VACIAS = Set.of(
            "de", "del", "la", "el", "los", "las", "un", "una", "y", "o", "en", "al",
            "por", "para", "con", "su", "sus", "lo", "que", "como"
    );

    public record Resultado(String conceptos, int alineados, int sinEncaje) {}

    /**
     * @param conceptosCrudos lista separada por comas tal como la etiquetó el generador
     * @param vocabulario     subtemas curados (los descartados NO deben venir en esta lista)
     */
    public static Resultado alinear(String conceptosCrudos, List<String> vocabulario) {
        if (conceptosCrudos == null || conceptosCrudos.isBlank()) {
            return new Resultado(conceptosCrudos, 0, 0);
        }
        // Sin vocabulario curado no hay contra qué alinear: se deja todo como venía.
        if (vocabulario == null || vocabulario.isEmpty()) {
            return new Resultado(conceptosCrudos, 0, 0);
        }

        // LinkedHashSet: si dos conceptos crudos caen en el mismo subtema, se guarda uno solo
        // y se conserva el orden en que aparecieron.
        Set<String> salida = new LinkedHashSet<>();
        int alineados = 0;
        int sinEncaje = 0;

        for (String crudo : conceptosCrudos.split(",")) {
            String limpio = crudo.strip();
            if (limpio.isEmpty()) continue;

            String encaje = mejorEncaje(limpio, vocabulario);
            if (encaje != null) {
                salida.add(encaje);
                alineados++;
            } else {
                salida.add(limpio);
                sinEncaje++;
            }
        }

        return new Resultado(String.join(", ", salida), alineados, sinEncaje);
    }

    /** El subtema curado que mejor representa a este concepto, o null si ninguno lo hace. */
    public static String mejorEncaje(String concepto, List<String> vocabulario) {
        String canonConcepto = NormalizadorConcepto.canonizar(concepto);
        if (canonConcepto.isEmpty()) return null;

        // 1) Mismo concepto ya normalizado: es el caso limpio y no necesita heurística.
        for (String tema : vocabulario) {
            if (NormalizadorConcepto.canonizar(tema).equals(canonConcepto)) return tema;
        }

        Set<String> palabrasConcepto = palabrasUtiles(canonConcepto);
        if (palabrasConcepto.isEmpty()) return null;

        String mejor = null;
        double mejorPuntaje = 0;

        for (String tema : vocabulario) {
            Set<String> palabrasTema = palabrasUtiles(NormalizadorConcepto.canonizar(tema));
            if (palabrasTema.isEmpty()) continue;

            // El núcleo del subtema es su primera palabra con contenido. Sin él, no hay encaje
            // por mucho que compartan adjetivos.
            String nucleo = palabrasTema.iterator().next();
            if (!palabrasConcepto.contains(nucleo)) continue;

            long comunes = palabrasConcepto.stream().filter(palabrasTema::contains).count();

            // Se divide por el MENOR de los dos: así "Abuso" (1 palabra) encaja en "Abuso de
            // poder" (2). Dividir por el mayor castigaría al concepto corto por ser corto, que
            // es justo el caso que se quiere absorber.
            double puntaje = comunes / (double) Math.min(palabrasConcepto.size(), palabrasTema.size());

            if (puntaje >= SOLAPE_MINIMO && puntaje > mejorPuntaje) {
                mejorPuntaje = puntaje;
                mejor = tema;
            }
        }
        return mejor;
    }

    private static Set<String> palabrasUtiles(String canonico) {
        List<String> palabras = new ArrayList<>(Arrays.asList(canonico.split("\\s+")));
        palabras.removeIf(p -> p.length() < 3 || VACIAS.contains(p));
        return new LinkedHashSet<>(palabras);
    }
}
