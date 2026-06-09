package com.example.tallerintegrador.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentJudgeService {

    private final GeminiService geminiService;
    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> evaluarRespuestaUnitaria(
            String pregunta, String respuestaEsperada,
            String respuestaEstudiante, int totalPreguntas, String tipoPregunta) {

        boolean esBinaria = "VERDADERO_FALSO".equals(tipoPregunta)
                || "OPCION_MULTIPLE".equals(tipoPregunta);

        String reglasEvaluacion = esBinaria ? """
        REGLA ABSOLUTA: Esta pregunta es de tipo %s. Solo hay correcto o incorrecto.
        - Si coincide con la respuesta esperada → puntaje: 100, esCorrecta: true
        - Si no coincide → puntaje: 0, esCorrecta: false
        NO uses valores intermedios.
        SIEMPRE escribe una explicacion de 3 a 4 oraciones indicando por qué es correcta
        o incorrecta, mencionando cuál era la respuesta esperada si falló.
        """.formatted(tipoPregunta) : """
        REGLAS (pregunta ABIERTA):
        1. Evalúa profundidad, conceptos y cumplimiento de la rúbrica.
        2. Puntaje de 0 a 100 proporcional al cumplimiento.
        3. Explicación de 2 a 4 oraciones.
        """;

        String prompt = String.format("""
        Actúa como un profesor experto, justo y objetivo.
        %s
        PREGUNTA: "%s"
        RÚBRICA / RESPUESTA ESPERADA: "%s"
        RESPUESTA DEL ESTUDIANTE: "%s"
        
        REGLAS DE FORMATO JSON:
        1. Tu respuesta debe ser un objeto JSON válido.
        2. Usa comillas dobles (") para todos los nombres de campos y valores de tipo texto.
        3. Para citar textos dentro del campo "explicacion", usa comillas simples ('). Nunca uses comillas dobles dentro del valor de "explicacion".
        
        Responde ÚNICAMENTE con JSON sin markdown:
        {"esCorrecta": true, "puntaje": 100, "explicacion": "..."}
        """, reglasEvaluacion, pregunta, respuestaEsperada, respuestaEstudiante);

        long startTime = System.currentTimeMillis();
        var responseObj = geminiService.askGemini(prompt);
        long latenciaMs = System.currentTimeMillis() - startTime;

        String respuestaIA = responseObj.text();
        String jsonLimpio = cleanJsonString(respuestaIA);

        int inputTokens = 0, outputTokens = 0, totalTokens = 0;
        var optionalMetadata = responseObj.usageMetadata();
        if (optionalMetadata != null && optionalMetadata.isPresent()) {
            var metadata = optionalMetadata.get();
            inputTokens  = metadata.promptTokenCount().orElse(0);
            outputTokens = metadata.candidatesTokenCount().orElse(0);
            totalTokens  = metadata.totalTokenCount().orElse(0);
        }

        Map<String, Object> metricasRendimiento = Map.of(
                "latencia_segundos", latenciaMs / 1000.0,
                "input_tokens",  inputTokens,
                "output_tokens", outputTokens,
                "total_tokens",  totalTokens
        );

        // Cálculo de escala (se aplica siempre, sea parse normal o fallback)
        double pesoMaximoPregunta = Math.round((20.0 / totalPreguntas) * 100.0) / 100.0;

        Map<String, Object> evaluacion = parsearEvaluacion(jsonLimpio, pesoMaximoPregunta, esBinaria);

        Map<String, Object> resultadoFinal = new LinkedHashMap<>();
        resultadoFinal.put("pregunta_evaluada", pregunta);
        resultadoFinal.put("evaluacion", evaluacion);
        resultadoFinal.put("metricas_rendimiento", metricasRendimiento);

        return resultadoFinal;
    }

    private Map<String, Object> parsearEvaluacion(String jsonLimpio, double pesoMaximoPregunta, boolean esBinaria) {
        Map<String, Object> evaluacion;

        // Intento 1: parse normal con Jackson
        try {
            JsonNode root = mapper.readTree(jsonLimpio);
            evaluacion = new LinkedHashMap<>(mapper.convertValue(root, Map.class));
        } catch (Exception e) {
            log.warn("JSON malformado de Gemini, usando extracción por regex. Error: {}", e.getMessage());

            // Intento 2: extracción por regex campo a campo
            evaluacion = new LinkedHashMap<>();
            try {
                boolean esCorrecta = jsonLimpio.contains("\"esCorrecta\": true")
                        || jsonLimpio.contains("\"esCorrecta\":true");

                Matcher puntajeMatcher = Pattern.compile("\"puntaje\":\\s*(\\d+)").matcher(jsonLimpio);
                int puntaje = puntajeMatcher.find() ? Integer.parseInt(puntajeMatcher.group(1)) : 0;


                Matcher explicacionMatcher = Pattern
                        .compile("\"explicacion\":\\s*[\"'](.*?)[\"']\\s*[,}]", Pattern.DOTALL)
                        .matcher(jsonLimpio);
                String explicacion = explicacionMatcher.find()
                        ? explicacionMatcher.group(1).replace("\\\"", "'")
                        : "Evaluación completada.";

                evaluacion.put("esCorrecta", esCorrecta);
                evaluacion.put("_puntaje_raw", puntaje);
                evaluacion.put("explicacion", explicacion);
            } catch (Exception ex) {
                log.error("Fallo también el regex fallback: {}", ex.getMessage());
                evaluacion.put("esCorrecta", false);
                evaluacion.put("_puntaje_raw", 0);
                evaluacion.put("explicacion", "No se pudo procesar la evaluación de la IA.");
            }
        }

        // Normalizar puntaje (puede venir de parse normal o del fallback)
        int puntaje100;
        if (evaluacion.containsKey("_puntaje_raw")) {
            puntaje100 = ((Number) evaluacion.remove("_puntaje_raw")).intValue();
        } else {
            Object raw = evaluacion.get("puntaje");
            puntaje100 = raw != null ? (int) Math.round(Double.parseDouble(raw.toString())) : 0;
        }
        puntaje100 = Math.max(0, Math.min(100, puntaje100)); // clamp 0-100

        if (esBinaria) {
            boolean correcto = Boolean.TRUE.equals(evaluacion.get("esCorrecta"));
            puntaje100 = correcto ? 100 : 0;
        }

        double puntajeEscala = Math.round((puntaje100 / 100.0) * pesoMaximoPregunta * 100.0) / 100.0;

        evaluacion.put("puntaje_porcentaje", puntaje100);
        evaluacion.put("puntaje",            puntajeEscala);
        evaluacion.put("puntaje_maximo",     pesoMaximoPregunta);

        return evaluacion;
    }

    private String cleanJsonString(String raw) {
        if (raw == null) return "{}";
        raw = raw.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
        int startIndex = raw.indexOf("{");
        int endIndex   = raw.lastIndexOf("}");
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return raw.substring(startIndex, endIndex + 1);
        }
        return "{}";
    }
}