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

    public Map<String, Object> ejecutarDebate(
            Usuario usuario,
            GuardarIntentoAdaptativoRequest request,
            boolean esAcra,
            Map<String, Object> acraDetalle) {

        String contextoEvaluacion = construirContextoEvaluacion(request, esAcra, acraDetalle);
        String prompt = construirPromptDebate(usuario, contextoEvaluacion);

        try {
            String raw = geminiService.askGemini(prompt).text();
            String clean = raw.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
            int s = clean.indexOf("{"), e = clean.lastIndexOf("}");
            if (s != -1 && e > s) clean = clean.substring(s, e + 1);
            return objectMapper.readValue(clean, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            log.error("[EvaluationAdaptationAgent] Error en debate de agentes, aplicando fallback: {}", ex.getMessage());
            return fallbackDebate(usuario.getNivelConocimiento(), request.notaFinal(), esAcra);
        }
    }

    private String construirContextoEvaluacion(
            GuardarIntentoAdaptativoRequest request,
            boolean esAcra,
            Map<String, Object> acraDetalle) {
        if (esAcra && acraDetalle != null) {
            return String.format("""
                Tipo de prueba: DIAGNÓSTICA ACRA (Escala de Estrategias de Aprendizaje)
                Puntaje total ACRA: %s / 80 puntos
                Escala I - Adquisición:   %s / 20
                Escala II - Codificación: %s / 20
                Escala III - Recuperación:%s / 20
                Escala IV - Apoyo:        %s / 20
                Nivel preliminar sugerido por puntaje ACRA: %s
                """,
                    acraDetalle.get("total"),
                    ((Map<?, ?>) acraDetalle.get("escala_I_adquisicion")).get("puntaje"),
                    ((Map<?, ?>) acraDetalle.get("escala_II_codificacion")).get("puntaje"),
                    ((Map<?, ?>) acraDetalle.get("escala_III_recuperacion")).get("puntaje"),
                    ((Map<?, ?>) acraDetalle.get("escala_IV_apoyo")).get("puntaje"),
                    acraDetalle.get("nivel_determinado")
            );
        } else {
            String respuestasResumen = request.respuestas().stream()
                    .map(r -> String.format("  - [%s] Pregunta: '%s' | Respuesta: '%s' | Correcta: %b",
                            r.tipoPregunta(), r.preguntaTexto(), r.respuestaEstudiante(), r.esCorrecta()))
                    .collect(Collectors.joining("\n"));
            return String.format("""
                Tipo de prueba: FORMATIVA
                Nota final: %.2f / 20.0
                Tiempo empleado: %d segundos
                Número de intento: %d
                Respuestas:
                %s
                """,
                    request.notaFinal(), request.tiempoEmpleadoSegundos(),
                    request.numeroIntentos(), respuestasResumen
            );
        }
    }

    private String construirPromptDebate(Usuario usuario, String contextoEvaluacion) {
        return String.format("""
        Actúa como un comité educativo virtual compuesto por 4 agentes especializados.
        Debatan brevemente el perfil del estudiante y lleguen a un consenso.

        PERFIL DEL ESTUDIANTE:
        - Nombre: %s
        - Nivel de conocimiento actual: %s
        - Dificultades detectadas anteriormente: %s

        DATOS DE LA EVALUACIÓN:
        %s

        ROLES DEL DEBATE:
        [Agente Evaluador]: Analiza estadísticas duras: puntaje, velocidad, distribución de errores.
        [Agente Psicopedagogo]: Interpreta el tipo de error y el perfil estratégico del alumno. Propone apoyo.
        [Agente de Adaptación de Evaluaciones]: Determina cómo adaptar y ajustar las futuras evaluaciones al nivel del alumno (mapeando con la Taxonomía de Bloom), asegurando que el nivel sea el adecuado para no frustrarlo pero manteniendo un objetivo de mejora continua y progresiva.
        [Agente Coordinador]: Sintetiza todas las visiones, resuelve diferencias y define el nivel final y las acciones.

        INSTRUCCIONES:
        - Escribe exactamente 4 intervenciones (una por agente) en orden: Evaluador → Psicopedagogo → Adaptación de Evaluaciones → Coordinador.
        - Cada intervención: máximo 2 oraciones concretas.
        - El Coordinador SIEMPRE cierra con una decisión clara de nivel y recomendaciones.
        - Responde ÚNICAMENTE con un objeto JSON válido sin bloques markdown, con esta estructura exacta:
        {
          "debate_transcripcion": "[Agente Evaluador]: ...\\n[Agente Psicopedagogo]: ...\\n[Agente de Adaptación de Evaluaciones]: ...\\n[Agente Coordinador]: ...",
          "nuevo_nivel": "PRINCIPIANTE" | "INTERMEDIO" | "AVANZADO",
          "conceptos_a_reforzar": "concepto1, concepto2",
          "recomendaciones": ["Recomendación 1", "Recomendación 2", "Recomendación 3"]
        }
        """, usuario.getNombre(), usuario.getNivelConocimiento(),
                Optional.ofNullable(usuario.getDificultadesDetectadas()).orElse("Ninguna"),
                contextoEvaluacion);
    }

    public Map<String, Object> fallbackDebate(NivelConocimiento nivelActual, Double nota, boolean esAcra) {
        NivelConocimiento nuevoNivel = nivelActual;
        if (!esAcra && nota != null) {
            if (nota >= 16.0 && nivelActual == NivelConocimiento.PRINCIPIANTE) nuevoNivel = NivelConocimiento.INTERMEDIO;
            else if (nota >= 16.0 && nivelActual == NivelConocimiento.INTERMEDIO) nuevoNivel = NivelConocimiento.AVANZADO;
            else if (nota < 11.0 && nivelActual == NivelConocimiento.AVANZADO) nuevoNivel = NivelConocimiento.INTERMEDIO;
            else if (nota < 11.0 && nivelActual == NivelConocimiento.INTERMEDIO) nuevoNivel = NivelConocimiento.PRINCIPIANTE;
        }
        return Map.of(
                "debate_transcripcion", "[Agente Evaluador]: Rendimiento analizado con reglas fijas.\\n[Agente Psicopedagogo]: Estrategia psicopedagógica de contingencia aplicada.\\n[Agente de Adaptación de Evaluaciones]: Adaptación curricular adaptada de forma estática.\\n[Agente Coordinador]: Nivel de contingencia determinado.",
                "nuevo_nivel", nuevoNivel.name(),
                "conceptos_a_reforzar", "conceptos generales del tema evaluado",
                "recomendaciones", List.of("Repasar el material didáctico asignado a la semana.")
        );
    }
}
