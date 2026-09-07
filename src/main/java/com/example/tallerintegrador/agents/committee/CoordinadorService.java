package com.example.tallerintegrador.agents.committee;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;

/** Contrato AiServices del Coordinador: cierra el debate con una decisión, no una postura más. */
public interface CoordinadorService {
    Result<ConsensoComite> decidir(@UserMessage String instrucciones);
}
