package com.example.tallerintegrador.service;

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
import java.util.List;
import java.util.Map;

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
            IdHasher idHasher) {
        this.intentoRepository = intentoRepository;
        this.userRepository = userRepository;
        this.semanaRepository = semanaRepository;
        this.preguntaRepository = preguntaRepository;
        this.respuestaUsuarioRepository = respuestaUsuarioRepository;
        this.embeddingModel = embeddingModel;
        this.questionsEmbeddingStore = questionsEmbeddingStore;
        this.transactionManager = transactionManager;
        this.idHasher = idHasher;
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
            intentoRepository.save(intento);

            // 2. Guardar las preguntas generadas y las respuestas del alumno en BD local
            for (var detalle : request.respuestas()) {
                Pregunta pregunta = new Pregunta();
                pregunta.setPregunta(detalle.preguntaTexto());
                pregunta.setSemana(semana);

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
                resUsuario.setFechaCreacion(LocalDateTime.now());
                resUsuario.setIntento(intento);

                respuestaUsuarioRepository.save(resUsuario);

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
                .stream().map(intento -> {
                    List<RespuestaUsuario> respuestasList = respuestaUsuarioRepository.findByIntentoId(intento.getId());
                    if (respuestasList.isEmpty()) {
                        respuestasList = respuestaUsuarioRepository.findByUsuarioIdAndPreguntaSemanaId(usuarioId, intento.getSemana().getId());
                    }

                    List<Map<String, Object>> respuestas = respuestasList
                            .stream().map(r -> Map.<String, Object>of(
                                    "pregunta",   r.getPregunta().getPregunta(),
                                    "respuesta",  r.getRespuestaTexto(),
                                    "esCorrecta", r.isCorrecta()
                            )).toList();

                    String cursoNombre = intento.getSemana().getCurso() != null ? intento.getSemana().getCurso().getNombre() : "Curso sin nombre";
                    String cursoEmoji = intento.getSemana().getCurso() != null ? intento.getSemana().getCurso().getEmoji() : "📚";

                    return Map.<String, Object>of(
                            "id",          intento.getId(),
                            "semana",      intento.getSemana().getNumSem(),
                            "cursoNombre", cursoNombre,
                            "cursoEmoji",  cursoEmoji,
                            "nota",        intento.getNota(),
                            "fecha",       intento.getFecha().toString(),
                            "respuestas",  respuestas
                    );
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
        return intentoRepository.findBySemanaIdOrderByFechaDesc(semanaId)
                .stream().map(intento -> Map.<String, Object>of(
                        "id",       intento.getId(),
                        "alumno",   intento.getUsuario().getNombre(),
                        "correo",   intento.getUsuario().getCorreo(),
                        "nota",     intento.getNota(),
                        "fecha",    intento.getFecha().toString()
                )).toList();
    }
}