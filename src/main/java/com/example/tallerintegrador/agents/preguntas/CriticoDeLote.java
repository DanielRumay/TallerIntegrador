package com.example.tallerintegrador.agents.preguntas;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;

/**
 * Contrato AiServices de los criticos cuando revisan un lote de reactivos.
 *
 * Mismo patron que CriticoDePreguntas — un contrato, tres instancias en AiServicesConfig con
 * system prompts distintos — pero devolviendo una lista de veredictos en vez de uno solo.
 *
 * Se mantienen ambos contratos a proposito: el de uno en uno sigue siendo util para revisar
 * un reactivo suelto (por ejemplo, tras una correccion de estilo) y es el que permite medir
 * si juzgar en lote degrada el veredicto, comparando ambas modalidades sobre los mismos
 * reactivos.
 */
public interface CriticoDeLote {
    Result<DictamenDeLote> revisar(@UserMessage String instrucciones);
}
