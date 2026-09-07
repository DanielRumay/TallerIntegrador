package com.example.tallerintegrador.agents;

import com.example.tallerintegrador.agents.judge.JuezDeRespuestaService;
import com.example.tallerintegrador.agents.judge.VeredictoJuez;
import com.example.tallerintegrador.entidades.postgres.EventoMetricaIA;
import com.example.tallerintegrador.entidades.postgres.TipoEventoIA;
import com.example.tallerintegrador.service.metricas.TelemetriaIAService;
import dev.langchain4j.service.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentJudgeAgent — califica las respuestas del alumno frente a la rúbrica.
 *
 * Antes de este cambio, el prompt le pedía al modelo "responde ÚNICAMENTE con JSON" y
 * cuando no obedecía (comillas internas, texto extra), un parser de expresiones regulares
 * intentaba rescatar los campos a mano. Eso trataba el síntoma. La causa era pedirle
 * cumplimiento de formato a un modelo mediante instrucciones de texto, que son
 * probabilísticas por naturaleza.
 *
 * Ahora el transporte pasa por JuezDeRespuestaService (LangChain4j AiServices), que declara
 * VeredictoJuez como esquema JSON nativo (Capability.RESPONSE_FORMAT_JSON_SCHEMA, ver
 * AiServicesConfig). Gemini cumple el esquema a nivel de API. El parser de rescate por
 * regex desaparece porque deja de tener trabajo que hacer, no porque se blindó mejor.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentJudgeAgent {

    private final JuezDeRespuestaService juezDeRespuestaService;
    private final TelemetriaIAService telemetriaIAService;

    public Map<String, Object> evaluarRespuestaUnitaria(
            String pregunta, String respuestaEsperada,
            String respuestaEstudiante, int totalPreguntas, String tipoPregunta) {

        boolean esBinaria = "VERDADERO_FALSO".equals(tipoPregunta)
                || "OPCION_MULTIPLE".equals(tipoPregunta)
                || "VISUAL_QUIZ".equals(tipoPregunta)
                || "VIDEO_EXPLICATIVO".equals(tipoPregunta);
        boolean esDeteccionErrores = "DETECCION_ERRORES".equals(tipoPregunta);

        String reglasEvaluacion = construirReglas(tipoPregunta, esBinaria, esDeteccionErrores);

        String prompt = """
                Actúa como un profesor experto, justo y objetivo.
                %s
                PREGUNTA: "%s"
                RÚBRICA / RESPUESTA ESPERADA: "%s"
                RESPUESTA DEL ESTUDIANTE: "%s"

                Completa el esquema estructurado con tu evaluación de esta respuesta.
                """.formatted(reglasEvaluacion, pregunta, respuestaEsperada, respuestaEstudiante);

        double pesoMaximoPregunta = Math.round((20.0 / totalPreguntas) * 100.0) / 100.0;

        long startTime = System.currentTimeMillis();
        Map<String, Object> evaluacion;
        long inputTokens = 0, outputTokens = 0;
        boolean fallo = false;

        try {
            Result<VeredictoJuez> resultado = juezDeRespuestaService.evaluar(prompt);
            var tokenUsage = resultado.tokenUsage();
            if (tokenUsage != null) {
                inputTokens = nz(tokenUsage.inputTokenCount());
                outputTokens = nz(tokenUsage.outputTokenCount());
            }
            evaluacion = construirEvaluacion(resultado.content(), pesoMaximoPregunta, esBinaria, esDeteccionErrores);
        } catch (Exception e) {
            log.error("[AgentJudgeAgent] Fallo al obtener veredicto estructurado: {}", e.getMessage());
            fallo = true;
            evaluacion = evaluacionDeFallo(pesoMaximoPregunta);
        }

        long latenciaMs = System.currentTimeMillis() - startTime;

        Map<String, Object> metricasRendimiento = Map.of(
                "latencia_segundos", latenciaMs / 1000.0,
                "input_tokens", inputTokens,
                "output_tokens", outputTokens,
                "total_tokens", inputTokens + outputTokens
        );

        registrarTelemetria(tipoPregunta, latenciaMs, inputTokens, outputTokens, evaluacion, fallo);

        Map<String, Object> resultadoFinal = new LinkedHashMap<>();
        resultadoFinal.put("pregunta_evaluada", pregunta);
        resultadoFinal.put("evaluacion", evaluacion);
        resultadoFinal.put("metricas_rendimiento", metricasRendimiento);

        return resultadoFinal;
    }

    private String construirReglas(String tipoPregunta, boolean esBinaria, boolean esDeteccionErrores) {
        if (esBinaria) {
            return """
                    REGLA ABSOLUTA: Esta pregunta es de tipo %s. Solo hay correcto o incorrecto.
                    - Si coincide con la respuesta esperada → puntaje: 100, esCorrecta: true
                    - Si no coincide → puntaje: 0, esCorrecta: false
                    NO uses valores intermedios.
                    SIEMPRE escribe una explicacion de 3 a 4 oraciones indicando por qué es correcta
                    o incorrecta, mencionando cuál era la respuesta esperada si falló.
                    """.formatted(tipoPregunta);
        }
        if (esDeteccionErrores) {
            return """
                    REGLAS (pregunta DETECCION_ERRORES):
                    1. El estudiante debió identificar los términos erróneos en el texto y proporcionar sus correcciones.
                    2. La respuesta esperada tiene las respuestas correctas en formato: 'correccion1 | correccion2'.
                    3. La respuesta del estudiante contiene las correcciones enviadas por él (en formato de texto o JSON).
                    4. Evalúa si el estudiante encontró los errores conceptuales y si los corrigió correctamente.
                    5. El puntaje debe ser proporcional (ej: si son 2 errores y corrigió ambos bien = 100, si solo uno = 50, si ninguno = 0).
                    6. En la explicación, detalla qué correcciones fueron acertadas y cuáles no, comparando con la respuesta esperada.
                    7. 'esCorrecta' será true si obtuvo un puntaje de 75 o más.
                    8. Evalúa cada corrección de forma semántica pero rigurosa. Acepta sinónimos directos o respuestas semánticamente equivalentes (por ejemplo, 'contaminación absoluta' es válido si la respuesta esperada es 'clima altamente contaminado'), pero NO aceptes conceptos que tengan matices filosóficos o teóricos distintos que alteren el sentido exacto del texto original (por ejemplo, 'fatalista' no debe ser aceptado como válido si la respuesta correcta es 'pesimista', ya que son conceptos diferenciables y no sinónimos exactos).
                    9. CUIDADO CON LA GENERALIZACIÓN: No aceptes respuestas que sean excesivamente generales o vagas si la respuesta esperada exige un término técnico o específico del tema.
                    10. Completa el campo 'detalles': una entrada por cada error, con la palabra con error y si la corrección del estudiante fue válida.
                    11. Completa 'textoCorregido' con el texto completo del enunciado con TODAS las correcciones aplicadas.
                     """;
        }
        return """
                REGLAS (pregunta ABIERTA / VIDEO_PRESENTACION):
                1. CRÍTICO — DETECCIÓN DE RESPUESTA VACÍA O EVASIÓN:
                   a) Si la respuesta del estudiante es genérica, no responde a la pregunta, es evasiva (ej. "respuesta correcta", "no sé", "esa es la respuesta", texto sin relación con el tema), o no tiene contenido sustancial que demuestre comprensión: ASIGNA PUNTAJE 0, esCorrecta: false, y en la explicación indica que no respondió adecuadamente a la pregunta.
                   b) NO asumas que el estudiante respondió correctamente solo porque usó palabras clave de la rúbrica. Verifica que la respuesta realmente DESARROLLE un argumento coherente y específico que demuestre comprensión.
                2. Si la respuesta es sustancial y demuestra comprensión: evalúa profundidad, conceptos y cumplimiento de la rúbrica o respuesta esperada. Puntaje de 0 a 100 proporcional.
                3. RETROALIMENTACIÓN PEDAGÓGICA:
                   a) Si acertó: felicítalo y explica por qué su respuesta es correcta, destacando los aciertos concretos.
                   b) Si se equivocó o está incompleta: explica EXPLÍCITAMENTE cuál era la respuesta correcta o qué conceptos debería haber incluido según la rúbrica. No te limites a decir "está incorrecto"; enseña mostrando qué esperabas y por qué.
                   c) Siempre compara la respuesta del estudiante con la esperada, señalando qué incluyó bien, qué omitió y qué debe corregir.
                4. Explicación de 3 a 6 oraciones, en tono docente y constructivo.
                """;
    }

    /** Convierte el record tipado en el mismo Map que consumían frontend/pruebas antes del cambio. */
    private Map<String, Object> construirEvaluacion(VeredictoJuez veredicto, double pesoMaximoPregunta,
                                                     boolean esBinaria, boolean esDeteccionErrores) {
        int puntaje100 = Math.max(0, Math.min(100, veredicto.puntaje()));
        boolean esCorrecta = veredicto.esCorrecta();

        if (esBinaria) {
            puntaje100 = esCorrecta ? 100 : 0;
        }

        double puntajeEscala = Math.round((puntaje100 / 100.0) * pesoMaximoPregunta * 100.0) / 100.0;

        Map<String, Object> evaluacion = new LinkedHashMap<>();
        evaluacion.put("esCorrecta", esCorrecta);
        evaluacion.put("explicacion", veredicto.explicacion());
        evaluacion.put("puntaje_porcentaje", puntaje100);
        evaluacion.put("puntaje", puntajeEscala);
        evaluacion.put("puntaje_maximo", pesoMaximoPregunta);

        if (esDeteccionErrores) {
            List<Map<String, Object>> detalles = veredicto.detalles() == null ? List.of()
                    : veredicto.detalles().stream()
                        .map(d -> (Map<String, Object>) Map.<String, Object>of(
                                "palabra_con_error", d.palabraConError(),
                                "esCorrecto", d.esCorrecto()))
                        .toList();
            evaluacion.put("detalles", detalles);
            evaluacion.put("texto_corregido", veredicto.textoCorregido());
        }

        return evaluacion;
    }

    /** Único camino de fallo que queda: la llamada a la API falló por completo (red, cuota). */
    private Map<String, Object> evaluacionDeFallo(double pesoMaximoPregunta) {
        Map<String, Object> evaluacion = new LinkedHashMap<>();
        evaluacion.put("esCorrecta", false);
        evaluacion.put("explicacion", "No se pudo obtener la evaluación de la IA. Por favor, vuelve a intentarlo.");
        evaluacion.put("puntaje_porcentaje", 0);
        evaluacion.put("puntaje", 0.0);
        evaluacion.put("puntaje_maximo", pesoMaximoPregunta);
        return evaluacion;
    }

    /**
     * Deja constancia de cada calificación: latencia, coste en tokens y si la llamada
     * falló por completo. Ya no existe un estado intermedio de "rescate por regex": o el
     * esquema estructurado se cumplió, o la llamada falló.
     */
    private void registrarTelemetria(String tipoPregunta, long latenciaMs,
                                     long inputTokens, long outputTokens,
                                     Map<String, Object> evaluacion, boolean fallo) {
        EventoMetricaIA evento = EventoMetricaIA.de(
                TipoEventoIA.JUEZ_EVALUACION,
                fallo ? "FALLO_LLAMADA" : "PARSEO_OK");
        evento.setLatenciaMs(latenciaMs);
        evento.setInputTokens((int) inputTokens);
        evento.setOutputTokens((int) outputTokens);
        evento.setDetalle(tipoPregunta);
        Object puntaje = evaluacion.get("puntaje_porcentaje");
        if (puntaje instanceof Number n) {
            evento.setValor(n.doubleValue());
        }
        telemetriaIAService.registrar(evento);
    }

    private long nz(Integer i) {
        return i != null ? i : 0L;
    }
}
