package com.example.tallerintegrador.agents;

import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.service.GeminiService;
import com.example.tallerintegrador.service.util.JsonParsingUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoordinadorAgent {

    private final GeminiService geminiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Turno 5: Cierra la conversación consolidando el debate en un reporte estructurado JSON.
     */
    public Map<String, Object> generarConsenso(
            Usuario usuario,
            String contextoEvaluacion,
            String turno1,
            String turno2,
            String turno3,
            String turno4) {

        String prompt = String.format("""
            Actúa como el [Agente Coordinador] en un comité educativo virtual. Tu enfoque es integrador, mediador y decisivo.
            Debes evaluar las opiniones previas de tus colegas, sintetizarlas, declarar el veredicto del nuevo nivel del alumno y emitir las recomendaciones.

            PERFIL DEL ALUMNO:
            - Nombre: %s
            - Nivel actual: %s

            DATOS DE RENDIMIENTO DE LA EVALUACIÓN:
            %s

            HISTORIAL DEL DEBATE:
            - Turno 1 ([Agente Evaluador]): %s
            - Turno 2 ([Agente Psicopedagogo]): %s
            - Turno 3 ([Agente de Adaptación de Evaluaciones]): %s
            - Turno 4 ([Agente Psicopedagogo]): %s

            INSTRUCCIONES:
            1. Escribe tu intervención de cierre como el [Agente Coordinador] (Turno 5), de 1 a 2 oraciones, saludando/concordando con los colegas y dictando la decisión final.
            2. Junta todas las 5 intervenciones ordenadas en un solo bloque de texto para la transcripción del debate.
            3. Responde ÚNICAMENTE con un objeto JSON válido, sin bloques de código markdown ni texto adicional, con esta estructura exacta:
            {
              "debate_transcripcion": "[Agente Evaluador]: (turno 1)\\n[Agente Psicopedagogo]: (turno 2)\\n[Agente de Adaptación de Evaluaciones]: (turno 3)\\n[Agente Psicopedagogo]: (turno 4)\\n[Agente Coordinador]: (tu turno 5)",
              "nuevo_nivel": "PRINCIPIANTE" | "INTERMEDIO" | "AVANZADO",
              "conceptos_a_reforzar": "lista, de, conceptos, clave",
              "recomendaciones": ["Recomendación 1", "Recomendación 2", "Recomendación 3"]
            }

            *CRÍTICO*: Asegúrate de que las recomendaciones mencionen explícitamente qué métodos de retroalimentación o estudio de la semana (ej: "Hablar con Aria el avatar tutor", "Video Explicativo", "Opción múltiple", "Detección de errores") son los recomendados para que el frontend pueda desbloquearlos selectivamente.
            """,
                usuario.getNombre(),
                usuario.getNivelConocimiento(),
                contextoEvaluacion,
                turno1,
                turno2,
                turno3,
                turno4
        );

        try {
            String raw = geminiService.askGemini(prompt).text();
            String clean = raw.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
            int s = clean.indexOf("{"), e = clean.lastIndexOf("}");
            if (s != -1 && e > s) clean = clean.substring(s, e + 1);
            return objectMapper.readValue(clean, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            log.error("[CoordinadorAgent] Error en Turno 5 (Consenso): {}, aplicando fallback", ex.getMessage());
            return fallbackConsenso(turno1, turno2, turno3, turno4);
        }
    }

    private Map<String, Object> fallbackConsenso(String t1, String t2, String t3, String t4) {
        String t5 = "[Agente Coordinador]: Tras revisar las visiones estadística y pedagógica, determinamos que el estudiante requiere consolidar sus bases conceptuales mediante retroalimentación focalizada.";
        String transcripcion = String.join("\n", t1, t2, t3, t4, t5);

        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("debate_transcripcion", transcripcion);
        fallback.put("nuevo_nivel", "PRINCIPIANTE");
        fallback.put("conceptos_a_reforzar", "conceptos generales del tema");
        fallback.put("recomendaciones", List.of(
                "Ver el Video Explicativo animado de la semana.",
                "Realizar el cuestionario de Opción múltiple."
        ));
        return fallback;
    }
}
