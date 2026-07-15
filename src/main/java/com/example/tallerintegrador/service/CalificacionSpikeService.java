package com.example.tallerintegrador.service;

import com.example.tallerintegrador.service.util.JsonParsingUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CalificacionSpikeService {

    private final GeminiService geminiService;
    private final MetricasEstandarizadasService metricasEstandarizadasService;
    private final ObjectMapper mapper = new ObjectMapper();

    public List<Map<String, Object>> evaluarRespuestasMock() {

        // 1. JSON MAESTRO
        String jsonMaestro = """
        {
          "tecnica": "STRUCTURED_OUTPUT",
          "tipo_pregunta": "ABIERTA",
          "nivel_bloom_obj": "Evaluar",
          "preguntas": [
            {
              "enunciado": "Argumenta por qué el elemento de la 'circunstancia o contexto' resulta determinante para el éxito de la comunicación, basándote en cómo este elemento previene malentendidos en situaciones de polisemia o ambigüedad según el texto proporcionado.",
              "opciones_o_respuesta": "Rúbrica: Nivel Alto: El estudiante justifica la importancia del contexto citando ejemplos específicos de polisemia (como el caso de la palabra 'arco') y explica detalladamente cómo este marco permite una descodificación precisa. Nivel Medio: El estudiante menciona que el contexto es importante pero no logra conectar profundamente la relación entre el lugar/tiempo y la selección del significado correcto. Nivel Bajo: El estudiante solo define el contexto sin emitir un juicio sobre su relevancia en la prevención de errores comunicativos.",
              "justificacion_pregunta": "Requiere que el estudiante evalúe la importancia de un elemento específico del proceso comunicativo y justifique su postura basándose en la funcionalidad del sistema."
            }
          ]
        }
        """;

        String pregunta = "";
        String rubrica = "";

        try {
            JsonNode root = mapper.readTree(jsonMaestro);
            JsonNode primeraPregunta = root.path("preguntas").get(0);
            pregunta = primeraPregunta.path("enunciado").asText();
            rubrica = primeraPregunta.path("opciones_o_respuesta").asText();
        } catch (Exception e) {
            return List.of(Map.of("error", "No se pudo leer el JSON maestro"));
        }

        // 2. Las 10 Respuestas
        List<String> respuestasAlumnos = List.of(
                "El contexto es vital porque ayuda a resolver palabras polisémicas como 'arco', asegurando la decodificación correcta de los mensajes ambiguos.",
                "El contexto es importante porque nos dice dónde y cuándo hablamos. Sin eso, no se entiende.",
                "El contexto es el lugar físico y el tiempo donde se produce la comunicación.",
                "Nivel Alto: El estudiante justifica la importancia del contexto citando ejemplos específicos de polisemia (como 'arco') y explica detalladamente...",
                "El contexto no sirve para nada. La polisemia no se arregla con el lugar, la gente simplemente adivina.",
                "La situación ambiental es crucial para interpretar términos con múltiples significados y evitar conclusiones erradas por parte del receptor.",
                "Evita malentendidos en palabras con varios significados.",
                "El contexto es el idioma que se habla, como el español, que previene la ambigüedad.",
                "La comunicación tiene elementos como emisor, receptor, mensaje y canal.",
                "No sé profe, el contexto es que mi perro se comió la tarea."
        );

        // 3. PREPARAR EL BATCH PARA EL LLM
        StringBuilder batchRespuestas = new StringBuilder();
        for (int i = 0; i < respuestasAlumnos.size(); i++) {
            batchRespuestas.append("Alumno ").append(i + 1).append(":\n")
                    .append(respuestasAlumnos.get(i)).append("\n\n");
        }

        // 4. LLAMADA ÚNICA AL LLM (¡Solo consume 1 petición de tu cuota!)
        long startLlm = System.currentTimeMillis();
        String promptLlm = PromptTemplateService.PROMPT_LLM_JUEZ.formatted(pregunta, rubrica, batchRespuestas.toString());
        var responseObj = geminiService.askGemini(promptLlm);
        long latenciaLlm = System.currentTimeMillis() - startLlm;
        int tokensLlm = responseObj.usageMetadata().isPresent() ? responseObj.usageMetadata().get().totalTokenCount().orElse(0) : 0;

        List<Map<String, Object>> notasLlm = parsearJsonJuezBatch(responseObj.text());

        // 5. UNIR TODO (Coseno + LLM)
        List<Float> vectorRubrica = geminiService.getEmbeddings(rubrica);
        List<Map<String, Object>> resultadosFinales = new ArrayList<>();

        for (int i = 0; i < respuestasAlumnos.size(); i++) {
            String idAlumno = "Alumno " + (i + 1);
            String respuesta = respuestasAlumnos.get(i);

            // Coseno (El Embedder tiene un límite diferente y mucho más alto, no se caerá por 10 peticiones rápidas)
            long startCoseno = System.currentTimeMillis();
            List<Float> vectorAlumno = geminiService.getEmbeddings(respuesta);
            double similitudCoseno = metricasEstandarizadasService.calcularSimilitudCoseno(vectorRubrica, vectorAlumno);
            long latenciaCoseno = System.currentTimeMillis() - startCoseno;

            // Buscar la nota del LLM en la lista
            Map<String, Object> notaAlumnoLlm = notasLlm.stream()
                    .filter(m -> {
                        String id = String.valueOf(m.get("id_alumno"));
                        String num1 = idAlumno.replaceAll("\\D+", "");
                        String num2 = id.replaceAll("\\D+", "");
                        return !num1.isEmpty() && num1.equals(num2);
                    })
                    .findFirst()
                    .orElse(Map.of("nota", -1, "justificacion", "Fallo en LLM"));

            Map<String, Object> reporte = new LinkedHashMap<>();
            reporte.put("id_alumno", idAlumno);
            reporte.put("respuesta", respuesta);
            reporte.put("coseno_similitud", similitudCoseno);
            reporte.put("coseno_latencia_ms", latenciaCoseno);
            reporte.put("llm_nota", notaAlumnoLlm.get("nota"));
            reporte.put("llm_justificacion", notaAlumnoLlm.get("justificacion"));
            // Dividimos latencia y tokens totales entre los 10 alumnos para sacar el promedio por alumno
            reporte.put("llm_latencia_promedio_ms", latenciaLlm / respuestasAlumnos.size());
            reporte.put("llm_tokens_promedio", tokensLlm / respuestasAlumnos.size());

            resultadosFinales.add(reporte);

            try { Thread.sleep(500); } catch (InterruptedException ignored) {} // Mini pausa por seguridad del embedder
        }

        return resultadosFinales;
    }

    // Nuevo parser que devuelve una lista de mapas
    private List<Map<String, Object>> parsearJsonJuezBatch(String textoLimpio) {
        try {
            String clean = JsonParsingUtils.cleanJsonString(textoLimpio);
            return mapper.readValue(clean, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.error("Error parseando BATCH respuesta del Juez: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}