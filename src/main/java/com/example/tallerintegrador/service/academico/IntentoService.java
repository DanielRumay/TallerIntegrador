package com.example.tallerintegrador.service.academico;
import com.example.tallerintegrador.service.analitica.AdaptiveLearningService;
import com.example.tallerintegrador.service.academico.VocabularioConceptosService;
import com.example.tallerintegrador.service.analitica.ConocimientoBktService;

import com.example.tallerintegrador.DTO.GuardarIntentoRequest;
import com.example.tallerintegrador.entidades.postgres.*;
import com.example.tallerintegrador.repository.*;
import com.example.tallerintegrador.service.util.IdHasher;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.embedding.Embedding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.util.stream.Collectors;

@Slf4j
@Service
public class IntentoService {

    private final IntentoRepository intentoRepository;
    private final UserRepository userRepository;
    private final SemanaRepository semanaRepository;
    private final PreguntaRepository preguntaRepository;
    private final RespuestaUsuarioRepository respuestaUsuarioRepository;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> questionsEmbeddingStore;
    private final PlatformTransactionManager transactionManager;
    private final IdHasher idHasher;
    private final ConocimientoBktService conocimientoBktService;
    private final VocabularioConceptosService vocabularioConceptosService;

    private record PreguntaIndexarInfo(Long preguntaId, String preguntaTexto) {}

    public IntentoService(
            IntentoRepository intentoRepository,
            UserRepository userRepository,
            SemanaRepository semanaRepository,
            PreguntaRepository preguntaRepository,
            RespuestaUsuarioRepository respuestaUsuarioRepository,
            EmbeddingModel embeddingModel,
            @Qualifier("questionsEmbeddingStore") EmbeddingStore<TextSegment> questionsEmbeddingStore,
            PlatformTransactionManager transactionManager,
            IdHasher idHasher,
            ConocimientoBktService conocimientoBktService,
            VocabularioConceptosService vocabularioConceptosService) {
        this.intentoRepository = intentoRepository;
        this.userRepository = userRepository;
        this.semanaRepository = semanaRepository;
        this.preguntaRepository = preguntaRepository;
        this.respuestaUsuarioRepository = respuestaUsuarioRepository;
        this.embeddingModel = embeddingModel;
        this.questionsEmbeddingStore = questionsEmbeddingStore;
        this.transactionManager = transactionManager;
        this.idHasher = idHasher;
        this.conocimientoBktService = conocimientoBktService;
        this.vocabularioConceptosService = vocabularioConceptosService;
    }

    public void guardarIntentoCompleto(GuardarIntentoRequest request) {
        List<PreguntaIndexarInfo> preguntasIndexar = new ArrayList<>();
        Long decodedSemanaId = idHasher.decode(request.semanaId());

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            Usuario usuario = userRepository.findById(request.usuarioId())
                    .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
            Semana semana = semanaRepository.findById(decodedSemanaId)
                    .orElseThrow(() -> new RuntimeException("Semana no encontrada"));

            // 1. Guardar el Intento principal
            Intento intento = new Intento();
            intento.setUsuario(usuario);
            intento.setSemana(semana);
            intento.setNota(request.notaFinal());
            intento.setFecha(LocalDateTime.now());
            // Este flujo es el de práctica normal: siempre es una evaluación formativa.
            // Antes no se asignaba, y la columna quedaba NULL en cada intento del alumno.
            intento.setTipoEvaluacion(TipoEvaluacion.FORMATIVA);
            intento.setTecnica(request.tecnica());
            intentoRepository.save(intento);

            // 2. Guardar las preguntas generadas y las respuestas del alumno en BD local
            for (var detalle : request.respuestas()) {
                Pregunta pregunta = new Pregunta();
                pregunta.setPregunta(detalle.preguntaTexto());
                pregunta.setSemana(semana);
                pregunta.setNivelBloom(detalle.nivelBloom());
                // Los conceptos llegan del cliente, etiquetados por el generador, y se
                // agrupan aquí dentro del vocabulario que el docente ya curó. Es lo que hace
                // que descartar un tema cambie de verdad lo que se mide: antes la curación no
                // tocaba ni el BKT ni el mapa de calor.
                pregunta.setConceptos(
                        vocabularioConceptosService.alinear(detalle.conceptos(), semana.getId()));
                // Sin esto, el historial solo podía decir "Incorrecto" sin decir qué era lo
                // correcto, que es la parte que sirve para estudiar.
                pregunta.setRespuestaCorrecta(detalle.respuestaCorrecta());

                if (detalle.tipoPregunta().equals("ABIERTA")) {
                    pregunta.setTipodepregunta(Tipo.Responder);
                } else {
                    pregunta.setTipodepregunta(Tipo.Opcion_Multiple);
                }
                Pregunta preguntaGuardada = preguntaRepository.save(pregunta);

                RespuestaUsuario resUsuario = new RespuestaUsuario();
                resUsuario.setUsuario(usuario);
                resUsuario.setPregunta(preguntaGuardada);
                resUsuario.setRespuestaTexto(detalle.respuestaEstudiante());
                resUsuario.setCorrecta(detalle.esCorrecta());
                resUsuario.setRetroalimentacion(detalle.retroalimentacion());
                resUsuario.setFechaCreacion(LocalDateTime.now());
                resUsuario.setIntento(intento);

                respuestaUsuarioRepository.save(resUsuario);

                // Mismo mecanismo que en el flujo adaptativo (ver AdaptiveLearningService):
                // se salta en silencio si el frontend todavía no envía nivelBloom/conceptos
                // para esta pregunta, en vez de romper el guardado del examen completo.
                if (detalle.nivelBloom() != null && detalle.conceptos() != null && !detalle.conceptos().isBlank()) {
                    Long cursoId = semana.getCurso() != null ? semana.getCurso().getId() : null;
                    for (String concepto : detalle.conceptos().split("\\s*,\\s*")) {
                        if (!concepto.isBlank()) {
                            conocimientoBktService.actualizar(
                                    usuario.getId(), cursoId, semana.getId(),
                                    concepto.trim(), detalle.nivelBloom(), detalle.esCorrecta());
                        }
                    }
                }

                preguntasIndexar.add(new PreguntaIndexarInfo(preguntaGuardada.getId(), detalle.preguntaTexto()));
            }
        });

        // 3. Guardar vectores en Qdrant de forma NO transaccional fuera del bloqueo de la base de datos
        for (var info : preguntasIndexar) {
            if (info.preguntaTexto() != null && !info.preguntaTexto().trim().isEmpty()) {
                try {
                    Response<Embedding> embResponse = embeddingModel.embed(info.preguntaTexto());
                    Metadata meta = new Metadata();
                    meta.put("usuarioId", String.valueOf(request.usuarioId()));
                    meta.put("tipo", "pregunta");
                    meta.put("preguntaId", String.valueOf(info.preguntaId()));
                    meta.put("semanaId", String.valueOf(decodedSemanaId));
                    questionsEmbeddingStore.add(embResponse.content(), TextSegment.from(info.preguntaTexto(), meta));
                    log.info("[QDRANT-DEDUP] Pregunta guardada vectorialmente para alumno ID={}: '{}'", request.usuarioId(), info.preguntaTexto());
                } catch (Exception e) {
                    log.error("[QDRANT-DEDUP] Error al indexar pregunta en Qdrant: {}", e.getMessage());
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> obtenerIntentosPorUsuario(Long usuarioId) {
        return intentoRepository.findByUsuarioIdOrderByFechaDesc(usuarioId)
                .stream()
                // Los ACRA guardados ANTES de separarlos a su propia tabla siguen en
                // `intento` con nota 0.0. Sin este filtro, ese cero ficticio sigue
                // hundiendo el promedio del alumno en el dashboard y el historial — que es
                // justo el síntoma reportado ("2 intentos · promedio 4 de 20"). Los nuevos
                // ya no se crean aquí; esto limpia los históricos.
                .filter(i -> i.getTipoEvaluacion() != TipoEvaluacion.DIAGNOSTICA)
                .map(intento -> {
                    List<RespuestaUsuario> respuestasList = respuestaUsuarioRepository.findByIntentoId(intento.getId());
                    if (respuestasList.isEmpty()) {
                        respuestasList = respuestaUsuarioRepository.findByUsuarioIdAndPreguntaSemanaId(usuarioId, intento.getSemana().getId());
                    }

                    // LinkedHashMap y no Map.of: los campos nuevos son nullable para los
                    // intentos anteriores a este cambio, y Map.of rechaza valores null.
                    // Conserva además el orden de las claves, que es el de lectura del
                    // alumno y el de las columnas del CSV que descarga.
                    List<Map<String, Object>> respuestas = respuestasList
                            .stream().map(r -> {
                                Map<String, Object> detalle = new LinkedHashMap<>();
                                detalle.put("pregunta",          r.getPregunta().getPregunta());
                                detalle.put("respuesta",         r.getRespuestaTexto());
                                detalle.put("esCorrecta",        r.isCorrecta());
                                detalle.put("respuestaCorrecta", r.getPregunta().getRespuestaCorrecta());
                                detalle.put("retroalimentacion", r.getRetroalimentacion());
                                detalle.put("nivelBloom",        r.getPregunta().getNivelBloom());
                                detalle.put("conceptos",         r.getPregunta().getConceptos());
                                return detalle;
                            }).toList();

                    String cursoNombre = intento.getSemana().getCurso() != null ? intento.getSemana().getCurso().getNombre() : "Curso sin nombre";
                    String cursoEmoji = intento.getSemana().getCurso() != null ? intento.getSemana().getCurso().getEmoji() : "📚";

                    Map<String, Object> fila = new LinkedHashMap<>();
                    fila.put("id",          intento.getId());
                    fila.put("semana",      intento.getSemana().getNumSem());
                    fila.put("nombreTema",  intento.getSemana().getNombreTema());
                    fila.put("cursoNombre", cursoNombre);
                    fila.put("cursoEmoji",  cursoEmoji);
                    fila.put("nota",        intento.getNota());
                    fila.put("fecha",       intento.getFecha().toString());
                    // El frontend ya filtraba por este campo, pero nunca se enviaba: la
                    // condición `tipoEvaluacion !== "DIAGNOSTICA"` comparaba contra
                    // undefined y no filtraba nada. Quien hacía el trabajo era el filtro
                    // del servidor, unas líneas más arriba.
                    fila.put("tipoEvaluacion", intento.getTipoEvaluacion() != null
                            ? intento.getTipoEvaluacion().name() : null);
                    fila.put("respuestas",  respuestas);
                    fila.put("tecnica",     intento.getTecnica() != null ? intento.getTecnica() : "");
                    return fila;
                }).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> obtenerTodosLosIntentos() {
        return intentoRepository.findAll().stream()
                .map(intento -> {
                    String cursoNombre = intento.getSemana().getCurso() != null
                            ? intento.getSemana().getCurso().getNombre()
                            : "Curso sin nombre";

                    String tecnica = "Práctica";
                    if (intento.getTipoEvaluacion() != null) {
                        tecnica = "adaptativa";
                    } else {
                        List<RespuestaUsuario> respuestas = respuestaUsuarioRepository.findByIntentoId(intento.getId());
                        if (!respuestas.isEmpty()) {
                            Tipo tipo = respuestas.get(0).getPregunta().getTipodepregunta();
                            if (tipo == Tipo.Responder) {
                                tecnica = "abierta";
                            } else if (tipo == Tipo.Opcion_Multiple) {
                                tecnica = "opcion_multiple";
                            }
                        }
                    }

                    return Map.<String, Object>of(
                            "id",      intento.getId(),
                            "alumno",  intento.getUsuario().getNombre(),
                            "correo",  intento.getUsuario().getCorreo(),
                            "curso",   cursoNombre,
                            "semana",  intento.getSemana().getNumSem(),
                            "tecnica", tecnica,
                            "nota",    intento.getNota(),
                            "fecha",   intento.getFecha().toString()
                    );
                }).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> obtenerIntentosPorSemana(Long semanaId) {
        List<Intento> intentos = intentoRepository.findBySemanaIdOrderByFechaDesc(semanaId);
        
        // Agrupar por correo de usuario
        Map<String, List<Intento>> agrupadosPorCorreo = intentos.stream()
                .collect(Collectors.groupingBy(i -> i.getUsuario().getCorreo()));
                
        return agrupadosPorCorreo.entrySet().stream().map(entry -> {
            String correo = entry.getKey();
            List<Intento> intentosAlumno = entry.getValue();
            String nombre = intentosAlumno.get(0).getUsuario().getNombre();
            
            List<Map<String, Object>> intentosDetalle = intentosAlumno.stream().map(intento -> Map.<String, Object>of(
                    "id",       intento.getId(),
                    "nota",     intento.getNota(),
                    "fecha",    intento.getFecha().toString(),
                    "tecnica",  intento.getTecnica() != null ? intento.getTecnica() : "Práctica"
            )).toList();
            
            double sumaNotas = intentosAlumno.stream().mapToDouble(i -> i.getNota() != null ? i.getNota() : 0.0).sum();
            double promedio = intentosAlumno.isEmpty() ? 0.0 : sumaNotas / intentosAlumno.size();
            
            return Map.<String, Object>of(
                    "alumno", nombre,
                    "correo", correo,
                    "promedio", Math.round(promedio * 10.0) / 10.0,
                    "totalIntentos", intentosAlumno.size(),
                    "intentos", intentosDetalle
            );
        }).toList();
    }


}