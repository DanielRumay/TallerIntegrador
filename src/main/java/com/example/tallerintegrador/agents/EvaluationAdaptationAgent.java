package com.example.tallerintegrador.agents;

import com.example.tallerintegrador.agents.committee.AgenteDelComite;
import com.example.tallerintegrador.agents.committee.Postura;
import dev.langchain4j.service.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/** EvaluationAdaptationAgent — enfoque de Taxonomía de Bloom y ajuste de dificultad del comité. */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationAdaptationAgent {

    @Qualifier("agenteAdaptacion")
    private final AgenteDelComite agenteAdaptacion;

    /** Turno 3: perspectiva de adaptación de dificultad según la Taxonomía de Bloom. */
    public Postura generarTurno3(Long usuarioId, String contextoEvaluacion, Postura turno1, Postura turno2) {
        String instrucciones = """
                DATOS DE LA EVALUACIÓN:
                %s

                HISTORIAL DEL DEBATE:
                - Turno 1 ([Agente Evaluador]): %s
                - Turno 2 ([Agente Psicopedagogo]): %s

                Este es el Turno 3 del debate (usuarioId=%d). Antes de opinar, consulta el
                historial de notas del alumno. Determina cómo adaptar y ajustar las futuras
                evaluaciones a su nivel (mapeando con la Taxonomía de Bloom), asegurando que
                el nivel sea el adecuado para no frustrarlo pero manteniendo mejora continua.
                """.formatted(contextoEvaluacion, turno1.mensaje(), turno2.mensaje(), usuarioId);

        try {
            Result<Postura> resultado = agenteAdaptacion.deliberar(instrucciones);
            return resultado.content();
        } catch (Exception e) {
            log.error("[EvaluationAdaptationAgent] Error en Turno 3: {}", e.getMessage());
            return fallback("Sugiero que las próximas pruebas mantengan un nivel intermedio de la Taxonomía de Bloom para evitar frustración e incentivar la aplicación.");
        }
    }

    private Postura fallback(String mensaje) {
        return new Postura("[Agente de Adaptación de Evaluaciones]", null, 0.3,
                List.of("Fallback local: la API no respondió"), mensaje);
    }
}
