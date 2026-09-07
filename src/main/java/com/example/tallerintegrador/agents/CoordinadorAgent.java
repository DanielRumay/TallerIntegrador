package com.example.tallerintegrador.agents;
import com.example.tallerintegrador.service.analitica.AdaptiveLearningService;

import com.example.tallerintegrador.agents.committee.ConsensoComite;
import com.example.tallerintegrador.agents.committee.CoordinadorService;
import com.example.tallerintegrador.agents.committee.Postura;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import dev.langchain4j.service.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CoordinadorAgent — Turno 5: cierra el debate consolidando las posturas en una decisión.
 *
 * generarConsenso() sigue devolviendo un Map con las mismas claves que antes
 * (debate_transcripcion, nuevo_nivel, conceptos_a_reforzar, recomendaciones) para no romper
 * el contrato que ya consumen AdaptiveLearningService y el frontend — lo que cambió es
 * cómo se obtiene ese contenido: ya no es un JSON de texto libre parseado a mano, es
 * ConsensoComite tipado por AiServices, y la transcripción se arma concatenando las
 * Posturas reales de los cuatro turnos anteriores, no algo que el propio LLM redacta de
 * memoria.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoordinadorAgent {

    private final CoordinadorService coordinadorService;

    public Map<String, Object> generarConsenso(
            Usuario usuario,
            Long usuarioId,
            String contextoEvaluacion,
            Postura turno1,
            Postura turno2,
            Postura turno3,
            Postura turno4) {

        String instrucciones = """
                Eres el [Agente Coordinador]. Debes cerrar el debate del comité educativo.

                PERFIL DEL ALUMNO:
                - Nombre: %s
                - Nivel actual: %s

                DATOS DE RENDIMIENTO DE LA EVALUACIÓN:
                %s

                HISTORIAL DEL DEBATE (con la evidencia que cada colega consultó):
                - Turno 1 ([Agente Evaluador]): %s | evidencia: %s
                - Turno 2 ([Agente Psicopedagogo]): %s | evidencia: %s
                - Turno 3 ([Agente de Adaptación de Evaluaciones]): %s | evidencia: %s
                - Turno 4 ([Agente Psicopedagogo]): %s | evidencia: %s

                Este es el Turno 5 (usuarioId=%d). Sintetiza el debate y decide el nivel
                final del alumno. Las recomendaciones deben mencionar explícitamente qué
                métodos de retroalimentación de la semana (ej: "Hablar con Aria el avatar
                tutor", "Video Explicativo", "Opción múltiple", "Detección de errores") se
                recomiendan, para que el frontend pueda desbloquearlos selectivamente.
                """.formatted(
                usuario.getNombre(), usuario.getNivelConocimiento(), contextoEvaluacion,
                turno1.mensaje(), turno1.evidencia(),
                turno2.mensaje(), turno2.evidencia(),
                turno3.mensaje(), turno3.evidencia(),
                turno4.mensaje(), turno4.evidencia(),
                usuarioId
        );

        try {
            Result<ConsensoComite> resultado = coordinadorService.decidir(instrucciones);
            ConsensoComite consenso = resultado.content();
            return ensamblarRespuesta(consenso, turno1, turno2, turno3, turno4, false);
        } catch (Exception ex) {
            log.error("[CoordinadorAgent] Error en Turno 5 (Consenso): {}, aplicando fallback", ex.getMessage());
            return fallbackConsenso(turno1, turno2, turno3, turno4);
        }
    }

    private Map<String, Object> ensamblarRespuesta(ConsensoComite consenso,
                                                    Postura t1, Postura t2, Postura t3, Postura t4,
                                                    boolean fallback) {
        String t5 = "[Agente Coordinador]: " + consenso.mensaje();
        String transcripcion = String.join("\n",
                t1.agente() + ": " + t1.mensaje(),
                t2.agente() + ": " + t2.mensaje(),
                t3.agente() + ": " + t3.mensaje(),
                t4.agente() + ": " + t4.mensaje(),
                t5);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("debate_transcripcion", transcripcion);
        out.put("nuevo_nivel", consenso.nivelPropuesto());
        out.put("confianza", consenso.confianza());
        out.put("conceptos_a_reforzar", consenso.conceptosAReforzar());
        out.put("recomendaciones", consenso.recomendaciones());
        out.put("_fallback", fallback);
        return out;
    }

    private Map<String, Object> fallbackConsenso(Postura t1, Postura t2, Postura t3, Postura t4) {
        ConsensoComite consenso = new ConsensoComite(
                "Tras revisar las visiones estadística y pedagógica, determinamos que el estudiante requiere consolidar sus bases conceptuales mediante retroalimentación focalizada.",
                "PRINCIPIANTE",
                0.2,
                "conceptos generales del tema",
                List.of("Ver el Video Explicativo animado de la semana.", "Realizar el cuestionario de Opción múltiple.")
        );
        return ensamblarRespuesta(consenso, t1, t2, t3, t4, true);
    }
}
