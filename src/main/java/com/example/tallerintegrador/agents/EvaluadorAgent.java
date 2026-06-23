package com.example.tallerintegrador.agents;

import com.example.tallerintegrador.service.GeminiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluadorAgent {

    private final GeminiService geminiService;

    /**
     * Turno 1: Análisis cuantitativo inicial de las estadísticas del intento.
     */
    public String generarTurno1(String contextoEvaluacion) {
        String prompt = String.format("""
            Actúa como el [Agente Evaluador] en un comité educativo virtual. Tu enfoque es puramente cuantitativo y estadístico.
            Analiza fríamente los siguientes datos de rendimiento:
            
            %s
            
            Escribe el Turno 1 del debate. Haz un diagnóstico numérico frío de la nota final, velocidad y distribución de errores.
            REGLAS:
            - Escribe exactamente de 1 a 2 oraciones concisas y al grano.
            - Comienza tu respuesta OBLIGATORIAMENTE con tu etiqueta: '[Agente Evaluador]: '
            - Cero texto extra, cero markdown.
            """, contextoEvaluacion);

        try {
            return geminiService.askGemini(prompt).text().trim();
        } catch (Exception e) {
            log.error("[EvaluadorAgent] Error en Turno 1: {}", e.getMessage());
            return "[Agente Evaluador]: El rendimiento numérico de la evaluación muestra inconsistencias en los temas críticos.";
        }
    }

    /**
     * Turno 3: Réplica al Psicopedagogo aportando datos finos de contraste.
     */
    public String generarTurno3(String contextoEvaluacion, String turno1, String turno2) {
        String prompt = String.format("""
            Actúa como el [Agente Evaluador] en un comité educativo virtual. Tu enfoque es cuantitativo y estadístico.
            
            DATOS DE RENDIMIENTO:
            %s
            
            HISTORIAL DEL DEBATE:
            - Turno 1 (Tuyo): %s
            - Turno 2 ([Agente Psicopedagogo]): %s
            
            Escribe el Turno 3 del debate. Replica la opinión del psicopedagogo contrastándola con los datos numéricos finos (como tiempos de respuesta específicos o tasas de acierto).
            REGLAS:
            - Escribe exactamente de 1 a 2 oraciones concisas.
            - Dirígete o haz referencia al Psicopedagogo.
            - Comienza tu respuesta OBLIGATORIAMENTE con tu etiqueta: '[Agente Evaluador]: '
            - Cero texto extra, cero markdown.
            """, contextoEvaluacion, turno1, turno2);

        try {
            return geminiService.askGemini(prompt).text().trim();
        } catch (Exception e) {
            log.error("[EvaluadorAgent] Error en Turno 3: {}", e.getMessage());
            return "[Agente Evaluador]: Sin embargo, Psicopedagogo, los datos objetivos de tiempo de respuesta sugieren que hubo precipitación en el cuestionario.";
        }
    }
}
