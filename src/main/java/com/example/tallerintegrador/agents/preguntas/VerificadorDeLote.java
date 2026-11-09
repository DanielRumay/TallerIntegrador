package com.example.tallerintegrador.agents.preguntas;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;

import java.util.List;

/**
 * Segunda lectura de un lote de reactivos, en UNA sola llamada.
 *
 * POR QUE EXISTE. El comite de tres criticos se apago porque demoraba demasiado, y con el se
 * fue la unica comprobacion de que la respuesta marcada fuera correcta. Esto recupera lo
 * indispensable al costo de una llamada: si la respuesta se sostiene en el material y que
 * proceso cognitivo exige de verdad el reactivo.
 *
 * LO QUE NO ES. Otro modelo opinando sobre un modelo no valida el nivel de Bloom: solo detecta
 * desajustes gruesos. La validacion real sigue siendo la clasificacion de una muestra por
 * docentes.
 */
public interface VerificadorDeLote {

    Result<VerificacionDeLote> verificar(@UserMessage String instrucciones);

    record VerificacionDeLote(List<VerificacionDeReactivo> verificaciones) {}

    /**
     * @param id                  indice del reactivo dentro del lote, empezando en 1
     * @param respuestaSustentada si la respuesta marcada es correcta segun el material
     * @param evidencia           fragmento del material en que se apoya
     * @param nivelBloomEstimado  proceso que exige realmente: Recordar a Crear
     */
    record VerificacionDeReactivo(int id, boolean respuestaSustentada, String evidencia, String nivelBloomEstimado) {}
}
