package com.example.tallerintegrador.service.ia;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A quien se le escribe: grado, edad tipica y ciclo del Curriculo Nacional (CNEB).
 *
 * POR QUE EXISTE. Cada prompt tenia la edad escrita a mano y no coincidian entre si: Aria decia
 * "2do de secundaria (13-14 anos)", el corrector "12 a 17" y el juez de la practica se presentaba
 * como "profesor de Quinto Grado". El grado ya estaba en la base (Curso y Usuario), pero ningun
 * prompt lo leia. Este registro lo convierte en una sola instruccion que comparten todos.
 *
 * POR QUE CICLO Y NO EDAD. No se guarda la fecha de nacimiento de menores (Ley 29733, minimizacion
 * de datos) ni hace falta: el CNEB organiza los estandares por ciclo (VI = 1.° y 2.° de
 * secundaria, VII = 3.° a 5.°) y la edad tipica se deriva del grado.
 *
 * POR QUE MEDIR Y NO SOLO PEDIR. Pedirle al modelo "escribe para un chico de 13 anos" no basta: los
 * LLM tienden a un rango de legibilidad propio y se adaptan mal a la edad solicitada (Rooein et al.,
 * 2023). Por eso el perfil trae tambien un umbral INFLESZ que el control de calidad verifica despues.
 *
 * DOS EJES SEPARADOS. La audiencia cambia el LENGUAJE (vocabulario, largo de frase, tono); la
 * exigencia cognitiva la sigue fijando el nivel de Bloom del alumno. Un alumno avanzado de 2.°
 * recibe preguntas de Evaluar redactadas para alguien de 13 anos, no para uno de 17.
 *
 * @param grado          numero de grado dentro de su nivel (1..6), o null si no se conoce
 * @param secundaria     true si el grado es de secundaria
 * @param ciclo          ciclo del CNEB ("III".."VII"), o null si no se conoce
 * @param edadMinima     edad tipica al iniciar el grado
 * @param edadMaxima     edad tipica al terminarlo
 * @param umbralInflesz  indice de Szigriszt minimo esperado para el ciclo, o null para usar el
 *                       umbral configurado
 * @param marcoCurricular bloque del CNEB para el area y el grado (ver MarcoCneb), o vacio
 */
public record PerfilAudiencia(Integer grado, boolean secundaria, String ciclo,
                              int edadMinima, int edadMaxima, Double umbralInflesz, String marcoCurricular) {

    public PerfilAudiencia(Integer grado, boolean secundaria, String ciclo,
                           int edadMinima, int edadMaxima, Double umbralInflesz) {
        this(grado, secundaria, ciclo, edadMinima, edadMaxima, umbralInflesz, "");
    }

    public PerfilAudiencia conMarco(String marco) {
        return new PerfilAudiencia(grado, secundaria, ciclo, edadMinima, edadMaxima, umbralInflesz, marco == null ? "" : marco);
    }

    /** Largo orientativo de oracion por grado: criterio del equipo, a calibrar con la medicion. */
    private static final int[] PALABRAS_PRIMARIA = {10, 12, 14, 15, 16, 18};
    private static final int[] PALABRAS_SECUNDARIA = {18, 20, 22, 24, 25};

    private int palabrasPorOracion() {
        return (secundaria ? PALABRAS_SECUNDARIA : PALABRAS_PRIMARIA)[grado - 1];
    }

    /**
     * Umbrales por ciclo, tomados de las franjas de la escala INFLESZ (Barrio-Cantalejo et al.,
     * 2008): 65-80 "bastante facil" (primaria), 55-65 "normal" (secundaria obligatoria), 40-55
     * "algo dificil" (bachillerato). Para el ciclo VII se toma 50, dentro de la franja de 40-55,
     * para dejar margen a la terminologia que el propio curso exige. Son puntos de partida a
     * calibrar con la revision docente, no valores validados para enunciados cortos.
     */
    static final double UMBRAL_PRIMARIA = 65;
    static final double UMBRAL_CICLO_VI = 55;
    static final double UMBRAL_CICLO_VII = 50;

    /** Grado desconocido: el comportamiento anterior (toda la secundaria, umbral configurado). */
    public static final PerfilAudiencia GENERAL = new PerfilAudiencia(null, true, null, 12, 17, null);

    private static final Pattern NUMERO = Pattern.compile("([1-6])");
    private static final String[] ORDINALES = {"primer", "segundo", "tercer", "cuarto", "quinto", "sexto"};

    /**
     * Interpreta el nombre del grado tal como se guarda en la tabla grado ("2do Año de
     * Secundaria", "5to primaria", "Tercero de secundaria"...). Si no reconoce el numero devuelve
     * GENERAL: es preferible no adaptar a adaptar al grado equivocado.
     */
    public static PerfilAudiencia desdeNombreGrado(String nombreGrado) {
        if (nombreGrado == null || nombreGrado.isBlank()) return GENERAL;
        String n = normalizar(nombreGrado);
        boolean primaria = n.contains("prim");

        Integer grado = null;
        Matcher m = NUMERO.matcher(n);
        if (m.find()) {
            grado = Integer.parseInt(m.group(1));
        } else {
            for (int i = 0; i < ORDINALES.length; i++) {
                if (n.contains(ORDINALES[i])) { grado = i + 1; break; }
            }
        }
        if (grado == null) return GENERAL;
        return primaria ? deGradoPrimaria(grado) : deGradoSecundaria(grado);
    }

    static PerfilAudiencia deGradoSecundaria(int grado) {
        if (grado < 1 || grado > 5) return GENERAL;
        // 1.° de secundaria se cursa tipicamente con 12 a 13 anos
        int edad = 11 + grado;
        boolean cicloVI = grado <= 2;
        return new PerfilAudiencia(grado, true, cicloVI ? "VI" : "VII", edad, edad + 1,
                cicloVI ? UMBRAL_CICLO_VI : UMBRAL_CICLO_VII);
    }

    static PerfilAudiencia deGradoPrimaria(int grado) {
        if (grado < 1 || grado > 6) return GENERAL;
        int edad = 5 + grado;
        String ciclo = grado <= 2 ? "III" : grado <= 4 ? "IV" : "V";
        return new PerfilAudiencia(grado, false, ciclo, edad, edad + 1, UMBRAL_PRIMARIA);
    }

    public boolean esConocido() {
        return grado != null;
    }

    /** Para insertar en una frase: "un estudiante de 2.° de secundaria (13 a 14 años)". */
    public String descripcion() {
        if (!esConocido()) return "un estudiante de secundaria (12 a 17 años)";
        return "un estudiante de %d.° de %s (%d a %d años)".formatted(
                grado, secundaria ? "secundaria" : "primaria", edadMinima, edadMaxima);
    }

    /** Umbral efectivo: el del ciclo si se conoce el grado; si no, el configurado. */
    public double umbralInfleszO(double porDefecto) {
        return umbralInflesz != null ? umbralInflesz : porDefecto;
    }

    /**
     * Bloque de instrucciones para el prompt. Los rasgos de cada ciclo resumen los estandares
     * de lectura del CNEB (el VII habla de "estructuras complejas y vocabulario variado y
     * especializado"); los largos de frase son criterio del equipo, a calibrar con la medicion.
     */
    public String instruccion() {
        String rasgos;
        if (!esConocido()) {
            rasgos = """
                    - Vocabulario claro y propio de la secundaria peruana; evita el léxico universitario.
                    - Oraciones directas, sin subordinadas encadenadas.""";
        } else if ("III".equals(ciclo)) {
            rasgos = """
                    - Palabras muy conocidas de la casa y la escuela; nada de términos abstractos.
                    - Oraciones de unas %d palabras como máximo, con una sola idea y una sola tarea.
                    - Siempre un caso concreto y cercano.""".formatted(palabrasPorOracion());
        } else if ("IV".equals(ciclo)) {
            rasgos = """
                    - Palabras de uso diario; si un término nuevo es el contenido evaluado, explícalo con un ejemplo cercano.
                    - Oraciones de unas %d palabras como máximo y una sola idea cada una.
                    - Nada de abstracciones sin un caso concreto.""".formatted(palabrasPorOracion());
        } else if (!secundaria) {
            rasgos = """
                    - Palabras de uso frecuente; los términos del área solo si son el contenido evaluado.
                    - Oraciones de unas %d palabras como máximo y una sola idea cada una.
                    - Las ideas generales van acompañadas de un ejemplo.""".formatted(palabrasPorOracion());
        } else if ("VI".equals(ciclo)) {
            rasgos = """
                    - Vocabulario cotidiano; los términos técnicos solo si son el contenido que se evalúa.
                    - Oraciones de una sola idea, de unas %d palabras como máximo, sin subordinadas encadenadas.
                    - Ejemplos y situaciones cercanas a la vida de un adolescente de esa edad.""".formatted(palabrasPorOracion());
        } else {
            rasgos = """
                    - Vocabulario variado; puedes usar la terminología especializada que emplea el material.
                    - Oraciones que pueden relacionar dos ideas, de unas %d palabras como máximo.
                    - Se admiten situaciones hipotéticas y contrastes entre posturas.""".formatted(palabrasPorOracion());
        }
        String cabecera = esConocido()
                ? "AUDIENCIA: %s, ciclo %s del Currículo Nacional.".formatted(capitalizar(descripcion()), ciclo)
                : "AUDIENCIA: estudiantes de secundaria (12 a 17 años); el grado exacto no se conoce.";
        return cabecera + "\n" + rasgos + "\n"
                + "- La audiencia cambia el LENGUAJE, no la exigencia: respeta el nivel de Bloom solicitado."
                + (marcoCurricular == null || marcoCurricular.isBlank() ? "" : "\n" + marcoCurricular);
    }

    private static String capitalizar(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String normalizar(String s) {
        String sinTildes = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT);
    }
}
