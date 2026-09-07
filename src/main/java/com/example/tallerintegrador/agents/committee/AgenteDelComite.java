package com.example.tallerintegrador.agents.committee;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;

/**
 * Contrato AiServices compartido por los tres agentes que deliberan (Evaluador,
 * Psicopedagogo, Adaptación de Evaluaciones). Cada rol se instancia por separado en
 * AiServicesConfig con su propio @SystemMessage y las mismas HerramientasComite — mismo
 * contrato de entrada/salida, distinto punto de vista.
 */
public interface AgenteDelComite {
    Result<Postura> deliberar(@UserMessage String instrucciones);
}
