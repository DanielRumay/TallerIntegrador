package com.example.tallerintegrador.agents.judge;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * Contrato AiServices del juez. Toda la lógica de negocio (qué reglas aplican según el
 * tipo de pregunta, cómo se escala el puntaje) se queda en AgentJudgeAgent — esta interfaz
 * solo declara el transporte: un prompt de entrada, un VeredictoJuez tipado de salida.
 *
 * Se devuelve Result<VeredictoJuez> en vez de VeredictoJuez a secas para conservar
 * tokenUsage(): la telemetría de coste/latencia que ya existía (ver
 * AgentJudgeAgent.registrarTelemetria) no se pierde con el cambio de transporte.
 */
public interface JuezDeRespuestaService {

    @SystemMessage("""
            Eres el motor de evaluación de un juez automático educativo. Completa
            ÚNICAMENTE los campos del esquema estructurado indicado; no agregues texto,
            explicación ni comentario fuera de esos campos.
            """)
    Result<VeredictoJuez> evaluar(@UserMessage String instruccionesYContexto);
}
