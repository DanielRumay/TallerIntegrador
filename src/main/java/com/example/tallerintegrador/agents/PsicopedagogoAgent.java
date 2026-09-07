package com.example.tallerintegrador.agents;

import com.example.tallerintegrador.agents.committee.AgenteDelComite;
import com.example.tallerintegrador.agents.committee.Postura;
import dev.langchain4j.service.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * PsicopedagogoAgent — enfoque cualitativo y cognitivo del comité.
 *
 * Consulta el perfil declarado del alumno y sus preguntas falladas recientes antes de
 * opinar (HerramientasComite), en vez de generalizar sobre un resumen ya cocinado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PsicopedagogoAgent {

    @Qualifier("agentePsicopedagogo")
    private final AgenteDelComite agentePsicopedagogo;

    /** Turno 2: respuesta cualitativa al Evaluador sobre la naturaleza cognitiva de los errores. */
    public Postura generarTurno2(Long usuarioId, String contextoEvaluacion, Postura turno1) {
        String instrucciones = """
                DATOS DE RENDIMIENTO:
                %s

                TURNO 1 ([Agente Evaluador]): %s

                Este es el Turno 2 del debate (usuarioId=%d). Antes de responder, consulta el
                perfil del alumno y sus preguntas falladas recientes con tus herramientas.
                Responde cualitativamente al Evaluador explicando el POR QUÉ de esos fallos
                (problemas de codificación, falta de asociación de ideas, distractores,
                bloqueos), citando en 'evidencia' lo que consultaste.
                """.formatted(contextoEvaluacion, turno1.mensaje(), usuarioId);

        try {
            Result<Postura> resultado = agentePsicopedagogo.deliberar(instrucciones);
            return resultado.content();
        } catch (Exception e) {
            log.error("[PsicopedagogoAgent] Error en Turno 2: {}", e.getMessage());
            return fallback("Entiendo tu punto, Evaluador, pero considero que el error del estudiante denota una falta de codificación de conceptos clave.");
        }
    }

    /** Turno 4: propuesta de técnicas de estudio y apoyos recomendados. */
    public Postura generarTurno4(Long usuarioId, String contextoEvaluacion, Postura turno1, Postura turno2, Postura turno3) {
        String instrucciones = """
                DATOS DE RENDIMIENTO:
                %s

                HISTORIAL DEL DEBATE:
                - Turno 1 ([Agente Evaluador]): %s
                - Turno 2 (Tuyo): %s
                - Turno 3 ([Agente de Adaptación de Evaluaciones]): %s

                Este es el Turno 4 del debate (usuarioId=%d). Propón las mejores técnicas de
                estudio y sugiere qué herramientas o formatos de retroalimentación de la
                semana (hablar con Aria el avatar tutor, ver videolecciones, quizzes
                visuales) le ayudarán más, fundamentado en lo que consultaste.
                """.formatted(contextoEvaluacion, turno1.mensaje(), turno2.mensaje(), turno3.mensaje(), usuarioId);

        try {
            Result<Postura> resultado = agentePsicopedagogo.deliberar(instrucciones);
            return resultado.content();
        } catch (Exception e) {
            log.error("[PsicopedagogoAgent] Error en Turno 4: {}", e.getMessage());
            return fallback("Recomiendo proporcionarle una videolección detallada sobre el tema para reforzar sus bases de estudio.");
        }
    }

    private Postura fallback(String mensaje) {
        return new Postura("[Agente Psicopedagogo]", null, 0.3, List.of("Fallback local: la API no respondió"), mensaje);
    }
}
