package com.example.tallerintegrador.service.analitica;
import com.example.tallerintegrador.service.academico.IntentoService;
import com.example.tallerintegrador.service.ia.GeminiService;
import com.example.tallerintegrador.service.metricas.TelemetriaIAService;

import com.example.tallerintegrador.DTO.GuardarIntentoAdaptativoRequest;
import com.example.tallerintegrador.agents.EvaluationOrchestratorAgent;
import com.example.tallerintegrador.agents.EvaluadorAgent;
import com.example.tallerintegrador.agents.PsicopedagogoAgent;
import com.example.tallerintegrador.agents.CoordinadorAgent;
import com.example.tallerintegrador.agents.EvaluationAdaptationAgent;
import com.example.tallerintegrador.agents.VerificadorAgent;
import com.example.tallerintegrador.agents.committee.Postura;
import com.example.tallerintegrador.repository.DebateAgentesRepository;
import com.example.tallerintegrador.repository.DiagnosticoAcraRepository;
import com.example.tallerintegrador.DTO.GuardarIntentoRequest;
import com.example.tallerintegrador.entidades.postgres.*;
import com.example.tallerintegrador.repository.*;
import com.example.tallerintegrador.service.util.IdHasher;
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
    private final IdHasher idHasher;
    private final EvaluadorAgent evaluadorAgent;
    private final PsicopedagogoAgent psicopedagogoAgent;
    private final CoordinadorAgent coordinadorAgent;
    private final EvaluationAdaptationAgent evaluationAdaptationAgent;
    private final VerificadorAgent verificadorAgent;
    private final DebateAgentesRepository debateAgentesRepository;
    private final TelemetriaIAService telemetriaIAService;
    private final ConocimientoBktService conocimientoBktService;
    private final DiagnosticoAcraRepository diagnosticoAcraRepository;
    private final UbicacionPorBloomService ubicacionPorBloomService;
    private final NivelSemanaAlumnoRepository nivelSemanaAlumnoRepository;
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

        // ── Prueba Diagnóstica ACRA (primera vez en toda la plataforma) ──────
        if (!usuario.isDiagnosticoCompletado()) {
            log.info("[ADAPTIVE-ACRA] Entregando prueba ACRA inicial para alumno: {}", usuario.getNombre());
            Map<String, Object> acraResponse = AcraEvaluacion.buildResponse();
            acraResponse.put("nivel_conocimiento_aplicado", "SIN_DIAGNOSTICO");
            return acraResponse;
        }

        // ── Prueba de UBICACIÓN de esta semana (una vez por semana) ──────────
        //
        // El ACRA se aplica una sola vez y mide estrategias de estudio. La ubicación se
        // aplica en CADA semana y mide desempeño en el tema de esa semana: un alumno puede
        // manejar bien fotosíntesis y estar perdido en genética, y un único nivel global
        // promediaría ambas cosas produciendo preguntas mal calibradas en las dos.
        var ubicacionPrevia = nivelSemanaAlumnoRepository
                .findByUsuarioIdAndSemanaId(usuarioId, semanaId);

        if (ubicacionPrevia.isEmpty()) {
            log.info("[UBICACION] Sin ubicación para la semana {}; se entrega la prueba de ubicación", semanaId);
            return generarPruebaDeUbicacion(usuario, semana);
        }

        NivelConocimiento nivelDeLaSemana = ubicacionPrevia.get().getNivel();

        // ── Evaluación Formativa o de Refuerzo (iteraciones siguientes) ───────
        TipoEvaluacion tipoEval = TipoEvaluacion.FORMATIVA;
        NivelDificultad dificultadDeseada = mapearNivelConocimientoADificultad(nivelDeLaSemana);

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
            // Se usa el nivel DE ESTA SEMANA, no el global del alumno.
            String nivelBloom = switch (nivelDeLaSemana) {
                case PRINCIPIANTE -> "Comprender";
                case INTERMEDIO   -> "Analizar";
                case AVANZADO     -> "Evaluar";
            };
            String tipoPregunta = nivelDeLaSemana == NivelConocimiento.AVANZADO
                    ? "ABIERTA" : "OPCION_MULTIPLE";

            resultado = evaluationOrchestratorAgent.generarEvaluacion(
                    "conceptos principales", semana.getMongoId(),
                    tipoPregunta, nivelBloom, "STRUCTURED_OUTPUT", 5, usuario.getCorreo()
            );
            resultado.put("origen", "DINAMICO");
        }

        resultado.put("tipo_evaluacion",            tipoEval.name());
        resultado.put("nivel_conocimiento_aplicado", nivelDeLaSemana.name());
        resultado.put("nivel_origen", "UBICACION_SEMANAL");
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
        return guardarIntentoConDebate(request, (evento, datos) -> {});
    }

    /**
     * Misma lógica que guardarIntentoConDebate(request), con un punto de extensión: emisor
     * se invoca después de cada turno del debate con el nombre del evento y su payload. El
     * endpoint SSE (ver AdaptiveLearningController.guardarStream) pasa un emisor que reenvía
     * cada turno al frontend en cuanto se produce; la versión síncrona pasa un emisor vacío
     * y se comporta exactamente igual que antes de este cambio.
     */
    @Transactional
    public Map<String, Object> guardarIntentoConDebate(
            GuardarIntentoAdaptativoRequest request,
            java.util.function.BiConsumer<String, Object> emisor) {
        Long decodedSemanaId = idHasher.decode(request.semanaId());

        Usuario usuario = userRepository.findById(request.usuarioId())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        Semana semana = semanaRepository.findById(decodedSemanaId)
                .orElseThrow(() -> new RuntimeException("Semana no encontrada"));

        boolean esAcra = request.tipoEvaluacion() == TipoEvaluacion.DIAGNOSTICA
                && request.respuestas().stream()
                        .anyMatch(r -> "ACRA_LIKERT".equals(r.tipoPregunta()));

        // ── El ACRA NO es un examen: no genera Intento ni nota ────────────────
        // Antes se guardaba como Intento con nota 0.0 y se marcaba DIAGNOSTICA para que
        // cada consulta se acordara de excluirlo. IntentoService nunca lo excluyó, así que
        // ese 0.0 ficticio hundía el promedio del alumno en el dashboard y el historial.
        // Ahora vive en su propia tabla y el error deja de ser posible por construcción.
        Intento intento = null;
        if (!esAcra) {
            intento = new Intento();
            intento.setUsuario(usuario);
            intento.setSemana(semana);
            intento.setNota(request.notaFinal());
            intento.setFecha(LocalDateTime.now());
            intento.setTiempoEmpleadoSegundos(request.tiempoEmpleadoSegundos());
            intento.setNumeroIntentos(request.numeroIntentos());
            intento.setTipoEvaluacion(request.tipoEvaluacion());
            intento.setTecnica("ADAPTATIVA");
            intentoRepository.save(intento);
        }

        List<String> preguntasFalladasTexto = new ArrayList<>();

        // ── Guardar respuestas del alumno ─────────────────────────────────────
        // Las respuestas Likert del ACRA tampoco se guardan como Pregunta/RespuestaUsuario:
        // no son reactivos con respuesta correcta, y contaminarían el histórico de
        // preguntas (y su deduplicación vectorial). Se persisten completas en
        // DiagnosticoAcra.respuestasJson.
        for (var pyr : esAcra ? List.<GuardarIntentoRequest.RespuestaDetalle>of() : request.respuestas()) {
            Pregunta pregunta = new Pregunta();
            pregunta.setPregunta(pyr.preguntaTexto());
            pregunta.setSemana(semana);
            pregunta.setTipodepregunta("ABIERTA".equals(pyr.tipoPregunta()) ? Tipo.Responder : Tipo.Opcion_Multiple);
            pregunta.setNivelBloom(pyr.nivelBloom());
            pregunta.setConceptos(pyr.conceptos());
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
                preguntasFalladasTexto.add(pyr.preguntaTexto());
            }

            // Actualiza el dominio bayesiano del alumno para cada concepto que cubre este
            // reactivo. Se salta en silencio si el frontend aún no envió nivelBloom/
            // conceptos (ver GuardarIntentoRequest.RespuestaDetalle) — no rompe el guardado
            // del intento por un dato que todavía no existe en el contrato del cliente.
            if (pyr.nivelBloom() != null && pyr.conceptos() != null && !pyr.conceptos().isBlank()
                    && !"ACRA_LIKERT".equals(pyr.tipoPregunta())) {
                for (String concepto : pyr.conceptos().split("\\s*,\\s*")) {
                    if (!concepto.isBlank()) {
                        Long cursoId = semana.getCurso() != null ? semana.getCurso().getId() : null;
                        conocimientoBktService.actualizar(usuario.getId(), cursoId,
                                semana != null ? semana.getId() : null,
                                concepto.trim(), pyr.nivelBloom(), pyr.esCorrecta());
                    }
                }
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
            log.info("[ADAPTIVE-ACRA] Puntaje total: {} | Nivel sugerido: {}",
                    acraDetalle.get("total"), acraDetalle.get("nivel_determinado"));

            persistirDiagnosticoAcra(usuario, acraDetalle, valoresLikert, request.tiempoEmpleadoSegundos());
        }

        // ── Debate de Agentes (Multi-Agent System) ────────────────────────────
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
            )
            // Advertencia explícita para el comité sobre qué mide y qué NO mide el ACRA.
            //
            // El ACRA es un autoinforme de ESTRATEGIAS DE ESTUDIO ("¿subrayas lo
            // importante?", "¿repites mentalmente?"). No mide conocimiento del curso ni
            // capacidad cognitiva. Derivar de ahí el nivel de dominio es saltar entre dos
            // constructos distintos. Sin esta advertencia, los agentes tomaban el "nivel
            // sugerido por ACRA" como si fuera una medida de desempeño.
            + """

                ADVERTENCIA SOBRE ESTE INSTRUMENTO: el ACRA mide ESTRATEGIAS DE ESTUDIO
                declaradas por el propio alumno, no su conocimiento del curso ni su nivel
                cognitivo. Un alumno con buenos hábitos puede tener comprensión inferencial
                débil, y al revés. Usa este puntaje para entender CÓMO estudia y qué apoyo
                necesita, NO para decidir qué tan difíciles deben ser sus preguntas. Al no
                haber todavía desempeño observado, ubica al alumno en PRINCIPIANTE y deja que
                las evaluaciones formativas posteriores lo reubiquen.
                """;
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
                %s
                """,
                    request.notaFinal(), request.tiempoEmpleadoSegundos(),
                    request.numeroIntentos(), respuestasResumen,
                    ubicacionEmpirica(request.respuestas())
            );
        }

        // El nivel vigente se captura ANTES de deliberar: es el término de contraste real.
        NivelConocimiento nivelAnterior = usuario.getNivelConocimiento() != null
                ? usuario.getNivelConocimiento()
                : NivelConocimiento.PRINCIPIANTE;

        long inicioDebate = System.currentTimeMillis();
        Postura t1 = evaluadorAgent.generarTurno1(usuario.getId(), contextoEvaluacion);
        emisor.accept("turno", t1);
        Postura t2 = psicopedagogoAgent.generarTurno2(usuario.getId(), contextoEvaluacion, t1);
        emisor.accept("turno", t2);
        Postura t3 = evaluationAdaptationAgent.generarTurno3(usuario.getId(), contextoEvaluacion, t1, t2);
        emisor.accept("turno", t3);
        Postura t4 = psicopedagogoAgent.generarTurno4(usuario.getId(), contextoEvaluacion, t1, t2, t3);
        emisor.accept("turno", t4);
        Map<String, Object> debateResultado =
                coordinadorAgent.generarConsenso(usuario, usuario.getId(), contextoEvaluacion, t1, t2, t3, t4);
        emisor.accept("consenso", debateResultado);
        long latenciaDebate = System.currentTimeMillis() - inicioDebate;

        // ── Propuesta del comité ──────────────────────────────────────────────
        NivelConocimiento nivelPropuesto = parsearNivel(debateResultado.get("nuevo_nivel"), nivelAnterior);

        // ── Guardia determinista: el comité propone, la política dispone ──────
        VerificadorAgent.Veredicto veredicto =
                verificadorAgent.verificar(usuario.getId(), nivelAnterior, nivelPropuesto);
        NivelConocimiento nuevoNivel = veredicto.nivelAplicado();
        usuario.setNivelConocimiento(nuevoNivel);
        emisor.accept("veto", Map.of("aplicado", veredicto.vetado(), "motivo", veredicto.motivo()));

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

        // ── Persistir la deliberación (trazabilidad y auditoría del criterio) ──
        persistirDebate(usuario, intento, nivelAnterior, nivelPropuesto, nuevoNivel,
                veredicto, debateResultado, latenciaDebate, List.of(t1, t2, t3, t4));

        log.info("[ADAPTIVE] Perfil actualizado. Alumno id={} | {} → {} | veto={} | {}ms",
                usuario.getId(), nivelAnterior, nuevoNivel, veredicto.vetado(), latenciaDebate);

        // ── Ensamblar respuesta final ─────────────────────────────────────────
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("message", "Evaluación procesada y perfil actualizado mediante debate de agentes.");
        respuesta.put("nivel_anterior",  nivelAnterior.name());
        respuesta.put("nivel_propuesto", nivelPropuesto.name());
        respuesta.put("nivel_nuevo",     nuevoNivel.name());
        respuesta.put("nivel_cambio",    nivelAnterior != nuevoNivel);
        respuesta.put("veto",            Map.of(
                "aplicado", veredicto.vetado(),
                "motivo",   veredicto.motivo()
        ));
        respuesta.put("notaFinal",      request.notaFinal());
        respuesta.put("debate",         debateResultado);
        if (acraDetalle != null) {
            respuesta.put("acra_detalle", acraDetalle);
        }
        emisor.accept("final", respuesta);
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

    /**
     * Genera la prueba de ubicación de una semana: 6 reactivos, 2 por cada estrato cognitivo.
     *
     * CÓMO FUNCIONA. `UbicacionPorBloomService.composicionDeLaPrueba()` devuelve cuántos
     * reactivos pedir de cada nivel — {Comprender: 2, Analizar: 2, Evaluar: 2} — y aquí se
     * llama al generador una vez por estrato con el pipeline RAG que ya existe. Las preguntas
     * salen del material real de la semana, no de un banco genérico.
     *
     * Los reactivos se MEZCLAN antes de enviarlos. Si el alumno los recibiera agrupados por
     * nivel, notaría que la dificultad sube por bloques y podría rendirse al llegar al
     * tercero, sesgando su ubicación hacia abajo.
     */
    private Map<String, Object> generarPruebaDeUbicacion(Usuario usuario, Semana semana) {
        List<Map<String, Object>> reactivos = new ArrayList<>();

        for (var entrada : ubicacionPorBloomService.composicionDeLaPrueba().entrySet()) {
            String nivelBloom = entrada.getKey();
            int cuantos = entrada.getValue();
            try {
                Map<String, Object> generado = evaluationOrchestratorAgent.generarEvaluacion(
                        "conceptos principales", semana.getMongoId(),
                        // Opción múltiple en todos los estratos: la corrección es objetiva y no
                        // depende del juez de IA, cuya concordancia con docentes todavía no se
                        // ha medido. Basar la ubicación en calificación sin validar sería
                        // apoyar toda la adaptación en un eslabón sin comprobar.
                        "OPCION_MULTIPLE", nivelBloom, "STRUCTURED_OUTPUT", cuantos, usuario.getCorreo());

                Object preguntasJson = generado.get("preguntas_json");
                if (preguntasJson instanceof Map<?, ?> mapa && mapa.get("preguntas") instanceof List<?> lista) {
                    for (Object p : lista) {
                        if (p instanceof Map<?, ?> pregunta) {
                            Map<String, Object> copia = new LinkedHashMap<>();
                            pregunta.forEach((k, v) -> copia.put(String.valueOf(k), v));
                            // Se etiqueta con su estrato: es lo que permitirá aplicar Guttman
                            // al recibir las respuestas.
                            copia.put("nivel_bloom", nivelBloom);
                            reactivos.add(copia);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[UBICACION] No se pudieron generar reactivos de '{}': {}", nivelBloom, e.getMessage());
            }
        }

        Collections.shuffle(reactivos);

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("tipo_evaluacion", "UBICACION");
        respuesta.put("tipo_pregunta", "OPCION_MULTIPLE");
        respuesta.put("origen", "UBICACION_SEMANAL");
        respuesta.put("nivel_conocimiento_aplicado", "SIN_UBICAR");
        respuesta.put("preguntas_json", Map.of("preguntas", reactivos));
        respuesta.put("mensaje_alumno",
                "Antes de empezar, resuelve estas preguntas. No cuentan para tu nota: sirven "
                + "para ajustar la dificultad de esta semana a lo que ya sabes.");

        log.info("[UBICACION] Prueba generada para semana {}: {} reactivos", semana.getId(), reactivos.size());
        return respuesta;
    }

    /**
     * Aplica el escalograma de Guttman a las respuestas de la prueba de ubicación y fija el
     * nivel de esa semana.
     *
     * La prueba de ubicación NO genera Intento ni nota: no es un examen, es una medición
     * previa. Contarla como evaluación hundiría el promedio del alumno con un cero antes de
     * haber estudiado — el mismo defecto que tenía el ACRA antes de separarlo a su tabla.
     */
    @Transactional
    public Map<String, Object> guardarUbicacion(Long usuarioId, String semanaIdHash,
                                                List<GuardarIntentoRequest.RespuestaDetalle> respuestas) {
        Usuario usuario = userRepository.findById(usuarioId)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado: " + usuarioId));
        Long semanaId = idHasher.decode(semanaIdHash);
        Semana semana = semanaRepository.findById(semanaId)
                .orElseThrow(() -> new RuntimeException("Semana no encontrada: " + semanaId));

        List<UbicacionPorBloomService.RespuestaUbicacion> paraUbicar = respuestas.stream()
                .filter(r -> r.nivelBloom() != null && !r.nivelBloom().isBlank())
                .map(r -> new UbicacionPorBloomService.RespuestaUbicacion(r.nivelBloom(), r.esCorrecta()))
                .toList();

        var resultado = ubicacionPorBloomService.determinarNivel(paraUbicar);

        NivelSemanaAlumno registro = nivelSemanaAlumnoRepository
                .findByUsuarioIdAndSemanaId(usuarioId, semanaId)
                .orElseGet(NivelSemanaAlumno::new);
        registro.setUsuario(usuario);
        registro.setSemana(semana);
        registro.setNivel(resultado.nivel());
        registro.setJustificacion(resultado.justificacion());
        registro.setTotalReactivos(paraUbicar.size());
        try {
            registro.setDesempenoJson(objectMapper.writeValueAsString(resultado.desempenoPorEstrato()));
        } catch (Exception e) {
            log.warn("[UBICACION] No se pudo serializar el desempeño: {}", e.getMessage());
        }
        nivelSemanaAlumnoRepository.save(registro);

        log.info("[UBICACION] Alumno {} · semana {} → {} ({})",
                usuarioId, semanaId, resultado.nivel(), resultado.justificacion());

        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("nivel", resultado.nivel().name());
        salida.put("desempenoPorEstrato", resultado.desempenoPorEstrato());
        salida.put("justificacion", resultado.justificacion());
        salida.put("mensajeAlumno", switch (resultado.nivel()) {
            case PRINCIPIANTE -> "Empezaremos por lo esencial de este tema. Vas a ir subiendo.";
            case INTERMEDIO -> "Ya manejas lo básico: iremos a preguntas de análisis.";
            case AVANZADO -> "Dominas el tema. Te plantearemos preguntas de juicio y argumentación.";
        });
        return salida;
    }

    /**
     * Ubicación por DESEMPEÑO OBSERVADO, para que el comité no delibere solo sobre la nota.
     *
     * POR QUÉ ESTO EXISTE. Hasta ahora el comité recibía la nota final y el listado de
     * respuestas, y el nivel de partida venía del ACRA — un autoinforme de hábitos de
     * estudio. Es decir: la decisión sobre qué tan difíciles deben ser las preguntas de un
     * alumno se apoyaba en si dice que subraya, no en qué operaciones cognitivas demostró.
     *
     * `UbicacionPorBloomService` estaba construido y probado desde hace tiempo, pero no lo
     * llamaba nadie. Aquí se conecta: se agrupan las respuestas por nivel de Bloom y se
     * aplica el escalograma de Guttman (1944), que ubica al alumno en el estrato MÁS ALTO
     * donde alcanza el umbral, sin exigir perfección en los inferiores.
     *
     * El resultado NO sustituye al comité: se le entrega como evidencia dura para que
     * delibere sobre ella. Y por encima sigue el veto determinista del Verificador.
     *
     * Si las preguntas no traen nivel de Bloom —el frontend puede no enviarlo— se devuelve
     * un texto que lo dice, en vez de omitir el bloque en silencio.
     */
    private String ubicacionEmpirica(List<GuardarIntentoRequest.RespuestaDetalle> respuestas) {
        try {
            List<UbicacionPorBloomService.RespuestaUbicacion> paraUbicar = respuestas.stream()
                    .filter(r -> r.nivelBloom() != null && !r.nivelBloom().isBlank())
                    .map(r -> new UbicacionPorBloomService.RespuestaUbicacion(r.nivelBloom(), r.esCorrecta()))
                    .toList();

            if (paraUbicar.isEmpty()) {
                return """

                    UBICACIÓN POR DESEMPEÑO: no disponible en este intento (las preguntas no
                    llegaron etiquetadas con su nivel de Bloom). Decide solo con la nota y las
                    respuestas.
                    """;
            }

            var resultado = ubicacionPorBloomService.determinarNivel(paraUbicar);
            return """

                UBICACIÓN POR DESEMPEÑO OBSERVADO (escalograma de Guttman sobre niveles de Bloom):
                  Nivel que respalda la evidencia: %s
                  Desempeño por estrato: %s
                  Criterio: %s

                Esta ubicación se calcula con lo que el alumno RESOLVIÓ, no con lo que declara
                sobre sus hábitos. Tómala como la evidencia más fuerte disponible; si propones
                un nivel distinto, justifica por qué.
                """.formatted(
                    resultado.nivel(), resultado.desempenoPorEstrato(), resultado.justificacion());

        } catch (Exception e) {
            // La ubicación es un apoyo a la deliberación, no un requisito: si falla, el comité
            // decide con lo demás en vez de quedarse sin evaluación que guardar.
            log.warn("[UBICACION-BLOOM] No se pudo calcular la ubicación empírica: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Guarda el perfil ACRA en su tabla propia. Un fallo aquí no debe tumbar el flujo: el
     * alumno ya respondió el cuestionario y el comité ya puede razonar sobre el resultado
     * en memoria.
     */
    private void persistirDiagnosticoAcra(Usuario usuario, Map<String, Object> acraDetalle,
                                          List<Integer> respuestasLikert, Integer tiempoSegundos) {
        try {
            DiagnosticoAcra diagnostico = new DiagnosticoAcra();
            diagnostico.setUsuario(usuario);
            diagnostico.setFecha(LocalDateTime.now());
            diagnostico.setEscalaAdquisicion(puntajeDeEscala(acraDetalle, "escala_I_adquisicion"));
            diagnostico.setEscalaCodificacion(puntajeDeEscala(acraDetalle, "escala_II_codificacion"));
            diagnostico.setEscalaRecuperacion(puntajeDeEscala(acraDetalle, "escala_III_recuperacion"));
            diagnostico.setEscalaApoyo(puntajeDeEscala(acraDetalle, "escala_IV_apoyo"));

            Object total = acraDetalle.get("total");
            diagnostico.setPuntajeTotal(total instanceof Number n ? n.intValue() : null);
            diagnostico.setNivelSugerido(parsearNivel(acraDetalle.get("nivel_determinado"), NivelConocimiento.PRINCIPIANTE));
            diagnostico.setRespuestasJson(textoDe(respuestasLikert));
            diagnostico.setTiempoEmpleadoSegundos(tiempoSegundos);

            diagnosticoAcraRepository.save(diagnostico);
            log.info("[ADAPTIVE-ACRA] Diagnóstico persistido para alumno id={}", usuario.getId());
        } catch (Exception e) {
            log.error("[ADAPTIVE-ACRA] No se pudo persistir el diagnóstico ACRA: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Integer puntajeDeEscala(Map<String, Object> acraDetalle, String clave) {
        Object escala = acraDetalle.get(clave);
        if (escala instanceof Map<?, ?> mapa) {
            Object puntaje = mapa.get("puntaje");
            if (puntaje instanceof Number n) return n.intValue();
        }
        return null;
    }

    /** Convierte el nivel textual del Coordinador en enum, tolerando salidas inesperadas del LLM. */
    private NivelConocimiento parsearNivel(Object crudo, NivelConocimiento porDefecto) {
        if (crudo == null) return porDefecto;
        try {
            return NivelConocimiento.valueOf(crudo.toString().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[ADAPTIVE] Nivel no reconocido en la respuesta del comité: '{}'. Se conserva {}.",
                    crudo, porDefecto);
            return porDefecto;
        }
    }

    private void persistirDebate(Usuario usuario,
                                 Intento intento,
                                 NivelConocimiento nivelAnterior,
                                 NivelConocimiento nivelPropuesto,
                                 NivelConocimiento nivelAplicado,
                                 VerificadorAgent.Veredicto veredicto,
                                 Map<String, Object> debateResultado,
                                 long latenciaMs,
                                 List<Postura> posturas) {
        try {
            DebateAgentes debate = new DebateAgentes();
            debate.setUsuario(usuario);
            debate.setIntento(intento);
            debate.setFecha(LocalDateTime.now());
            debate.setNivelAnterior(nivelAnterior);
            debate.setNivelPropuesto(nivelPropuesto);
            debate.setNivelAplicado(nivelAplicado);
            debate.setVetoAplicado(veredicto.vetado());
            debate.setMotivoVeto(veredicto.vetado() ? veredicto.motivo() : null);
            debate.setDebateTranscripcion(textoDe(debateResultado.get("debate_transcripcion")));
            debate.setPosturasJson(textoDe(posturas));
            debate.setConceptosAReforzar(textoDe(debateResultado.get("conceptos_a_reforzar")));
            debate.setRecomendaciones(textoDe(debateResultado.get("recomendaciones")));
            debate.setUsoFallback(Boolean.TRUE.equals(debateResultado.get("_fallback")));
            debate.setLatenciaTotalMs(latenciaMs);
            debateAgentesRepository.save(debate);

            EventoMetricaIA evento = EventoMetricaIA.de(
                    TipoEventoIA.COMITE_DEBATE,
                    veredicto.vetado() ? "VETO_APLICADO" : "PROPUESTA_ACEPTADA");
            evento.setUsuarioId(usuario.getId());
            evento.setLatenciaMs(latenciaMs);
            evento.setDetalle("%s -> %s (propuesto %s)".formatted(nivelAnterior, nivelAplicado, nivelPropuesto));
            telemetriaIAService.registrar(evento);
        } catch (Exception e) {
            log.error("[ADAPTIVE] No se pudo persistir la traza del debate: {}", e.getMessage());
        }
    }

    private String textoDe(Object valor) {
        if (valor == null) return null;
        if (valor instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(valor);
        } catch (Exception e) {
            return valor.toString();
        }
    }

    private NivelDificultad mapearNivelConocimientoADificultad(NivelConocimiento nivel) {
        if (nivel == null) return NivelDificultad.FACIL;
        return switch (nivel) {
            case PRINCIPIANTE -> NivelDificultad.FACIL;
            case INTERMEDIO   -> NivelDificultad.INTERMEDIO;
            case AVANZADO     -> NivelDificultad.PROFUNDIZACION;
        };
    }
}
