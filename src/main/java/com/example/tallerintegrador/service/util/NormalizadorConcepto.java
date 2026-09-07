package com.example.tallerintegrador.service.util;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Forma canónica del nombre de un concepto.
 *
 * El problema que resuelve es real y ya está ocurriendo: `conceptos` lo genera el modelo en
 * texto libre, así que "Fotosíntesis", "La fotosíntesis", "fotosintesis " y "LAS
 * FOTOSÍNTESIS" crean cuatro filas distintas en `dominio_concepto_alumno`. El BKT las trata
 * como cuatro conceptos independientes: la evidencia del alumno queda repartida en cuatro
 * trozos, ninguno alcanza el umbral de dominio, y el mapa de conocimiento muestra cuatro
 * puntos débiles donde hay uno solo.
 *
 * Importa arreglarlo ANTES de la intervención, no después: los datos que entren mal ahora
 * entran mal para siempre, y reprocesarlos implicaría reescribir el historial sobre el que
 * se calcularon las probabilidades.
 *
 * DECISIÓN DE DISEÑO: la normalización es determinista y no usa el modelo ni embeddings.
 *
 * Se consideró agrupar por similitud de embeddings, que además uniría "fotosíntesis" con
 * "proceso fotosintético". Se descartó por ahora: fusionar dos conceptos que en realidad son
 * distintos corrompe la medida de dominio sin dejar rastro visible, y un componente que
 * puede equivocarse en silencio es exactamente lo que esta tesis no necesita más. Esta
 * versión es una función pura: se puede probar de forma exhaustiva y explicar a un jurado
 * en una frase. La agrupación semántica queda como extensión, con revisión docente.
 */
public final class NormalizadorConcepto {

    private NormalizadorConcepto() {}

    /** Artículos y determinantes iniciales que el modelo antepone de forma inconsistente. */
    private static final List<String> PREFIJOS = List.of(
            "el ", "la ", "los ", "las ", "un ", "una ", "unos ", "unas ",
            "del ", "de la ", "de los ", "de las ", "de ");

    /**
     * @return forma canónica en minúsculas, sin tildes, sin artículo inicial y con espacios
     *         colapsados; o cadena vacía si la entrada no aporta nada.
     */
    public static String canonizar(String bruto) {
        if (bruto == null) return "";

        String texto = bruto.trim().toLowerCase(Locale.ROOT);
        if (texto.isEmpty()) return "";

        // Quita la puntuación de los extremos: el modelo a veces devuelve "fotosíntesis." o
        // deja comillas del JSON.
        texto = texto.replaceAll("^[\\p{Punct}\\s]+", "").replaceAll("[\\p{Punct}\\s]+$", "");

        // Descompone y elimina los diacríticos: "fotosíntesis" y "fotosintesis" deben coincidir.
        texto = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        // La eñe se pierde con el paso anterior ("años" -> "anos"). Es aceptable y además
        // consistente: lo que importa es que dos escrituras del mismo concepto colisionen,
        // no que el resultado se lea bien. El nombre legible se conserva aparte.

        texto = texto.replaceAll("\\s+", " ").trim();

        // Un solo artículo inicial. No se repite el barrido: "la la" no es un caso real y
        // quitar artículos en cadena podría comerse parte del nombre.
        for (String prefijo : PREFIJOS) {
            if (texto.startsWith(prefijo) && texto.length() > prefijo.length()) {
                texto = texto.substring(prefijo.length()).trim();
                break;
            }
        }

        return texto;
    }

    /**
     * Forma legible para mostrar al alumno: la primera letra en mayúscula, conservando las
     * tildes del texto original. La canónica sirve para agrupar; ésta, para leer.
     */
    public static String paraMostrar(String bruto) {
        if (bruto == null) return "";
        String texto = bruto.trim().replaceAll("\\s+", " ");
        if (texto.isEmpty()) return "";

        for (String prefijo : PREFIJOS) {
            if (texto.toLowerCase(Locale.ROOT).startsWith(prefijo) && texto.length() > prefijo.length()) {
                texto = texto.substring(prefijo.length()).trim();
                break;
            }
        }
        if (texto.isEmpty()) return "";
        return texto.substring(0, 1).toUpperCase(Locale.ROOT) + texto.substring(1);
    }
}
