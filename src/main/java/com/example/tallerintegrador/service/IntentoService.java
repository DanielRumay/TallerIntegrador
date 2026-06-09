package com.example.tallerintegrador.service;

import com.example.tallerintegrador.DTO.GuardarIntentoRequest;
import com.example.tallerintegrador.entidades.postgres.*;
import com.example.tallerintegrador.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class IntentoService {

    private final IntentoRepository intentoRepository;
    private final UserRepository userRepository;
    private final SemanaRepository semanaRepository;
    private final PreguntaRepository preguntaRepository;
    private final RespuestaUsuarioRepository respuestaUsuarioRepository;

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