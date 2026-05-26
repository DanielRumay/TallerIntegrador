package com.example.tallerintegrador.service;

import com.example.tallerintegrador.DTO.CursoResponseDTO;
import com.example.tallerintegrador.DTO.CursoDocenteDTO;
import com.example.tallerintegrador.DTO.SemanaDTO;
import com.example.tallerintegrador.entidades.postgres.Curso;
import com.example.tallerintegrador.entidades.postgres.Grado;
import com.example.tallerintegrador.entidades.postgres.Seccion;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CursoService {

    private final CursoRepository cursoRepository;
    private final UserRepository userRepository;
    private final GradoRepository gradoRepository;
    private final MatriculaRepository matriculaRepository;
    private final SeccionRepository seccionRepository;
    private final SemanaRepository semanaRepository;
    private final PreguntaRepository preguntaRepository;
    private final RespuestaRepository respuestaRepository;
    private final RespuestaUsuarioRepository respuestaUsuarioRepository;
    private final MaterialRepository materialRepository;

    public List<Curso> obtenerCursosPorProfesor(Long profesorId) {
        return cursoRepository.findByProfesorId(profesorId);
    }

    public CursoResponseDTO crearCurso(Map<String, Object> request) {
        Long profesorId = Long.valueOf(request.get("profesorId").toString());
        Long gradoId = Long.valueOf(request.get("gradoId").toString());
        Long seccionId = Long.valueOf(request.get("seccionId").toString());

        Usuario profesor = userRepository.findById(profesorId)
                .orElseThrow(() -> new RuntimeException("Profesor no encontrado"));
        Grado grado = gradoRepository.findById(gradoId)
                .orElseThrow(() -> new RuntimeException("Grado no encontrado"));
        Seccion seccion = seccionRepository.findById(seccionId)
                .orElseThrow(() -> new RuntimeException("Sección no encontrada"));

        Curso nuevoCurso = new Curso();
        nuevoCurso.setNombre(request.get("nombre").toString());
        nuevoCurso.setDescripcion(request.get("descripcion").toString());
        nuevoCurso.setProfesor(profesor);
        nuevoCurso.setGrado(grado);
        nuevoCurso.setSeccion(seccion);

        if (request.containsKey("emoji")) {
            nuevoCurso.setEmoji(request.get("emoji").toString());
        }
        if (request.containsKey("color")) {
            nuevoCurso.setColor(request.get("color").toString());
        }

        Curso cursoGuardado = cursoRepository.save(nuevoCurso);

        if (request.containsKey("semanas")) {
            int cantidadSemanas = Integer.parseInt(request.get("semanas").toString());
            for (int i = 1; i <= cantidadSemanas; i++) {
                com.example.tallerintegrador.entidades.postgres.Semana nuevaSemana =
                        new com.example.tallerintegrador.entidades.postgres.Semana();
                nuevaSemana.setNumSem("Semana " + i);
                nuevaSemana.setCurso(cursoGuardado);
                semanaRepository.save(nuevaSemana);
            }
        }
        return CursoResponseDTO.builder()
                .id(cursoGuardado.getId())
                .name(cursoGuardado.getNombre())
                .description(cursoGuardado.getDescripcion())
                .emoji(cursoGuardado.getEmoji())
                .color(cursoGuardado.getColor())
                .nombreProfesor(profesor.getNombre())
                .build();
    }

    public List<Curso> obtenerCursosPorAlumno(Long alumnoId) {
        return cursoRepository.findCursosByAlumnoId(alumnoId);
    }

    public List<CursoDocenteDTO> obtenerCursosResumenProfesor(Long profesorId) {
        List<Curso> cursos = cursoRepository.findByProfesorId(profesorId);

        return cursos.stream().map(curso -> {
            long alumnos = matriculaRepository.countByCursoId(curso.getId());
            long semanas = semanaRepository.countByCursoId(curso.getId());

            return CursoDocenteDTO.builder()
                    .id(curso.getId())
                    .name(curso.getNombre())
                    .description(curso.getDescripcion())
                    .emoji(curso.getEmoji() != null ? curso.getEmoji() : "📚")
                    .color(curso.getColor() != null ? curso.getColor() : "primary")
                    .weeks(semanas)
                    .studentCount(alumnos)
                    .build();
        }).toList();
    }

    public List<CursoDocenteDTO> obtenerCursosResumenAlumno(Long alumnoId) {
        List<Curso> cursos = cursoRepository.findCursosByAlumnoId(alumnoId);

        return cursos.stream().map(curso -> {
            return CursoDocenteDTO.builder()
                    .id(curso.getId())
                    .name(curso.getNombre())
                    .description(curso.getDescripcion())
                    .emoji(curso.getEmoji() != null ? curso.getEmoji() : "📚")
                    .color(curso.getColor() != null ? curso.getColor() : "primary")
                    .build();
        }).toList();
    }

    // AQUÍ ESTÁ LA CORRECCIÓN: Mapeamos la lista de MaterialDTO
    public List<SemanaDTO> obtenerSemanasPorCurso(Long cursoId) {
        return semanaRepository.findByCursoId(cursoId)
                .stream()
                .map(semana -> {
                    List<SemanaDTO.MaterialDTO> materialesDTO = null;

                    if (semana.getMateriales() != null) {
                        materialesDTO = semana.getMateriales().stream()
                                .map(mat -> SemanaDTO.MaterialDTO.builder()
                                        .id(mat.getId())
                                        .nombreArchivo(mat.getNombreArchivo())
                                        .mongoId(mat.getMongoId())
                                        .visible(mat.isVisible())
                                        .fechaCarga(mat.getFechaCarga() != null ? mat.getFechaCarga().toString() : null)
                                        .build())
                                .collect(Collectors.toList());
                    }

                    return SemanaDTO.builder()
                            .id(semana.getId())
                            .numSem(semana.getNumSem())
                            .totalPreguntas(
                                    semana.getPreguntas() != null
                                            ? semana.getPreguntas().size()
                                            : 0
                            )
                            .materiales(materialesDTO) // Asignamos la nueva lista de materiales
                            .build();
                })
                .collect(Collectors.toList());
    }

    public CursoResponseDTO actualizarCurso(Long courseId, Map<String, Object> request) {
        Curso curso = cursoRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Curso no encontrado"));

        if (request.containsKey("nombre")) curso.setNombre(request.get("nombre").toString());
        if (request.containsKey("descripcion")) curso.setDescripcion(request.get("descripcion").toString());
        if (request.containsKey("emoji")) curso.setEmoji(request.get("emoji").toString());
        if (request.containsKey("color")) curso.setColor(request.get("color").toString());

        Curso actualizado = cursoRepository.save(curso);

        return CursoResponseDTO.builder()
                .id(actualizado.getId())
                .name(actualizado.getNombre())
                .description(actualizado.getDescripcion())
                .emoji(actualizado.getEmoji())
                .color(actualizado.getColor())
                .build();
    }

    @Transactional
    public void eliminarCurso(Long courseId) {
        Curso curso = cursoRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Curso no encontrado"));

        List<Long> semanaIds = semanaRepository.findByCursoId(courseId)
                .stream()
                .map(s -> s.getId())
                .toList();

        if (!semanaIds.isEmpty()) {
            List<Long> preguntaIds = preguntaRepository.findBySemanaIdIn(semanaIds)
                    .stream()
                    .map(p -> p.getId())
                    .toList();

            if (!preguntaIds.isEmpty()) {
                respuestaUsuarioRepository.deleteByPreguntaIdIn(preguntaIds);

                respuestaRepository.deleteByPreguntaIdIn(preguntaIds);

                preguntaRepository.deleteBySemanaIdIn(semanaIds);
            }

            materialRepository.deleteBySemanaIdIn(semanaIds);
        }

        matriculaRepository.deleteByCursoId(courseId);

        semanaRepository.deleteByCursoId(courseId);

        cursoRepository.delete(curso);
    }

    public void matricularAlumno(Long courseId, Long alumnoId) {
        Curso curso = cursoRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Curso no encontrado"));
        Usuario alumno = userRepository.findById(alumnoId)
                .orElseThrow(() -> new RuntimeException("Alumno no encontrado"));

        com.example.tallerintegrador.entidades.postgres.Matricula matricula =
                new com.example.tallerintegrador.entidades.postgres.Matricula();
        matricula.setCurso(curso);
        matricula.setUsuario(alumno);

        matriculaRepository.save(matricula);
    }

    public void desmatricularAlumno(Long courseId, Long alumnoId) {
        com.example.tallerintegrador.entidades.postgres.Matricula matricula =
                matriculaRepository.findByCursoIdAndUsuarioId(courseId, alumnoId)
                        .orElseThrow(() -> new RuntimeException("El alumno no está matriculado en este curso"));

        matriculaRepository.delete(matricula);
    }

    public List<Map<String, Object>> obtenerAlumnosPorCurso(Long cursoId) {
        // Traemos todas las matrículas de este curso
        List<com.example.tallerintegrador.entidades.postgres.Matricula> matriculas =
                matriculaRepository.findByCursoId(cursoId);

        return matriculas.stream().map(m -> {
            Usuario alumno = m.getUsuario();
            return Map.<String, Object>of(
                    "id", alumno.getId(),
                    "nombre", alumno.getNombre(),
                    "correo", alumno.getCorreo()
            );
        }).collect(Collectors.toList());
    }
}