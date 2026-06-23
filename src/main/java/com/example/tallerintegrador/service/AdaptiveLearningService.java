package com.example.tallerintegrador.service;

import com.example.tallerintegrador.DTO.GuardarIntentoAdaptativoRequest;
import com.example.tallerintegrador.agents.EvaluationOrchestratorAgent;
import com.example.tallerintegrador.agents.EvaluationAdaptationAgent;
import com.example.tallerintegrador.entidades.postgres.*;
import com.example.tallerintegrador.repository.*;
import com.example.tallerintegrador.service.util.IdHasher;
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
    private final EvaluationAdaptationAgent evaluationAdaptationAgent;
    private final IdHasher idHasher;

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
        Semana semana = semanaRepository.findById(idHasher.decode(request.semanaId()))
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
        Map<String, Object> debateResultado = evaluationAdaptationAgent.ejecutarDebate(usuario, request, esAcra, acraDetalle);

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
