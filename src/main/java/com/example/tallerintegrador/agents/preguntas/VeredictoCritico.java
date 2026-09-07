package com.example.tallerintegrador.agents.preguntas;

/**
 * Dictamen de un critico sobre una pregunta candidata, antes de que llegue al alumno.
 *
 * Salida estructurada, no texto libre: si el veredicto llegara como prosa habria que
 * parsearlo con expresiones regulares, que es el patron que ya causo problemas en
 * EvaluationOrchestratorAgent y que el juez de respuestas dejo atras.
 *
 * @param aprobada   si la pregunta puede mostrarse al alumno tal como esta
 * @param puntuacion 1 a 5 en la dimension que juzga este critico
 * @param problema   que falla, en una frase. Vacio si aprobada
 * @param correccion instruccion concreta para el generador si hay que rehacerla
 * @param evidencia  la parte del contexto o del enunciado en la que se apoya el dictamen;
 *                   obliga al critico a senalar algo concreto en vez de opinar en abstracto
 */
public record VeredictoCritico(
        boolean aprobada,
        int puntuacion,
        String problema,
        String correccion,
        String evidencia
) {}
