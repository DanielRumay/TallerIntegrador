package com.example.tallerintegrador.agents.preguntas;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;

/**
 * Repara la redaccion de un reactivo rechazado por FORMA, en vez de tirarlo y regenerarlo.
 *
 * POR QUE EXISTE. Hasta ahora, un reactivo cuyo contenido era correcto pero cuya redaccion
 * fallaba se descartaba entero: eso cuesta una generacion completa mas otra ronda de los tres
 * criticos. Corregir el enunciado cuesta una llamada corta y recupera el reactivo.
 *
 * POR QUE VA DESPUES DE LOS CRITICOS Y NO ANTES. El comite ya devuelve `problema` e
 * `instruccionesDeCorreccion`: el corrector trabaja con una instruccion concreta en lugar de
 * adivinar que arreglar. Reescribir antes significaria pagar por pulir reactivos que nadie
 * objeto, y maquillar reactivos que deberian morir por contenido.
 *
 * LO QUE NO PUEDE TOCAR. La respuesta correcta, cual de las opciones lo es, las palabras
 * marcadas en deteccion de errores, el nivel de Bloom y el concepto evaluado. La instruccion
 * lo dice, pero no se confia en ella: CorreccionEstiloGuard lo verifica despues, igual que
 * EnunciadoGuard no confia en que el generador respete la prohibicion de citar el documento.
 */
public interface CorrectorEstiloAgent {
    Result<ReactivoReescrito> corregir(@UserMessage String instrucciones);
}
