package com.example.tallerintegrador.agents;

import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.service.PreguntaDedupService;
import com.example.tallerintegrador.service.AgentJudgeService;
import com.example.tallerintegrador.service.GeminiService;
import com.example.tallerintegrador.service.PromptTemplateService;
import com.example.tallerintegrador.service.RagRetrieverService;
import com.example.tallerintegrador.service.RagRetrieverService.ChunkRelevante;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * EvaluationOrchestratorAgent — Patrón Orchestrator-Worker
 *
 * Coordina el flujo completo de generación de evaluación con RAG:
 *  1. Worker RAGRetriever   → recupera chunks relevantes de Qdrant
 *  2. Worker ContextSelector → LLM selecciona los mejores chunks
 *  3. Worker QuestionGenerator → genera preguntas con Structured Output
 *  4. Worker AgentJudge     → evalúa respuestas de alumnos (fase 2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationOrchestratorAgent {

    private final RagRetrieverService ragRetrieverService;
    private final ContextSelectorAgent   contextSelectorAgent;
    private final GeminiService geminiService;
    private final AgentJudgeService agentJudgeService;
    private final PromptTemplateService promptTemplateService;
    private final PreguntaDedupService preguntaDedupService;
    private final ObjectMapper           mapper = new ObjectMapper();

    // ===========================================================================
    // FASE 1: Generar preguntas (RAG -> Contexto -> StructuredOutput)
    // ===========================================================================

    /**
     * Genera preguntas de evaluación enriquecidas con RAG.
     *
     * @param tema          Ej: "procesos de comunicación y elementos"
     * @param archivoId     ID del archivo en MongoDB/Qdrant (null = busca en todos)
     * @param tipoPregunta  OPCION_MULTIPLE | VERDADERO_FALSO | ABIERTA
     * @param nivelBloom    Recordar | Comprender | Analizar | Evaluar | Crear
     * @param tecnica       FEW_SHOT | CHAIN_OF_THOUGHT | STRUCTURED_OUTPUT
     * @param cantidad      número de preguntas a generar
     */
    public Map<String, Object> generarEvaluacion(
            String tema,
            String archivoId,
            String tipoPregunta,
            String nivelBloom,
            String tecnica,
            int    cantidad) {
        return generarEvaluacion(tema, archivoId, tipoPregunta, nivelBloom, tecnica, cantidad, null);
    }

    public Map<String, Object> generarEvaluacion(
            String tema,
            String archivoId,
            String tipoPregunta,
            String nivelBloom,
            String tecnica,
            int    cantidad,
            String userEmail) {

        long inicio = System.currentTimeMillis();
        log.info("=== ORCHESTRATOR: Generando evaluación — tema='{}', tipo={}, bloom={} ===",
                tema, tipoPregunta, nivelBloom);

        log.info("[ORCHESTRATOR] → Delegando a RAGRetriever");
        List<ChunkRelevante> chunks = ragRetrieverService.recuperar(tema, archivoId);

        if (chunks.isEmpty()) {
            log.warn("[ORCHESTRATOR] RAG no encontró chunks relevantes — generando sin contexto");
        }
        log.info("[ORCHESTRATOR] → Delegando a ContextSelector");
        String contextoRAG = contextSelectorAgent.seleccionarContexto(chunks, nivelBloom, tipoPregunta);

        log.info("[ORCHESTRATOR] → Delegando a QuestionGenerator (con validación de similitud Qdrant)");

        Usuario usuario = preguntaDedupService.obtenerUsuarioPorEmail(userEmail);
        Long usuarioId = usuario != null ? usuario.getId() : null;
        List<String> preguntasEvitar = preguntaDedupService.obtenerPreguntasEvitar(userEmail, archivoId);

        List<Object> finalPreguntas = new ArrayList<>();
        List<String> avoidList = new ArrayList<>(preguntasEvitar);

        int attempts = 0;
        int targetCantidad = cantidad;

        Object finalPreguntasObj = null;
        Map<String, Object> baseMap = new LinkedHashMap<>();

        while (finalPreguntas.size() < targetCantidad && attempts < 3) {
            attempts++;
            int needed = targetCantidad - finalPreguntas.size();

            String preguntasJson = generarPreguntasConRAG(contextoRAG, tipoPregunta, nivelBloom, tecnica, needed, avoidList);
            Object parsed = parsearPreguntas(preguntasJson);

            if (parsed instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) parsed;
                if (baseMap.isEmpty()) {
                    baseMap.putAll(map);
                }

                Object listObj = map.get("preguntas");
                if (listObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) listObj;
                    for (Object p : list) {
                        if (p instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> pregunta = (Map<String, Object>) p;
                            String enunciado = (String) pregunta.get("enunciado");
                            if (enunciado != null && !enunciado.trim().isEmpty()) {
                                if (finalPreguntas.size() < targetCantidad) {
                                    if (!avoidList.contains(enunciado) && !preguntaDedupService.esPreguntaSimilar(enunciado, usuarioId, avoidList)) {
                                        finalPreguntas.add(p);
                                        avoidList.add(enunciado);
                                    } else {
                                        log.warn("[DEDUP-ORCHESTRATOR] Pregunta rechazada por similitud o duplicado exacto: {}", enunciado);
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                finalPreguntasObj = parsed;
            }
        }

        // Fallback si tras 3 intentos no se completaron preguntas
        if (finalPreguntas.size() < targetCantidad && attempts >= 3) {
            int needed = targetCantidad - finalPreguntas.size();
            log.warn("[DEDUP-ORCHESTRATOR] Fallback de deduplicación: generando {} pregunta(s) restante(s) sin restricciones vectoriales.", needed);
            String preguntasJson = generarPreguntasConRAG(contextoRAG, tipoPregunta, nivelBloom, tecnica, needed, java.util.List.of());
            Object parsed = parsearPreguntas(preguntasJson);
            if (parsed instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) parsed;
                if (baseMap.isEmpty()) {
                    baseMap.putAll(map);
                }
                Object listObj = map.get("preguntas");
                if (listObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) listObj;
                    finalPreguntas.addAll(list);
                }
            } else {
                finalPreguntasObj = parsed;
            }
        }

        if (finalPreguntasObj == null || finalPreguntasObj instanceof Map) {
            baseMap.put("preguntas", finalPreguntas);
            finalPreguntasObj = baseMap;
        }

        long latenciaTotal = System.currentTimeMillis() - inicio;

        Object preguntasObj = finalPreguntasObj;
        if ("VISUAL_QUIZ".equals(tipoPregunta) && preguntasObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) preguntasObj;
            Object listObj = map.get("preguntas");
            if (listObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) listObj;
                for (Object p : list) {
                    if (p instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> pregunta = (Map<String, Object>) p;
                        String promptImg = (String) pregunta.get("prompt_imagen");
                        if (promptImg != null && !promptImg.trim().isEmpty()) {
                            try {
                                log.info("[ORCHESTRATOR-IMAGE] Generando imagen para prompt: {}", promptImg);
                                String base64 = geminiService.generarImagenConImagen3(promptImg);
                                pregunta.put("base64_imagen", base64);
                            } catch (Exception e) {
                                log.error("Error generating image in orchestrator: {}", e.getMessage());
                                pregunta.put("error_imagen", e.getMessage());
                            }
                        }
                    }
                }
            }
        }

        if ("VIDEO_EXPLICATIVO".equals(tipoPregunta) && preguntasObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) preguntasObj;
            Object leccionObj = map.get("leccion");
            if (leccionObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> leccion = (Map<String, Object>) leccionObj;
                Object diapositivasObj = leccion.get("diapositivas");
                if (diapositivasObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> diapositivas = (List<Object>) diapositivasObj;
                    for (Object slideObj : diapositivas) {
                        if (slideObj instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> slide = (Map<String, Object>) slideObj;
                            String promptImg = (String) slide.get("prompt_imagen");
                            if (promptImg != null && !promptImg.trim().isEmpty()) {
                                try {
                                    log.info("[ORCHESTRATOR-IMAGE-SLIDE] Generando imagen para diapositiva: {}", promptImg);
                                    String base64 = geminiService.generarImagenConImagen3(promptImg);
                                    slide.put("base64_imagen", base64);
                                } catch (Exception e) {
                                    log.error("Error generating slide image in orchestrator: {}", e.getMessage());
                                    slide.put("error_imagen", e.getMessage());
                                }
                            }
                        }
                    }
                }
            }
        }

        // Ensamblar respuesta final
        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("tema",           tema);
        resultado.put("tipo_pregunta",  tipoPregunta);
        resultado.put("nivel_bloom",    nivelBloom);
        resultado.put("tecnica",        tecnica);
        resultado.put("chunks_usados",  chunks.size());
        resultado.put("contexto_rag",   contextoRAG.substring(0, Math.min(200, contextoRAG.length())) + "...");
        resultado.put("preguntas_json", preguntasObj);
        resultado.put("latencia_total_ms", latenciaTotal);

        log.info("[ORCHESTRATOR] Evaluación generada en {}ms", latenciaTotal);
        return resultado;
    }

    // ===========================================================================
    // FASE 2: Evaluar respuestas del alumno con el AgentJudge
    // ===========================================================================

    /**
     * Evalúa las respuestas de un alumno usando el AgentJudge existente.
     * Ahora el Judge también puede enriquecerse con RAG si se desea.
     *
     * @param preguntasYRespuestas  Lista de mapas con keys: pregunta, respuestaEsperada, respuestaEstudiante, tipoPregunta
     */
    public Map<String, Object> evaluarRespuestas(List<Map<String, String>> preguntasYRespuestas) {
        log.info("[ORCHESTRATOR] → Delegando {} preguntas al AgentJudge", preguntasYRespuestas.size());

        int totalPreguntas = preguntasYRespuestas.size();
        List<Map<String, Object>> evaluaciones = preguntasYRespuestas.stream()
                .map(pyr -> agentJudgeService.evaluarRespuestaUnitaria(
                        pyr.get("pregunta"),
                        pyr.get("respuestaEsperada"),
                        pyr.get("respuestaEstudiante"),
                        totalPreguntas,
                        pyr.get("tipoPregunta")
                ))
                .toList();

        double puntajeTotal = evaluaciones.stream()
                .mapToDouble(e -> {
                    Object ev = e.get("evaluacion");
                    if (ev instanceof Map<?, ?> evMap) {
                        Object p = evMap.get("puntaje");
                        return p != null ? Double.parseDouble(p.toString()) : 0.0;
                    }
                    return 0.0;
                })
                .sum();

        Map<String, Object> reporte = new LinkedHashMap<>();
        reporte.put("total_preguntas",  totalPreguntas);
        reporte.put("puntaje_total",    Math.round(puntajeTotal * 100.0) / 100.0);
        reporte.put("puntaje_maximo",   20.0);
        reporte.put("nota_vigesimal",   Math.round(puntajeTotal * 100.0) / 100.0);
        reporte.put("evaluaciones",     evaluaciones);

        return reporte;
    }

    // ===========================================================================
    // Métodos internos del Orchestrator
    // ===========================================================================

    /**
     * Combina el contexto RAG con el prompt de Structured Output existente.
     * El texto que se le pasa al generador es: contexto recuperado + instrucciones.
     */
    private String generarPreguntasConRAG(
            String contextoRAG,
            String tipoPregunta,
            String nivelBloom,
            String tecnica,
            int    cantidad,
            List<String> preguntasEvitar) {

        // Enriquecer el "texto" con el contexto RAG
        String textoParaGenerador = contextoRAG.isBlank()
                ? "[Sin contexto RAG disponible — genera según conocimiento general]"
                : contextoRAG;

        // Usa el PromptTemplateService ya existente (STRUCTURED_OUTPUT por defecto para RAG)
        String promptFinal = promptTemplateService.build(
                tecnica,
                tipoPregunta,
                nivelBloom,
                textoParaGenerador,
                cantidad,
                preguntasEvitar
        );

        return geminiService.askGemini(promptFinal).text();
    }

    private Object parsearPreguntas(String rawJson) {
        try {
            String clean = rawJson
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("(?s)```\\s*", "")
                    .trim();
            int s = clean.indexOf("{"), e = clean.lastIndexOf("}");
            if (s != -1 && e > s) clean = clean.substring(s, e + 1);
            return mapper.readValue(clean, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            log.warn("[ORCHESTRATOR] No se pudo parsear JSON de preguntas: {}", ex.getMessage());
            return rawJson;
        }
    }
}