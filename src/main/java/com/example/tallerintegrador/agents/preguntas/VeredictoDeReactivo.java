package com.example.tallerintegrador.agents.preguntas;

/**
 * Dictamen de un critico sobre UN reactivo dentro de una revision en lote.
 *
 * Es el mismo contenido que VeredictoCritico mas dos campos que solo tienen sentido al
 * revisar varios reactivos de una vez:
 *
 *   - `id` ata el veredicto a su reactivo. Sin el no hay forma de saber cual de las diez
 *     preguntas del lote fue la rechazada.
 *   - `nivelLecturaEstimado` lo rellena unicamente el critico pedagogico. Es la estimacion
 *     del grado escolar al que corresponde el vocabulario del enunciado, y sustituye como
 *     indicador principal a las formulas de legibilidad clasicas, pensadas para textos de
 *     100 palabras o mas y no para enunciados de veinte. Los otros dos criticos lo dejan
 *     vacio.
 *
 * @param id                   indice del reactivo dentro del lote, empezando en 1
 * @param aprobada             si puede mostrarse al alumno tal como esta
 * @param puntuacion           1 a 5 en la dimension que juzga este critico
 * @param problema             que falla, en una frase. Vacio si aprobada
 * @param correccion           instruccion concreta para corregirla o regenerarla
 * @param evidencia            fragmento del material o del enunciado que sustenta el dictamen
 * @param nivelLecturaEstimado grado escolar estimado; solo el critico pedagogico lo completa
 */
public record VeredictoDeReactivo(
        int id,
        boolean aprobada,
        int puntuacion,
        String problema,
        String correccion,
        String evidencia,
        String nivelLecturaEstimado
) {}
