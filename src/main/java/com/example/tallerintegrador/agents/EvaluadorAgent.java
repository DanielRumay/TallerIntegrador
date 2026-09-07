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
 * EvaluadorAgent — enfoque cuantitativo y estadístico del comité.
 *
 * Antes de este cambio, cada turno recibía el resumen de rendimiento ya calculado dentro
 * de un String.format y devolvía prosa libre. Ahora consulta el historial real del alumno
 * con HerramientasComite antes de opinar (ver AiServicesConfig, bean agenteEvaluador) y
 * devuelve una Postura estructurada con evidencia citada, no una frase suelta.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluadorAgent {

    @Qualifier("agenteEvaluador")
    private final AgenteDelComite agenteEvaluador;

    /** Turno 1: diagnóstico inicial, fundamentado en el historial real del alumno. */
    public Postura generarTurno1(Long usuarioId, String contextoEvaluacion) {
        String instrucciones = """
                DATOS DE LA EVALUACIÓN QUE SE ACABA DE RENDIR:
                %s

                Este es el Turno 1 del debate. Antes de responder, consulta el historial de
                notas del alumno (usuarioId=%d) con tus herramientas. Emite tu postura inicial:
                un diagnóstico numérico frío de la nota final, la velocidad y la distribución
                de errores, citando en 'evidencia' los datos concretos que consultaste.
                """.formatted(contextoEvaluacion, usuarioId);

        try {
            Result<Postura> resultado = agenteEvaluador.deliberar(instrucciones);
            return resultado.content();
        } catch (Exception e) {
            log.error("[EvaluadorAgent] Error en Turno 1: {}", e.getMessage());
            return fallback("El rendimiento numérico de la evaluación muestra inconsistencias en los temas críticos.");
        }
    }

    /** Turno 3: réplica al Psicopedagogo, contrastando con datos finos. */
    public Postura generarTurno3(Long usuarioId, String contextoEvaluacion, Postura turno1, Postura turno2) {
        String instrucciones = """
                DATOS DE LA EVALUACIÓN:
                %s

                HISTORIAL DEL DEBATE:
                - Turno 1 (Tuyo): %s
                - Turno 2 ([Agente Psicopedagogo]): %s

                Este es el Turno 3 del debate (usuarioId=%d). Replica la opinión del
                Psicopedagogo contrastándola con datos numéricos finos que puedas consultar
                (tiempos de respuesta específicos, tasas de acierto). Dirígete a él.
                """.formatted(contextoEvaluacion, turno1.mensaje(), turno2.mensaje(), usuarioId);

        try {
            Result<Postura> resultado = agenteEvaluador.deliberar(instrucciones);
            return resultado.content();
        } catch (Exception e) {
            log.error("[EvaluadorAgent] Error en Turno 3: {}", e.getMessage());
            return fallback("Sin embargo, Psicopedagogo, los datos objetivos de tiempo de respuesta sugieren que hubo precipitación en el cuestionario.");
        }
    }

    private Postura fallback(String mensaje) {
        return new Postura("[Agente Evaluador]", null, 0.3, List.of("Fallback local: la API no respondió"), mensaje);
    }
}
