package com.example.tallerintegrador.agents.preguntas;

import java.util.List;

/**
 * Reactivo con la redaccion corregida.
 *
 * `cambios` no es decorativo: obliga al modelo a declarar que toco, y esa declaracion se
 * guarda en la telemetria. Sin ella no habria forma de auditar despues por que una pregunta
 * mostrada al alumno no coincide palabra por palabra con la que genero el modelo original.
 *
 * `opciones` viene vacia en las preguntas abiertas y en verdadero/falso; en opcion multiple
 * debe traer exactamente las mismas alternativas, en el mismo orden, con la misma opcion
 * correcta. Lo verifica CorreccionEstiloGuard.
 *
 * @param enunciado enunciado reescrito
 * @param opciones  alternativas reescritas, en el mismo orden que las originales
 * @param cambios   que se modifico, en una frase
 */
public record ReactivoReescrito(
        String enunciado,
        List<String> opciones,
        String cambios
) {}
