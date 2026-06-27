package com.example.tallerintegrador.service;

import com.example.tallerintegrador.DTO.GuardarIntentoRequest;
import com.example.tallerintegrador.agents.EvaluationOrchestratorAgent;
import com.example.tallerintegrador.entidades.postgres.*;
import com.example.tallerintegrador.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdaptiveService {

    private final UserRepository userRepository;
    private final SemanaRepository semanaRepository;
    private final IntentoRepository intentoRepository;
    private final PreguntaRepository preguntaRepository;
    private final RespuestaUsuarioRepository respuestaUsuarioRepository;
    private final MaterialRepository materialRepository;
    private final EvaluationOrchestratorAgent orchestratorAgent;
    private final GeminiService geminiService;

    // ─────────────────────────────────────────────────────────────────
    // ACRA items — cuestionario de estrategias de aprendizaje
    // ─────────────────────────────────────────────────────────────────
    private static final List<Map<String, Object>> ACRA_ITEMS = List.of(
            acraItem(1, "Cuando estudio, hago preguntas sobre el contenido para verificar si lo entiendo.", "COMPRENSION"),
            acraItem(2, "Cuando encuentro un término desconocido, busco su significado.", "COMPRENSION"),
            acraItem(3, "Antes de estudiar un tema, recuerdo lo que ya sé sobre él.", "COMPRENSION"),
            acraItem(4, "Relaciono lo que aprendo con experiencias de mi vida cotidiana.", "ELABORACION"),
            acraItem(5, "Creo ejemplos propios para entender mejor los conceptos.", "ELABORACION"),
            acraItem(6, "Cuando estudio, hago resúmenes o esquemas para organizar la información.", "ORGANIZACION"),
            acraItem(7, "Utilizo mapas mentales o conceptuales al estudiar.", "ORGANIZACION"),
            acraItem(8, "Planifico el tiempo que voy a dedicar al estudio.", "PLANIFICACION"),
            acraItem(9, "Me fijo metas o propósitos antes de comenzar a estudiar.", "PLANIFICACION"),
            acraItem(10, "Evalúo si las estrategias que uso me están ayudando a aprender.", "METACOGNICION")
    );

    private static Map<String, Object> acraItem(int id, String enunciado, String escala) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("enunciado", enunciado);
        item.put("escala", escala);
        return item;
    }

    // ─────────────────────────────────────────────────────────────────
    // GET /evaluacion  → devuelve la evaluación que le toca al alumno
    // ─────────────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public Map<String, Object> obtenerEvaluacion(Long usuarioId, Long semanaId) {
        userRepository.findById(usuarioId)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado: " + usuarioId));
        Semana semana = semanaRepository.findById(semanaId)
                .orElseThrow(() -> new RuntimeException("Semana no encontrada: " + semanaId));

        // ¿El alumno ya tiene intentos en ESTA semana?
        List<Intento> intentosPrevios = intentoRepository.findByUsuarioIdOrderByFechaDesc(usuarioId)
                .stream().filter(i -> i.getSemana().getId().equals(semanaId)).toList();

        boolean esDiagnostico = intentosPrevios.isEmpty();

        Map<String, Object> response = new LinkedHashMap<>();

        if (esDiagnostico) {
            // ── DIAGNÓSTICO ACRA ──────────────────────────────────────
            response.put("tipo_evaluacion", "DIAGNOSTICA");
            response.put("instrumento", "ACRA");
            response.put("descripcion", "Cuestionario de Estrategias de Aprendizaje");
            response.put("items", ACRA_ITEMS);
            response.put("opciones_likert", List.of(
                    Map.of("valor", "NUNCA", "etiqueta", "Nunca o casi nunca"),
                    Map.of("valor", "POCAS_VECES", "etiqueta", "Pocas veces"),
                    Map.of("valor", "BASTANTES_VECES", "etiqueta", "Bastantes veces"),
                    Map.of("valor", "SIEMPRE", "etiqueta", "Siempre o casi siempre")
            ));
        } else {
            // ── FORMATIVA — generada por el Orchestrator Agent ────────
            String mongoId = semana.getMongoId();
            String tema = semana.getCurso() != null
                    ? semana.getCurso().getNombre() + " - Semana " + semana.getNumSem()
                    : "Semana " + semana.getNumSem();

            // Determinar nivel según la nota promedio del alumno
            double notaPromedio = intentosPrevios.stream()
                    .mapToDouble(i -> i.getNota() != null ? i.getNota() : 0.0)
                    .average().orElse(0.0);

            String nivelBloom = determinarNivelBloom(notaPromedio);

            Map<String, Object> evaluacion = orchestratorAgent.generarEvaluacion(
                    tema, mongoId, "OPCION_MULTIPLE", nivelBloom, "STRUCTURED_OUTPUT", 5
            );

            response.put("tipo_evaluacion", "FORMATIVA");
            response.put("instrumento", "FORMATIVA_ADAPTATIVA");
            response.put("nivel_bloom", nivelBloom);
            response.put("semana_id", semanaId);
            response.put("preguntas_json", evaluacion.get("preguntas_json"));
        }

        return response;
    }

    // ─────────────────────────────────────────────────────────────────
    // POST /guardar  → guarda el intento y dispara el debate de agentes
    // ─────────────────────────────────────────────────────────────────
    @Transactional
    public Map<String, Object> guardarIntentoAdaptativo(GuardarIntentoRequest request) {
        Usuario usuario = userRepository.findById(request.usuarioId())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        Semana semana = semanaRepository.findById(request.semanaId())
                .orElseThrow(() -> new RuntimeException("Semana no encontrada"));

        // 1. Guardar el Intento principal
        Intento intento = new Intento();
        intento.setUsuario(usuario);
        intento.setSemana(semana);
        intento.setNota(request.notaFinal());
        intento.setFecha(LocalDateTime.now());
        intentoRepository.save(intento);

        // 2. Guardar preguntas y respuestas
        if (request.respuestas() != null) {
            for (var detalle : request.respuestas()) {
                Pregunta pregunta = new Pregunta();
                pregunta.setPregunta(detalle.preguntaTexto());
                pregunta.setSemana(semana);
                pregunta.setTipodepregunta(
                        "ABIERTA".equals(detalle.tipoPregunta()) ? Tipo.Responder : Tipo.Opcion_Multiple
                );
                Pregunta preguntaGuardada = preguntaRepository.save(pregunta);

                RespuestaUsuario resUsuario = new RespuestaUsuario();
                resUsuario.setUsuario(usuario);
                resUsuario.setPregunta(preguntaGuardada);
                resUsuario.setRespuestaTexto(detalle.respuestaEstudiante());
                resUsuario.setCorrecta(detalle.esCorrecta());
                resUsuario.setFechaCreacion(LocalDateTime.now());
                resUsuario.setIntento(intento);
                respuestaUsuarioRepository.save(resUsuario);
            }
        }

        // 3. Calcular el nuevo nivel del alumno
        double nota = request.notaFinal() != null ? request.notaFinal() : 0.0;
        String nivelNuevo = calcularNivelNuevo(nota, request.tipoEvaluacion());

        // 4. Generar el debate de agentes con Gemini
        String debateTranscripcion = generarDebateAgentes(usuario, nota, nivelNuevo, request);

        // 5. Calcular recomendaciones de modos de aprendizaje
        List<String> recomendaciones = calcularRecomendaciones(nivelNuevo, nota);

        // 6. Ensamblar respuesta
        Map<String, Object> debateObj = new LinkedHashMap<>();
        debateObj.put("debate_transcripcion", debateTranscripcion);
        debateObj.put("recomendaciones", recomendaciones);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("intento_id", intento.getId());
        response.put("nota_final", nota);
        response.put("nivel_nuevo", nivelNuevo);
        response.put("tipo_evaluacion", request.tipoEvaluacion());
        response.put("debate", debateObj);

        return response;
    }

    // ─────────────────────────────────────────────────────────────────
    // GET /materiales-recomendados
    // ─────────────────────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<Map<String, Object>> obtenerMaterialesRecomendados(Long usuarioId, Long semanaId) {
        semanaRepository.findById(semanaId)
                .orElseThrow(() -> new RuntimeException("Semana no encontrada"));

        return materialRepository.findBySemanaId(semanaId).stream()
                .filter(Material::isVisible)
                .map(m -> {
                    Map<String, Object> mat = new LinkedHashMap<>();
                    mat.put("id", m.getId());
                    mat.put("mongoId", m.getMongoId());
                    mat.put("nombreArchivo", m.getNombreArchivo());
                    mat.put("fechaCarga", m.getFechaCarga() != null ? m.getFechaCarga().toString() : null);
                    return mat;
                }).toList();
    }

    // ─────────────────────────────────────────────────────────────────
    // Métodos auxiliares privados
    // ─────────────────────────────────────────────────────────────────

    private String determinarNivelBloom(double notaPromedio) {
        if (notaPromedio >= 17) return "Crear";
        if (notaPromedio >= 14) return "Evaluar";
        if (notaPromedio >= 11) return "Analizar";
        if (notaPromedio >= 8)  return "Aplicar";
        if (notaPromedio >= 5)  return "Comprender";
        return "Recordar";
    }

    private String calcularNivelNuevo(double nota, String tipoEvaluacion) {
        if ("DIAGNOSTICA".equals(tipoEvaluacion)) return "PRINCIPIANTE";
        if (nota >= 18) return "AVANZADO";
        if (nota >= 14) return "INTERMEDIO_AVANZADO";
        if (nota >= 10) return "INTERMEDIO";
        return "PRINCIPIANTE";
    }

    private List<String> calcularRecomendaciones(String nivel, double nota) {
        if ("PRINCIPIANTE".equals(nivel)) {
            return List.of("video explicativo", "opción múltiple");
        } else if (nivel.contains("INTERMEDIO")) {
            return List.of("opción múltiple", "verdadero / falso", "hablar con el avatar");
        } else {
            return List.of("hablar con el avatar", "pregunta abierta", "detección de errores");
        }
    }

    private String generarDebateAgentes(Usuario usuario, double nota, String nivelNuevo,
                                         GuardarIntentoRequest request) {
        try {
            int total = 0;
            int correctas = 0;
            if (request.respuestas() != null) {
                total = request.respuestas().size();
                correctas = (int) request.respuestas().stream()
                        .filter(GuardarIntentoRequest.RespuestaDetalle::esCorrecta).count();
            }

            String prompt = """
                    Eres el sistema de un comité académico pedagógico virtual. Simula un debate
                    breve entre 4 agentes educativos que analizan el desempeño de un estudiante.
                    Usa el siguiente formato EXACTO para cada turno (sin markdown, sin listas):

                    [Agente Evaluador]: <mensaje>
                    [Agente Psicopedagogo]: <mensaje>
                    [Agente de Adaptación de Evaluaciones]: <mensaje>
                    [Agente Coordinador]: <mensaje>

                    Datos del estudiante:
                    - Nombre: %s
                    - Tipo de evaluación: %s
                    - Nota obtenida: %.1f / 20
                    - Respuestas correctas: %d de %d
                    - Nivel asignado: %s

                    El debate debe ser breve (máximo 2 turnos por agente), profesional,
                    mencionar el nivel asignado y las recomendaciones pedagógicas concretas.
                    Responde ÚNICAMENTE con el texto del debate, sin ninguna introducción ni cierre.
                    """.formatted(
                    usuario.getNombre(),
                    request.tipoEvaluacion() != null ? request.tipoEvaluacion() : "FORMATIVA",
                    nota, correctas, total, nivelNuevo
            );

            return geminiService.askGemini(prompt).text();
        } catch (Exception e) {
            log.error("[ADAPTIVE] Error al generar debate de agentes: {}", e.getMessage());
            return "[Agente Coordinador]: Se ha procesado tu evaluación y se ha asignado el nivel "
                    + nivelNuevo + " de acuerdo a tu desempeño.";
        }
    }
}
