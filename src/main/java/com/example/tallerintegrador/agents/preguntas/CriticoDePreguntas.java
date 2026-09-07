package com.example.tallerintegrador.agents.preguntas;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;

/**
 * Contrato AiServices compartido por los tres criticos de preguntas.
 *
 * Mismo patron que AgenteDelComite: un solo contrato de entrada/salida, y tres instancias en
 * AiServicesConfig con system prompts distintos. Cada una mira UNA dimension.
 *
 * Por que tres criticos y no uno que lo revise todo: un unico revisor al que se le piden
 * cinco criterios a la vez tiende a fijarse en el primero y aprobar por inercia el resto. Es
 * el principio que aplica MAJ-EVAL, donde varios jueces con roles distintos evaluan
 * dimensiones separadas en lugar de un juez generalista.
 */
public interface CriticoDePreguntas {
    Result<VeredictoCritico> revisar(@UserMessage String instrucciones);
}
