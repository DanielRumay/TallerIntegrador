package com.example.tallerintegrador.config;

import com.example.tallerintegrador.agents.committee.AgenteDelComite;
import com.example.tallerintegrador.agents.committee.CoordinadorService;
import com.example.tallerintegrador.agents.committee.HerramientasComite;
import com.example.tallerintegrador.agents.judge.JuezDeRespuestaService;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Construye los servicios de IA con salida estructurada nativa (LangChain4j AiServices).
 *
 * Hasta esta versión, LangChain4j se usaba únicamente como cliente de Qdrant; toda la
 * generación real pasaba por com.google.genai.Client en crudo, con el modelo devolviendo
 * texto libre que un parser de regex intentaba rescatar cuando el JSON salía mal formado
 * (ver AgentJudgeAgent, versión anterior a este cambio). Aquí se declara explícitamente
 * que el modelo soporta RESPONSE_FORMAT_JSON_SCHEMA: con eso, LangChain4j genera el
 * esquema JSON a partir del propio record Java de retorno y Gemini lo cumple a nivel de
 * API, no de instrucción de prompt — el parser de rescate deja de ser necesario porque la
 * fuente del problema desaparece, no porque se blindó mejor el fallback.
 */
@Configuration
@RequiredArgsConstructor
public class AiServicesConfig {

    @Value("${langchain4j.google-ai-gemini.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.google-ai-gemini.chat-model.model-name:gemini-3.1-flash-lite}")
    private String modelName;

    /**
     * Modelo con salida estructurada declarada. Deliberadamente separado del ChatModel de
     * LangChain4jVerification: ese es solo un chequeo de salud; este es el que de verdad
     * hace trabajo de producción.
     */
    @Bean
    public ChatModel structuredOutputChatModel() {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.2) // el juez y el comité necesitan reproducibilidad, no variedad creativa
                .maxRetries(3)
                .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
                .build();
    }

    @Bean
    public JuezDeRespuestaService juezDeRespuestaService(ChatModel structuredOutputChatModel) {
        return AiServices.builder(JuezDeRespuestaService.class)
                .chatModel(structuredOutputChatModel)
                .build();
    }

    // ── Comité de PREGUNTAS: revisa cada reactivo antes de que lo vea el alumno. ─────
    //
    // Basado en EduAgentQG (arXiv:2511.11635), flujo multi-agente con ciclo
    // generar-evaluar-regenerar y evaluación consciente del objetivo de aprendizaje. La
    // separación en críticos por dimensión sigue el principio de MAJ-EVAL: varios jueces con
    // roles distintos evaluando aspectos separados, en vez de un juez generalista que tiende
    // a fijarse en el primer criterio y aprobar el resto por inercia.
    //
    // Sin herramientas a propósito: un crítico debe juzgar SOLO lo que tiene delante. Si
    // pudiera consultar el historial del alumno, mezclaría "esta pregunta está bien hecha"
    // con "a este alumno le conviene", que son dos juicios distintos.

    @Bean
    public com.example.tallerintegrador.agents.preguntas.CriticoDePreguntas criticoContenido(
            ChatModel structuredOutputChatModel) {
        return AiServices.builder(com.example.tallerintegrador.agents.preguntas.CriticoDePreguntas.class)
                .chatModel(structuredOutputChatModel)
                .systemMessage("""
                    Eres el [Crítico de Contenido]. Juzgas UNA sola cosa: si la pregunta se puede
                    responder con el material de estudio que se te entrega, y si la respuesta
                    marcada como correcta lo es de verdad según ese material.

                    RECHAZA si: la respuesta no aparece ni se deduce del material; la respuesta
                    marcada es incorrecta o incompleta; la pregunta exige datos externos que el
                    alumno no tiene.

                    NO juzgues la redacción ni la dificultad: de eso se ocupan otros críticos.
                    En `evidencia` cita el fragmento exacto del material que sustenta tu
                    dictamen. Si no puedes citar nada concreto, la pregunta NO está apoyada en
                    el material y debes rechazarla.
                    """)
                .build();
    }

    @Bean
    public com.example.tallerintegrador.agents.preguntas.CriticoDePreguntas criticoPedagogico(
            ChatModel structuredOutputChatModel) {
        return AiServices.builder(com.example.tallerintegrador.agents.preguntas.CriticoDePreguntas.class)
                .chatModel(structuredOutputChatModel)
                .systemMessage("""
                    Eres el [Crítico Pedagógico]. Juzgas UNA sola cosa: si la pregunta exige de
                    verdad el nivel cognitivo solicitado de la Taxonomía Revisada de Bloom, y si
                    es apropiada para un estudiante de 2do de secundaria (13-14 años).

                    Sé estricto con el nivel: una pregunta que pide "analizar" pero se responde
                    localizando un dato en el texto es de RECORDAR disfrazada, y debes
                    rechazarla. Es el error más común y el más difícil de detectar.

                    RECHAZA también si el vocabulario es de nivel universitario, si la pregunta
                    tiene varias preguntas dentro, o si es ambigua.

                    NO juzgues si la respuesta es correcta: de eso se ocupa otro crítico.
                    """)
                .build();
    }

    @Bean
    public com.example.tallerintegrador.agents.preguntas.CriticoDePreguntas criticoForma(
            ChatModel structuredOutputChatModel) {
        return AiServices.builder(com.example.tallerintegrador.agents.preguntas.CriticoDePreguntas.class)
                .chatModel(structuredOutputChatModel)
                .systemMessage("""
                    Eres el [Crítico de Forma]. Juzgas UNA sola cosa: la construcción del
                    reactivo.

                    RECHAZA si: hay distractores absurdos o descartables sin saber el tema; la
                    opción correcta es notablemente más larga o más detallada que las demás
                    (pista involuntaria clásica); hay alternativas que se solapan o que son
                    equivalentes entre sí; se usan "todas las anteriores" o "ninguna de las
                    anteriores"; el enunciado está en negativo sin destacarlo; o el enunciado no
                    se sostiene por sí solo.

                    En preguntas abiertas, verifica que el enunciado deje claro qué se espera y
                    de qué extensión.

                    NO juzgues si la respuesta es correcta ni el nivel cognitivo: otros críticos
                    se ocupan de eso.
                    """)
                .build();
    }

    // ── Comité de agentes: una instancia de AiServices por rol, mismo contrato de
    //    salida (Postura), mismas herramientas, distinto system prompt. ──────────────

    @Bean
    public AgenteDelComite agenteEvaluador(ChatModel structuredOutputChatModel, HerramientasComite herramientas) {
        return AiServices.builder(AgenteDelComite.class)
                .chatModel(structuredOutputChatModel)
                .tools(herramientas)
                .systemMessage("""
                    Eres el [Agente Evaluador] de un comité educativo virtual. Tu enfoque es
                    puramente cuantitativo y estadístico. ANTES de opinar, DEBES consultar las
                    herramientas disponibles para obtener el historial real de notas del alumno
                    — no asumas ni inventes cifras que no hayas consultado. Tu "evidencia" debe
                    citar los valores concretos que las herramientas te devolvieron.
                    """)
                .build();
    }

    @Bean
    public AgenteDelComite agentePsicopedagogo(ChatModel structuredOutputChatModel, HerramientasComite herramientas) {
        return AiServices.builder(AgenteDelComite.class)
                .chatModel(structuredOutputChatModel)
                .tools(herramientas)
                .systemMessage("""
                    Eres el [Agente Psicopedagogo] de un comité educativo virtual. Tu enfoque es
                    cualitativo y cognitivo. ANTES de opinar, DEBES consultar el perfil del
                    alumno y sus preguntas falladas recientes mediante las herramientas
                    disponibles — no generalices sin haber consultado datos reales de este
                    alumno específico.
                    """)
                .build();
    }

    @Bean
    public AgenteDelComite agenteAdaptacion(ChatModel structuredOutputChatModel, HerramientasComite herramientas) {
        return AiServices.builder(AgenteDelComite.class)
                .chatModel(structuredOutputChatModel)
                .tools(herramientas)
                .systemMessage("""
                    Eres el [Agente de Adaptación de Evaluaciones] de un comité educativo
                    virtual. Tu enfoque es la Taxonomía de Bloom y el ajuste de dificultad.
                    ANTES de opinar, consulta el historial de notas del alumno para fundamentar
                    si conviene subir, mantener o bajar el nivel de dificultad de las próximas
                    evaluaciones.
                    """)
                .build();
    }

    @Bean
    public CoordinadorService coordinadorService(ChatModel structuredOutputChatModel, HerramientasComite herramientas) {
        return AiServices.builder(CoordinadorService.class)
                .chatModel(structuredOutputChatModel)
                .tools(herramientas)
                .systemMessage("""
                    Eres el [Agente Coordinador] de un comité educativo virtual. Tu enfoque es
                    integrador, mediador y decisivo. Recibes las posturas ya emitidas por tus
                    colegas y debes sintetizarlas en una decisión final sobre el nivel del
                    alumno, citando la evidencia concreta que ellos aportaron. Si la evidencia
                    de tus colegas es insuficiente o contradictoria, dilo explícitamente y baja
                    tu nivel de confianza en vez de decidir a ciegas.
                    """)
                .build();
    }
}
