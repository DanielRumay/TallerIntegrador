package com.example.tallerintegrador.agents;

import com.example.tallerintegrador.service.GeminiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PsicopedagogoAgent {

    private final GeminiService geminiService;

    /**
     * Turno 2: Respuesta cualitativa al Evaluador sobre la naturaleza cognitiva de los errores.
     */
    public String generarTurno2(String contextoEvaluacion, String turno1) {
        String prompt = String.format("""
            Actúa como el [Agente Psicopedagogo] en un comité educativo virtual. Tu enfoque es cualitativo y cognitivo.
            Analiza los siguientes datos y la intervención previa del Evaluador:
            
            DATOS DE RENDIMIENTO:
            %s
            
            TURNO 1 ([Agente Evaluador]):
            %s
            
            Escribe el Turno 2 del debate. Responde cualitativamente al Evaluador explicando el por qué de esos fallos (ej. problemas de codificación, falta de asociación de ideas, distractores o bloqueos).
            REGLAS:
            - Escribe exactamente de 1 a 2 oraciones concisas.
            - Dirígete o haz referencia al Evaluador.
            - Comienza tu respuesta OBLIGATORIAMENTE con tu etiqueta: '[Agente Psicopedagogo]: '
            - Cero texto extra, cero markdown.
            """, contextoEvaluacion, turno1);

        try {
            return geminiService.askGemini(prompt).text().trim();
        } catch (Exception e) {
            log.error("[PsicopedagogoAgent] Error en Turno 2: {}", e.getMessage());
            return "[Agente Psicopedagogo]: Entiendo tu punto, Evaluador, pero considero que el error del estudiante denota una falta de codificación de conceptos clave.";
        }
    }

    /**
     * Turno 4: Propuesta de técnicas de estudio y apoyos recomendados.
     */
    public String generarTurno4(String contextoEvaluacion, String turno1, String turno2, String turno3) {
        String prompt = String.format("""
            Actúa como el [Agente Psicopedagogo] en un comité educativo virtual. Tu enfoque es cualitativo, psicopedagógico y de soporte.
            
            DATOS DE RENDIMIENTO:
            %s
            
            HISTORIAL DEL DEBATE:
            - Turno 1 ([Agente Evaluador]): %s
            - Turno 2 (Tuyo): %s
            - Turno 3 ([Agente Evaluador]): %s
            
            Escribe el Turno 4 del debate. Propone las mejores técnicas de estudio y sugiere qué herramientas o formatos de retroalimentación de la semana (por ejemplo, hablar con Aria el avatar tutor, ver videolecciones, realizar mapas o quizzes visuales) le ayudarán más.
            REGLAS:
            - Escribe exactamente de 1 a 2 oraciones concisas y propositivas.
            - Comienza tu respuesta OBLIGATORIAMENTE con tu etiqueta: '[Agente Psicopedagogo]: '
            - Cero texto extra, cero markdown.
            """, contextoEvaluacion, turno1, turno2, turno3);

        try {
            return geminiService.askGemini(prompt).text().trim();
        } catch (Exception e) {
            log.error("[PsicopedagogoAgent] Error en Turno 4: {}", e.getMessage());
            return "[Agente Psicopedagogo]: Recomiendo proporcionarle una videolección detallada sobre el tema para reforzar sus bases de estudio.";
        }
    }
}
