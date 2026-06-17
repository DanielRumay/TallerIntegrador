package com.example.tallerintegrador.service;

import com.example.tallerintegrador.DTO.GuardarIntentoAdaptativoRequest;
import com.example.tallerintegrador.agents.EvaluationOrchestratorAgent;
import com.example.tallerintegrador.entidades.postgres.*;
import com.example.tallerintegrador.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

    /**
     * Genera una evaluación adaptada al perfil actual del estudiante.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> generarEvaluacionAdaptativa(Long usuarioId, Long semanaId) {
        Usuario usuario = userRepository.findById(usuarioId)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado con ID: " + usuarioId));
        Semana semana = semanaRepository.findById(semanaId)
                .orElseThrow(() -> new RuntimeException("Semana no encontrada con ID: " + semanaId));

        // Inicializar nivel si no tiene uno asignado
        if (usuario.getNivelConocimiento() == null) {
            usuario.setNivelConocimiento(NivelConocimiento.PRINCIPIANTE);
        }

        TipoEvaluacion tipoEval = usuario.isDiagnosticoCompletado() 
                ? TipoEvaluacion.FORMATIVA 
                : TipoEvaluacion.DIAGNOSTICA;

        String nivelBloom;
        String tipoPregunta;

        if (tipoEval == TipoEvaluacion.DIAGNOSTICA) {
            // Prueba diagnóstica estándar
            nivelBloom = "Analizar";
            tipoPregunta = "OPCION_MULTIPLE";
            log.info("[ADAPTIVE] Generando evaluación DIAGNÓSTICA inicial para el alumno: {}", usuario.getNombre());
        } else {
            // Ajustar según nivel del estudiante
            switch (usuario.getNivelConocimiento()) {
                case PRINCIPIANTE -> {
                    nivelBloom = "Comprender";
                    tipoPregunta = "OPCION_MULTIPLE";
                }
                case INTERMEDIO -> {
                    nivelBloom = "Analizar";
                    tipoPregunta = "OPCION_MULTIPLE";
                }
                case AVANZADO -> {
                    nivelBloom = "Evaluar";
                    tipoPregunta = "ABIERTA";
                }
                default -> {
                    nivelBloom = "Comprender";
                    tipoPregunta = "OPCION_MULTIPLE";
                }
            }
            log.info("[ADAPTIVE] Generando evaluación {} para el alumno: {} (Nivel actual: {})", 
                    tipoEval, usuario.getNombre(), usuario.getNivelConocimiento());
        }

        // Obtener tema de RAG desde el archivo asignado a la semana
        String tema = "conceptos principales";
        String archivoId = semana.getMongoId(); // MongoDB reference

        Map<String, Object> evaluacion = evaluationOrchestratorAgent.generarEvaluacion(
                tema, archivoId, tipoPregunta, nivelBloom, "STRUCTURED_OUTPUT", 5, usuario.getCorreo()
        );

        // Decorar con metadatos adaptativos para el frontend
        evaluacion.put("tipo_evaluacion", tipoEval.name());
        evaluacion.put("nivel_conocimiento_aplicado", usuario.getNivelConocimiento().name());

        return evaluacion;
    }

    /**
     * Guarda el intento y actualiza el perfil adaptativo del estudiante.
     */
    @Transactional
    public void guardarIntentoAdaptativo(GuardarIntentoAdaptativoRequest request) {
        Usuario usuario = userRepository.findById(request.usuarioId())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        Semana semana = semanaRepository.findById(request.semanaId())
                .orElseThrow(() -> new RuntimeException("Semana no encontrada"));

        // 1. Guardar Intento
        Intento intento = new Intento();
        intento.setUsuario(usuario);
        intento.setSemana(semana);
        intento.setNota(request.notaFinal());
        intento.setFecha(LocalDateTime.now());
        intento.setTiempoEmpleadoSegundos(request.tiempoEmpleadoSegundos());
        intento.setNumeroIntentos(request.numeroIntentos());
        intento.setTipoEvaluacion(request.tipoEvaluacion());
        intentoRepository.save(intento);

        List<String> preguntasFalladas = new ArrayList<>();

        // 2. Guardar Preguntas y Respuestas del Alumno
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

            if (!pyr.esCorrecta()) {
                preguntasFalladas.add(pyr.preguntaTexto());
            }
        }

        // 3. Actualizar Perfil Adaptativo
        if (request.tipoEvaluacion() == TipoEvaluacion.DIAGNOSTICA) {
            usuario.setDiagnosticoCompletado(true);
        }

        // Ajustar Nivel de Conocimiento basado en el puntaje
        double score = request.notaFinal();
        NivelConocimiento nivelActual = usuario.getNivelConocimiento() != null 
                ? usuario.getNivelConocimiento() 
                : NivelConocimiento.PRINCIPIANTE;

        if (score >= 16.0) { // Nivel alto -> Sube de nivel
            if (nivelActual == NivelConocimiento.PRINCIPIANTE) {
                usuario.setNivelConocimiento(NivelConocimiento.INTERMEDIO);
            } else if (nivelActual == NivelConocimiento.INTERMEDIO) {
                usuario.setNivelConocimiento(NivelConocimiento.AVANZADO);
            }
        } else if (score < 11.0) { // Nivel bajo -> Baja de nivel
            if (nivelActual == NivelConocimiento.AVANZADO) {
                usuario.setNivelConocimiento(NivelConocimiento.INTERMEDIO);
            } else if (nivelActual == NivelConocimiento.INTERMEDIO) {
                usuario.setNivelConocimiento(NivelConocimiento.PRINCIPIANTE);
            }
        }

        // Analizar preguntas falladas usando IA para actualizar dificultades
        if (!preguntasFalladas.isEmpty()) {
            String dificultadesExtraidas = extraerDificultadesConIA(preguntasFalladas);
            if (usuario.getDificultadesDetectadas() == null || usuario.getDificultadesDetectadas().isEmpty()) {
                usuario.setDificultadesDetectadas(dificultadesExtraidas);
            } else {
                usuario.setDificultadesDetectadas(usuario.getDificultadesDetectadas() + ", " + dificultadesExtraidas);
            }
        }

        userRepository.save(usuario);
        log.info("[ADAPTIVE] Perfil de estudiante '{}' actualizado. Nota: {}. Nuevo nivel: {}", 
                usuario.getNombre(), score, usuario.getNivelConocimiento());
    }

    /**
     * Recomienda materiales didácticos basados en dificultades detectadas y nivel.
     */
    @Transactional(readOnly = true)
    public List<Material> recomendarMateriales(Long usuarioId, Long semanaId) {
        Usuario usuario = userRepository.findById(usuarioId)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        // Obtener todos los materiales de la semana
        List<Material> todosLosMateriales = materialRepository.findAll().stream()
                .filter(m -> m.getSemana() != null && m.getSemana().getId().equals(semanaId) && m.isVisible())
                .collect(Collectors.toList());

        NivelDificultad dificultadIdeal = mapearNivelConocimientoADificultad(usuario.getNivelConocimiento());
        String dificultades = usuario.getDificultadesDetectadas() != null ? usuario.getDificultadesDetectadas().toLowerCase() : "";

        return todosLosMateriales.stream()
                .filter(m -> {
                    // Criterio 1: Nivel de dificultad adecuado
                    boolean nivelCoincide = m.getNivelDificultad() == dificultadIdeal;
                    
                    // Criterio 2: Coincidencia en tags conceptuales con sus dificultades
                    boolean tieneDificultadRelacionada = false;
                    if (m.getTagsConceptos() != null && !dificultades.isEmpty()) {
                        List<String> tags = Arrays.asList(m.getTagsConceptos().toLowerCase().split("\\s*,\\s*"));
                        for (String tag : tags) {
                            if (dificultades.contains(tag)) {
                                tieneDificultadRelacionada = true;
                                break;
                            }
                        }
                    }
                    return nivelCoincide || tieneDificultadRelacionada;
                })
                .collect(Collectors.toList());
    }

    private NivelDificultad mapearNivelConocimientoADificultad(NivelConocimiento nivel) {
        if (nivel == null) return NivelDificultad.FACIL;
        return switch (nivel) {
            case PRINCIPIANTE -> NivelDificultad.FACIL;
            case INTERMEDIO -> NivelDificultad.INTERMEDIO;
            case AVANZADO -> NivelDificultad.PROFUNDIZACION;
        };
    }

    private String extraerDificultadesConIA(List<String> preguntasFalladas) {
        String prompt = "Dada la siguiente lista de preguntas falladas por un estudiante en una evaluación, extrae de 1 a 3 palabras clave o conceptos cortos en español que representen los temas específicos que el alumno debe estudiar de nuevo. Responde únicamente con los conceptos separados por comas, sin introducciones ni marcas markdown.\n\nPreguntas:\n" + String.join("\n", preguntasFalladas);
        try {
            return geminiService.askGemini(prompt).text().trim();
        } catch (Exception e) {
            log.error("[ADAPTIVE] Error extrayendo dificultades con IA: {}", e.getMessage());
            return "conceptos generales";
        }
    }
}
