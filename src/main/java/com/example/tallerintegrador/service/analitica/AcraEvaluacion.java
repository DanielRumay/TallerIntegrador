package com.example.tallerintegrador.service.analitica;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Escala ACRA — Versión Reducida (20 ítems)
 * Basada en: Román, J.M. y Gallego, S. (1994). ACRA: Escalas de Estrategias de Aprendizaje.
 *
 * Escalas:
 *   I   – Adquisición de información       (ítems acra_01 ... acra_05)
 *   II  – Codificación de información      (ítems acra_06 ... acra_10)
 *   III – Recuperación de información      (ítems acra_11 ... acra_15)
 *   IV  – Apoyo al procesamiento           (ítems acra_16 ... acra_20)
 *
 * Respuesta Likert de 4 puntos:
 *   A = Nunca o casi nunca        → 1 punto
 *   B = Pocas veces               → 2 puntos
 *   C = Bastantes veces           → 3 puntos
 *   D = Siempre o casi siempre    → 4 puntos
 *
 * Puntuación total (20 ítems × 4 pts = 80 pts máximo):
 *   20 – 39 pts → PRINCIPIANTE
 *   40 – 59 pts → INTERMEDIO
 *   60 – 80 pts → AVANZADO
 */
public final class AcraEvaluacion {

    private AcraEvaluacion() {}

    /** Opciones Likert estándar para todos los ítems. */
    public static final List<Map<String, Object>> OPCIONES_LIKERT = List.of(
            Map.of("valor", "1", "etiqueta", "A – Nunca o casi nunca"),
            Map.of("valor", "2", "etiqueta", "B – Pocas veces"),
            Map.of("valor", "3", "etiqueta", "C – Bastantes veces"),
            Map.of("valor", "4", "etiqueta", "D – Siempre o casi siempre")
    );

    /** Retorna la lista completa de los 20 ítems ACRA formateados para el frontend. */
    public static List<Map<String, Object>> getItems() {
        return List.of(
                // ─────────────────────────────────────────────────────────────
                // ESCALA I – ADQUISICIÓN DE INFORMACIÓN
                // ─────────────────────────────────────────────────────────────
                item("acra_01", "I",
                        "Cuando estudio, leo en voz alta los puntos más importantes del tema para " +
                        "retenerlos mejor en mi memoria."),
                item("acra_02", "I",
                        "Antes de comenzar a estudiar un tema, realizo una lectura rápida del " +
                        "material para hacerme una idea general de su contenido."),
                item("acra_03", "I",
                        "Subrayo o resalto las palabras, datos o ideas que me parecen más " +
                        "importantes mientras leo."),
                item("acra_04", "I",
                        "Repito mentalmente o en voz baja la información que necesito aprender para " +
                        "fijarla en mi memoria."),
                item("acra_05", "I",
                        "Cuando encuentro palabras o conceptos que no entiendo, los busco en el " +
                        "diccionario o en otras fuentes antes de continuar leyendo."),

                // ─────────────────────────────────────────────────────────────
                // ESCALA II – CODIFICACIÓN DE INFORMACIÓN
                // ─────────────────────────────────────────────────────────────
                item("acra_06", "II",
                        "Para recordar la información, elaboro esquemas, mapas conceptuales o " +
                        "resúmenes que me ayudan a organizar las ideas."),
                item("acra_07", "II",
                        "Cuando estudio un tema nuevo, intento relacionarlo con cosas que ya sé o " +
                        "con experiencias propias para entenderlo mejor."),
                item("acra_08", "II",
                        "Uso imágenes mentales, dibujos o representaciones visuales para recordar " +
                        "información difícil o abstracta."),
                item("acra_09", "II",
                        "Creo mis propios ejemplos, analogías o comparaciones para comprender " +
                        "conceptos que me resultan complicados."),
                item("acra_10", "II",
                        "Elaboro fichas o notas propias con los puntos clave del tema para repasar " +
                        "después de estudiar."),

                // ─────────────────────────────────────────────────────────────
                // ESCALA III – RECUPERACIÓN DE INFORMACIÓN
                // ─────────────────────────────────────────────────────────────
                item("acra_11", "III",
                        "Antes de un examen, me autoevalúo haciéndome preguntas sobre el tema para " +
                        "comprobar lo que sé y lo que me falta por repasar."),
                item("acra_12", "III",
                        "Cuando tengo que responder una pregunta en un examen, busco en mi mente " +
                        "pistas o contextos que me ayuden a recordar la respuesta."),
                item("acra_13", "III",
                        "Repaso mis apuntes o resúmenes poco antes del examen para asegurarme de " +
                        "recordar la información más importante."),
                item("acra_14", "III",
                        "Al responder un examen, utilizo palabras clave, esquemas mentales o listas " +
                        "que estudié para organizar mis respuestas."),
                item("acra_15", "III",
                        "Después de estudiar, intento recordar los contenidos sin mirar el material " +
                        "para verificar qué tan bien los retuve."),

                // ─────────────────────────────────────────────────────────────
                // ESCALA IV – APOYO AL PROCESAMIENTO
                // ─────────────────────────────────────────────────────────────
                item("acra_16", "IV",
                        "Me fijo objetivos claros al comenzar a estudiar y compruebo al final si " +
                        "los alcancé."),
                item("acra_17", "IV",
                        "Cuando me doy cuenta de que no estoy entendiendo algo, cambio mi forma de " +
                        "estudiar o busco otra fuente de información."),
                item("acra_18", "IV",
                        "Me siento motivado/a para estudiar porque creo que aprender me será útil " +
                        "en el futuro."),
                item("acra_19", "IV",
                        "Controlo mi atención durante el estudio y evito distracciones para " +
                        "mantener la concentración."),
                item("acra_20", "IV",
                        "Después de realizar una evaluación, reflexiono sobre mis errores para " +
                        "mejorar mis estrategias de estudio en el futuro.")
        );
    }

    /**
     * Retorna el JSON completo de la prueba ACRA con metadatos listos para el frontend.
     */
    public static Map<String, Object> buildResponse() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tipo_evaluacion", "DIAGNOSTICA");
        response.put("instrumento", "ACRA");
        response.put("descripcion",
                "Escala de Estrategias de Aprendizaje (Versión Reducida). " +
                "Indica con qué frecuencia realizas cada acción al estudiar.");
        response.put("escala_respuesta", "A=Nunca/1pt · B=Pocas veces/2pts · C=Bastantes veces/3pts · D=Siempre/4pts");
        response.put("tipo_pregunta", "ACRA_LIKERT");
        response.put("opciones_likert", OPCIONES_LIKERT);
        response.put("items", getItems());
        response.put("total_items", 20);
        response.put("puntaje_maximo", 80);
        return response;
    }

    /**
     * Calcula el puntaje total ACRA y retorna el nivel de conocimiento correspondiente.
     * Espera una lista de valores enteros 1-4 para los 20 ítems.
     */
    public static String calcularNivel(List<Integer> respuestas) {
        int total = respuestas.stream().mapToInt(Integer::intValue).sum();
        if (total >= 60) return "AVANZADO";
        if (total >= 40) return "INTERMEDIO";
        return "PRINCIPIANTE";
    }

    /** Calcula puntaje por escala para el reporte detallado. */
    public static Map<String, Object> calcularPuntajePorEscala(List<Integer> respuestas) {
        if (respuestas == null || respuestas.size() < 20) {
            return Map.of("error", "Se necesitan exactamente 20 respuestas");
        }
        int escala1 = respuestas.subList(0, 5).stream().mapToInt(Integer::intValue).sum();
        int escala2 = respuestas.subList(5, 10).stream().mapToInt(Integer::intValue).sum();
        int escala3 = respuestas.subList(10, 15).stream().mapToInt(Integer::intValue).sum();
        int escala4 = respuestas.subList(15, 20).stream().mapToInt(Integer::intValue).sum();
        int total = escala1 + escala2 + escala3 + escala4;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("escala_I_adquisicion",   Map.of("puntaje", escala1, "max", 20));
        result.put("escala_II_codificacion",  Map.of("puntaje", escala2, "max", 20));
        result.put("escala_III_recuperacion", Map.of("puntaje", escala3, "max", 20));
        result.put("escala_IV_apoyo",         Map.of("puntaje", escala4, "max", 20));
        result.put("total",                   total);
        result.put("nivel_determinado",       calcularNivel(respuestas));
        return result;
    }

    // ────────────────────────────────────────────
    // Helper privado
    // ────────────────────────────────────────────
    private static Map<String, Object> item(String id, String escala, String enunciado) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("escala", escala);
        m.put("enunciado", enunciado);
        m.put("tipo", "ACRA_LIKERT");
        return m;
    }
}
