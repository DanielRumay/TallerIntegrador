package com.example.tallerintegrador.service;

import com.example.tallerintegrador.DTO.GuardarIntentoAdaptativoRequest;
import com.example.tallerintegrador.agents.EvaluationOrchestratorAgent;
import com.example.tallerintegrador.entidades.postgres.*;
import com.example.tallerintegrador.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdaptiveLearningService {

    private final UserRepository userRepository;
    private final SemanaRepository semanaRepository;
    private final IntentoRepository intentoRepository;
    private final PreguntaRepository preguntaRepository;
    private final RespuestaUsuarioRepository respuestaUsuarioRepository;
    private final MaterialRepository materialRepository;
    private final EvaluationOrchestratorAgent evaluationOrchestratorAgent;
    private final GeminiService geminiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // =========================================================================
    // FASE 1: GENERAR EVALUACIÓN (ACRA Diagnóstica o Formativa adaptada)
    // =========================================================================

    /**
     * Genera la evaluación correspondiente al perfil del alumno.
     *
     * - Primera vez (diagnosticoCompletado=false): devuelve la prueba ACRA estática.
     * - Evaluaciones posteriores: prioriza preguntas estáticas de la BD; si no hay
     *   suficientes, genera dinámicamente con RAG + Gemini.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> generarEvaluacionAdaptativa(Long usuarioId, Long semanaId) {

        Usuario usuario = userRepository.findById(usuarioId)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado con ID: " + usuarioId));
        Semana semana = semanaRepository.findById(semanaId)
                .orElseThrow(() -> new RuntimeException("Semana no encontrada con ID: " + semanaId));

        if (usuario.getNivelConocimiento() == null) {
            usuario.setNivelConocimiento(NivelConocimiento.PRINCIPIANTE);
        }

        // ── Prueba Diagnóstica ACRA (primera vez) ────────────────────────────
        if (!usuario.isDiagnosticoCompletado()) {
            log.info("[ADAPTIVE-ACRA] Entregando prueba ACRA inicial para alumno: {}", usuario.getNombre());
            Map<String, Object> acraResponse = AcraEvaluacion.buildResponse();
            acraResponse.put("nivel_conocimiento_aplicado", "SIN_DIAGNOSTICO");
            return acraResponse;
        }

        // ── Evaluación Formativa o de Refuerzo (iteraciones siguientes) ───────
        TipoEvaluacion tipoEval = TipoEvaluacion.FORMATIVA;
        NivelDificultad dificultadDeseada = mapearNivelConocimientoADificultad(usuario.getNivelConocimiento());

        // Intentar preguntas estáticas de la BD para esta semana y nivel
        List<Pregunta> preguntasEstaticas = preguntaRepository.findBySemanaId(semanaId).stream()
                .filter(p -> p.getNivelDificultad() == dificultadDeseada)
                .collect(Collectors.toList());

        Map<String, Object> resultado;

        if (preguntasEstaticas.size() >= 5) {
            log.info("[ADAPTIVE-STATIC] Usando {} preguntas estáticas (nivel {})", preguntasEstaticas.size(), dificultadDeseada);
            List<Map<String, Object>> preguntasFormateadas = preguntasEstaticas.stream().limit(5).map(p -> {
                List<Map<String, Object>> opciones = p.getRespuestas() != null
                        ? p.getRespuestas().stream().map(r -> Map.<String, Object>of(
                                "texto", r.getRespuesta(),
                                "esCorrecta", r.isValor()
                        )).collect(Collectors.toList())
                        : List.of();
                return Map.<String, Object>of(
                        "id",           p.getId(),
                        "enunciado",    p.getPregunta(),
                        "tipo_pregunta", p.getTipodepregunta().name(),
                        "opciones",     opciones
                );
            }).collect(Collectors.toList());

            resultado = new LinkedHashMap<>();
            resultado.put("tipo_pregunta", preguntasEstaticas.get(0).getTipodepregunta().name());
            resultado.put("nivel_bloom",   dificultadDeseada.name());
            resultado.put("origen",        "ESTATICO");
            resultado.put("preguntas_json", Map.of("preguntas", preguntasFormateadas));
        } else {
            log.info("[ADAPTIVE-DYNAMIC] Generando dinámicamente con RAG (preguntas estáticas insuficientes: {}/5)", preguntasEstaticas.size());
            String nivelBloom = switch (usuario.getNivelConocimiento()) {
                case PRINCIPIANTE -> "Comprender";
                case INTERMEDIO   -> "Analizar";
                case AVANZADO     -> "Evaluar";
            };
            String tipoPregunta = usuario.getNivelConocimiento() == NivelConocimiento.AVANZADO
                    ? "ABIERTA" : "OPCION_MULTIPLE";

            resultado = evaluationOrchestratorAgent.generarEvaluacion(
                    "conceptos principales", semana.getMongoId(),
                    tipoPregunta, nivelBloom, "STRUCTURED_OUTPUT", 5, usuario.getCorreo()
            );
            resultado.put("origen", "DINAMICO");
        }

        resultado.put("tipo_evaluacion",            tipoEval.name());
        resultado.put("nivel_conocimiento_aplicado", usuario.getNivelConocimiento().name());
        return resultado;
    }

    // =========================================================================
    // FASE 2: GUARDAR INTENTO + DEBATE DE AGENTES
    // =========================================================================

    /**
     * Guarda el intento del alumno y decide su nuevo perfil mediante un debate de agentes.
     * Soporta tanto respuestas ACRA (Likert) como evaluaciones formativas normales.
     *
     * @return Transcripción del debate y decisión de los agentes.
     */
    @Transactional
    public Map<String, Object> guardarIntentoConDebate(GuardarIntentoAdaptativoRequest request) {

        Usuario usuario = userRepository.findById(request.usuarioId())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        Semana semana = semanaRepository.findById(request.semanaId())
                .orElseThrow(() -> new RuntimeException("Semana no encontrada"));

        boolean esAcra = request.tipoEvaluacion() == TipoEvaluacion.DIAGNOSTICA
                && request.respuestas().stream()
                        .anyMatch(r -> "ACRA_LIKERT".equals(r.tipoPregunta()));

        // ── Guardar Intento en BD ─────────────────────────────────────────────
        Intento intento = new Intento();
        intento.setUsuario(usuario);
        intento.setSemana(semana);
        intento.setNota(request.notaFinal());
        intento.setFecha(LocalDateTime.now());
        intento.setTiempoEmpleadoSegundos(request.tiempoEmpleadoSegundos());
        intento.setNumeroIntentos(request.numeroIntentos());
        intento.setTipoEvaluacion(request.tipoEvaluacion());
        intentoRepository.save(intento);

        List<String> preguntasFalladasTexto = new ArrayList<>();

        // ── Guardar respuestas del alumno ─────────────────────────────────────
        for (var pyr : request.respuestas()) {
            Pregunta pregunta = new Pregunta();
            pregunta.setPregunta(pyr.preguntaTexto());
            pregunta.setSemana(semana);
            pregunta.setTipodepregunta("ABIERTA".equals(pyr.tipoPregunta()) ? Tipo.Responder : Tipo.Opcion_Multiple);
            Pregunta preguntaGuardada = preguntaRepository.save(pregunta);

            RespuestaUsuario resUsuario = new RespuestaUsuario();
            resUsuario.setUsuario(usuario);
            resUsuario.setPregunta(preguntaGuardada);
            resUsuario.setRespuestaTexto(pyr.respuestaEstudiante());
            resUsuario.setCorrecta(pyr.esCorrecta());
            resUsuario.setFechaCreacion(LocalDateTime.now());
            resUsuario.setIntento(intento);
            respuestaUsuarioRepository.save(resUsuario);

            if (!pyr.esCorrecta() && !"ACRA_LIKERT".equals(pyr.tipoPregunta())) {
                preguntasFalladasTexto.add(pyr.preguntaTexto());
            }
        }

        // ── Calcular puntaje ACRA si corresponde ─────────────────────────────
        Map<String, Object> acraDetalle = null;
        if (esAcra) {
            List<Integer> valoresLikert = request.respuestas().stream()
                    .map(r -> {
                        try { return Integer.parseInt(r.respuestaEstudiante()); }
                        catch (NumberFormatException e) { return 2; }
                    })
                    .collect(Collectors.toList());
            acraDetalle = AcraEvaluacion.calcularPuntajePorEscala(valoresLikert);
            log.info("[ADAPTIVE-ACRA] Puntaje total: {} | Nivel determinado: {}",
                    acraDetalle.get("total"), acraDetalle.get("nivel_determinado"));
        }

        // ── Debate de Agentes ─────────────────────────────────────────────────
        Map<String, Object> debateResultado = ejecutarDebateDeAgentes(usuario, request, esAcra, acraDetalle);

        // ── Aplicar decisión del debate al perfil del alumno ──────────────────
        String nuevoNivelStr = (String) debateResultado.getOrDefault("nuevo_nivel", "PRINCIPIANTE");
        NivelConocimiento nuevoNivel = NivelConocimiento.valueOf(nuevoNivelStr.toUpperCase());
        usuario.setNivelConocimiento(nuevoNivel);

        if (esAcra) {
            usuario.setDiagnosticoCompletado(true);
        }

        String conceptosReforzar = (String) debateResultado.getOrDefault("conceptos_a_reforzar", "");
        if (conceptosReforzar != null && !conceptosReforzar.isEmpty()) {
            String actual = usuario.getDificultadesDetectadas();
            usuario.setDificultadesDetectadas(actual != null && !actual.isEmpty()
                    ? actual + ", " + conceptosReforzar
                    : conceptosReforzar);
        }

        userRepository.save(usuario);
        log.info("[ADAPTIVE] Perfil actualizado. Alumno='{}' → Nivel='{}' (Debate completado)",
                usuario.getNombre(), nuevoNivel);

        // ── Ensamblar respuesta final ─────────────────────────────────────────
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("message", "Evaluación procesada y perfil actualizado mediante debate de agentes.");
        respuesta.put("nivel_anterior", nuevoNivel.name()); // Para contraste en frontend
        respuesta.put("nivel_nuevo",    nuevoNivel.name());
        respuesta.put("debate",         debateResultado);
        if (acraDetalle != null) {
            respuesta.put("acra_detalle", acraDetalle);
        }
        return respuesta;
    }

    // =========================================================================
    // DEBATE DE AGENTES (Gemini simula 3 agentes deliberando)
    // =========================================================================

    private Map<String, Object> ejecutarDebateDeAgentes(
            Usuario usuario,
            GuardarIntentoAdaptativoRequest request,
            boolean esAcra,
            Map<String, Object> acraDetalle) {

        String contextoEvaluacion;

        if (esAcra && acraDetalle != null) {
            contextoEvaluacion = String.format("""
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
            contextoEvaluacion = String.format("""
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

        String prompt = String.format("""
        Actúa como un comité educativo virtual compuesto por 3 agentes especializados.
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
        [Agente Coordinador]: Sintetiza ambas visiones, resuelve diferencias y define el nivel final y las acciones.

        INSTRUCCIONES:
        - Escribe exactamente 3 intervenciones (una por agente) en orden: Evaluador → Psicopedagogo → Coordinador.
        - Cada intervención: máximo 2 oraciones concretas.
        - El Coordinador SIEMPRE cierra con una decisión clara de nivel y recomendaciones.
        - Responde ÚNICAMENTE con un objeto JSON válido sin bloques markdown, con esta estructura exacta:
        {
          "debate_transcripcion": "[Agente Evaluador]: ...\\n[Agente Psicopedagogo]: ...\\n[Agente Coordinador]: ...",
          "nuevo_nivel": "PRINCIPIANTE" | "INTERMEDIO" | "AVANZADO",
          "conceptos_a_reforzar": "concepto1, concepto2",
          "recomendaciones": ["Recomendación 1", "Recomendación 2", "Recomendación 3"]
        }
        """, usuario.getNombre(), usuario.getNivelConocimiento(),
                Optional.ofNullable(usuario.getDificultadesDetectadas()).orElse("Ninguna"),
                contextoEvaluacion);

        try {
            String raw = geminiService.askGemini(prompt).text();
            String clean = raw.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
            int s = clean.indexOf("{"), e = clean.lastIndexOf("}");
            if (s != -1 && e > s) clean = clean.substring(s, e + 1);
            return objectMapper.readValue(clean, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            log.error("[ADAPTIVE-DEBATE] Error en debate de agentes, aplicando fallback: {}", ex.getMessage());
            return fallbackDebate(usuario.getNivelConocimiento(), request.notaFinal(), esAcra);
        }
    }

    /**
     * Fallback de reglas estáticas si el debate de IA falla.
     */
    private Map<String, Object> fallbackDebate(NivelConocimiento nivelActual, Double nota, boolean esAcra) {
        NivelConocimiento nuevoNivel = nivelActual;
        if (!esAcra && nota != null) {
            if (nota >= 16.0 && nivelActual == NivelConocimiento.PRINCIPIANTE) nuevoNivel = NivelConocimiento.INTERMEDIO;
            else if (nota >= 16.0 && nivelActual == NivelConocimiento.INTERMEDIO) nuevoNivel = NivelConocimiento.AVANZADO;
            else if (nota < 11.0 && nivelActual == NivelConocimiento.AVANZADO) nuevoNivel = NivelConocimiento.INTERMEDIO;
            else if (nota < 11.0 && nivelActual == NivelConocimiento.INTERMEDIO) nuevoNivel = NivelConocimiento.PRINCIPIANTE;
        }
        return Map.of(
                "debate_transcripcion", "[Sistema]: Debate no disponible. Reglas de contingencia aplicadas.",
                "nuevo_nivel", nuevoNivel.name(),
                "conceptos_a_reforzar", "conceptos generales del tema evaluado",
                "recomendaciones", List.of("Repasar el material didáctico asignado a la semana.")
        );
    }

    // =========================================================================
    // FASE 3: RECOMENDACIÓN DE MATERIALES
    // =========================================================================

    @Transactional(readOnly = true)
    public List<Material> recomendarMateriales(Long usuarioId, Long semanaId) {
        Usuario usuario = userRepository.findById(usuarioId)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        List<Material> todosLosMateriales = materialRepository.findAll().stream()
                .filter(m -> m.getSemana() != null && m.getSemana().getId().equals(semanaId) && m.isVisible())
                .collect(Collectors.toList());

        NivelDificultad dificultadIdeal = mapearNivelConocimientoADificultad(usuario.getNivelConocimiento());
        String dificultades = usuario.getDificultadesDetectadas() != null
                ? usuario.getDificultadesDetectadas().toLowerCase() : "";

        return todosLosMateriales.stream()
                .filter(m -> {
                    boolean nivelCoincide = m.getNivelDificultad() == dificultadIdeal;
                    boolean tieneDificultadRelacionada = false;
                    if (m.getTagsConceptos() != null && !dificultades.isEmpty()) {
                        for (String tag : m.getTagsConceptos().toLowerCase().split("\\s*,\\s*")) {
                            if (dificultades.contains(tag)) { tieneDificultadRelacionada = true; break; }
                        }
                    }
                    return nivelCoincide || tieneDificultadRelacionada;
                })
                .collect(Collectors.toList());
    }

    // =========================================================================
    // UTILITARIOS
    // =========================================================================

    private NivelDificultad mapearNivelConocimientoADificultad(NivelConocimiento nivel) {
        if (nivel == null) return NivelDificultad.FACIL;
        return switch (nivel) {
            case PRINCIPIANTE -> NivelDificultad.FACIL;
            case INTERMEDIO   -> NivelDificultad.INTERMEDIO;
            case AVANZADO     -> NivelDificultad.PROFUNDIZACION;
        };
    }
}
