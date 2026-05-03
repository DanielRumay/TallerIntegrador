package com.example.tallerintegrador.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpikeService {

    private final GeminiService geminiService;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper mapper = new ObjectMapper();

    public List<Map<String, Object>> compare(
            String texto, String tipoPregunta, String nivelBloom, int cantidad) {

        List<Map<String, Object>> resultados = new ArrayList<>();

        for (String tecnica : List.of(
                PromptTemplateService.FEW_SHOT,
                PromptTemplateService.CHAIN_OF_THOUGHT,
                PromptTemplateService.STRUCTURED_OUTPUT)) {
            try {
                resultados.add(ejecutarTecnica(tecnica, tipoPregunta, nivelBloom, texto, cantidad));
                // Aumentamos el sleep a 2 segundos para ser más amigables con el rate limit gratuito
                Thread.sleep(2000);
            } catch (Exception e) {
                log.error("Error con técnica {}: {}", tecnica, e.getMessage());
                resultados.add(Map.of("tecnica", tecnica, "error", e.getMessage()));
            }
        }
        return resultados;
    }

    public Map<String, Object> ejecutarTecnica(
            String tecnica, String tipoPregunta,
            String nivelBloom, String texto, int cantidad) {

        String prompt = promptTemplateService.build(tecnica, tipoPregunta, nivelBloom, texto, cantidad);
        String respuesta = geminiService.askGemini(prompt);

        // Extraemos Bloom de la MISMA respuesta (¡Cero llamadas extra!)
        Map<String, Object> bloom = extraerBloomDelJson(respuesta, tecnica);

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("tecnica",          tecnica);
        resultado.put("tipo_pregunta",    tipoPregunta);
        resultado.put("nivel_bloom_obj",  nivelBloom != null ? nivelBloom : "Auto");
        resultado.put("respuesta_cruda",  respuesta);
        resultado.putAll(bloom);

        return resultado;
    }

    private Map<String, Object> extraerBloomDelJson(String respuesta, String tecnica) {
        try {
            // Limpiamos el texto por si Gemini devolvió el JSON envuelto en markdown
            String cleanStr = cleanJsonString(respuesta);
            JsonNode root = mapper.readTree(cleanStr);
            JsonNode eval = root.path("evaluacion_bloom");

            if (!eval.isMissingNode()) {
                return Map.of(
                        "nivel_bloom",       eval.path("nivel_bloom").asText("N/A"),
                        "nivel_bloom_orden", eval.path("nivel_bloom_orden").asInt(0),
                        "es_hots",           eval.path("es_hots").asBoolean(false),
                        "puntaje_calidad",   eval.path("puntaje_calidad").asDouble(0.0),
                        "justificacion",     eval.path("justificacion_evaluacion").asText(""),
                        "fuente_evaluacion", "Autoevaluación en 1 paso (" + tecnica + ")"
                );
            } else {
                return Map.of("nivel_bloom", "No encontrado en JSON");
            }
        } catch (Exception e) {
            log.warn("No se pudo parsear el JSON de Bloom para {}: {}", tecnica, e.getMessage());
            return Map.of("nivel_bloom", "Error de parseo");
        }
    }

    // Busca el primer bloque de JSON en la respuesta, ideal para Chain-of-Thought
    private String cleanJsonString(String raw) {
        int startIndex = raw.indexOf("{");
        int endIndex = raw.lastIndexOf("}");
        if (startIndex != -1 && endIndex != -1) {
            return raw.substring(startIndex, endIndex + 1);
        }
        return raw; // Si no encuentra llaves, devuelve crudo a ver si hay suerte
    }
}