package com.example.tallerintegrador.service;

import com.example.tallerintegrador.DTO.GuardarIntentoRequest;
import com.example.tallerintegrador.entidades.postgres.*;
import com.example.tallerintegrador.repository.*;
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

import java.time.LocalDateTime;
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

    public IntentoService(
            IntentoRepository intentoRepository,
            UserRepository userRepository,
            SemanaRepository semanaRepository,
            PreguntaRepository preguntaRepository,
            RespuestaUsuarioRepository respuestaUsuarioRepository,
            EmbeddingModel embeddingModel,
            @Qualifier("questionsEmbeddingStore") EmbeddingStore<TextSegment> questionsEmbeddingStore) {
        this.intentoRepository = intentoRepository;
        this.userRepository = userRepository;
        this.semanaRepository = semanaRepository;
        this.preguntaRepository = preguntaRepository;
        this.respuestaUsuarioRepository = respuestaUsuarioRepository;
        this.embeddingModel = embeddingModel;
        this.questionsEmbeddingStore = questionsEmbeddingStore;
    }

    @Transactional
    public void guardarIntentoCompleto(GuardarIntentoRequest request) {
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

        // 2. Guardar las preguntas generadas y las respuestas del alumno
        for (var detalle : request.respuestas()) {

            // Guardamos la pregunta generada por la IA para tener registro
            Pregunta pregunta = new Pregunta();
            pregunta.setPregunta(detalle.preguntaTexto());
            pregunta.setSemana(semana);

            // Asignamos el enum según el string (Ajusta esto si tus enums se llaman distinto)
            if (detalle.tipoPregunta().equals("ABIERTA")) {
                pregunta.setTipodepregunta(Tipo.Responder);
            } else {
                pregunta.setTipodepregunta(Tipo.Opcion_Multiple);
            }
            Pregunta preguntaGuardada = preguntaRepository.save(pregunta);

            // Guardamos lo que respondió el alumno
            RespuestaUsuario resUsuario = new RespuestaUsuario();
            resUsuario.setUsuario(usuario);
            resUsuario.setPregunta(preguntaGuardada);
            resUsuario.setRespuestaTexto(detalle.respuestaEstudiante());
            resUsuario.setCorrecta(detalle.esCorrecta());
            resUsuario.setFechaCreacion(LocalDateTime.now());
            resUsuario.setIntento(intento);

            respuestaUsuarioRepository.save(resUsuario);

            // Guardar vector de la pregunta en Qdrant para posterior deduplicación
            if (detalle.preguntaTexto() != null && !detalle.preguntaTexto().trim().isEmpty()) {
                try {
                    Response<Embedding> embResponse = embeddingModel.embed(detalle.preguntaTexto());
                    Metadata meta = new Metadata();
                    meta.put("usuarioId", String.valueOf(usuario.getId()));
                    meta.put("tipo", "pregunta");
                    meta.put("preguntaId", String.valueOf(preguntaGuardada.getId()));
                    meta.put("semanaId", String.valueOf(semana.getId()));
                    questionsEmbeddingStore.add(embResponse.content(), TextSegment.from(detalle.preguntaTexto(), meta));
                    log.info("[QDRANT-DEDUP] Pregunta guardada vectorialmente para alumno ID={}: '{}'", usuario.getId(), detalle.preguntaTexto());
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