package com.example.tallerintegrador.agents;

import com.example.tallerintegrador.DTO.GuardarIntentoAdaptativoRequest;
import com.example.tallerintegrador.entidades.postgres.NivelConocimiento;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.service.GeminiService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationAdaptationAgent {

    private final GeminiService geminiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Turno 3: Perspectiva de adaptación de dificultad según la Taxonomía de Bloom.
     */
    public String generarTurno3(String contextoEvaluacion, String turno1, String turno2) {
        String prompt = String.format("""
            Actúa como el [Agente de Adaptación de Evaluaciones] en un comité educativo virtual. Tu enfoque es la Taxonomía de Bloom y el ajuste de dificultad.
            
            DATOS DE LA EVALUACIÓN:
            %s
            
            HISTORIAL DEL DEBATE:
            - Turno 1 ([Agente Evaluador]): %s
            - Turno 2 ([Agente Psicopedagogo]): %s
            
            Escribe el Turno 3 del debate. Determina cómo adaptar y ajustar las futuras evaluaciones al nivel del alumno (mapeando con la Taxonomía de Bloom), asegurando que el nivel sea el adecuado para no frustrarlo pero manteniendo un objetivo de mejora continua.
            REGLAS:
            - Escribe exactamente de 1 a 2 oraciones concisas.
            - Dirígete al comité o haz referencia a las intervenciones anteriores.
            - Comienza tu respuesta OBLIGATORIAMENTE con tu etiqueta: '[Agente de Adaptación de Evaluaciones]: '
            - Cero texto extra, cero markdown.
            """, contextoEvaluacion, turno1, turno2);

        try {
            return geminiService.askGemini(prompt).text().trim();
        } catch (Exception e) {
            log.error("[EvaluationAdaptationAgent] Error en Turno 3: {}", e.getMessage());
            return "[Agente de Adaptación de Evaluaciones]: Sugiero que las próximas pruebas mantengan un nivel intermedio de la Taxonomía de Bloom para evitar frustración e incentivar la aplicación.";
        }
    }
}
