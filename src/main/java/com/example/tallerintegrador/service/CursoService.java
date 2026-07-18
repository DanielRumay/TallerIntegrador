package com.example.tallerintegrador.service;

import com.example.tallerintegrador.DTO.CursoRequestDTO;
import com.example.tallerintegrador.DTO.CursoResponseDTO;
import com.example.tallerintegrador.DTO.CursoDocenteDTO;
import com.example.tallerintegrador.DTO.SemanaDTO;
import com.example.tallerintegrador.entidades.postgres.Curso;
import com.example.tallerintegrador.entidades.postgres.Grado;
import com.example.tallerintegrador.entidades.postgres.Seccion;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.*;
import com.example.tallerintegrador.service.util.IdHasher;
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
    private final IdHasher idHasher;

    public List<Curso> obtenerCursosPorProfesor(Long profesorId) {
        return cursoRepository.findByProfesorId(profesorId);
    }

    public CursoResponseDTO crearCurso(CursoRequestDTO request) {
        Long profesorId = request.profesorId();
        Long gradoId = request.gradoId();
        Long seccionId = request.seccionId();

        Usuario profesor = userRepository.findById(profesorId)
                .orElseThrow(() -> new RuntimeException("Profesor no encontrado"));
        Grado grado = gradoRepository.findById(gradoId)
                .orElseThrow(() -> new RuntimeException("Grado no encontrado"));
        Seccion seccion = seccionRepository.findById(seccionId)
                .orElseThrow(() -> new RuntimeException("Sección no encontrada"));

        Curso nuevoCurso = new Curso();
        nuevoCurso.setNombre(request.nombre());
        nuevoCurso.setDescripcion(request.descripcion());
        nuevoCurso.setProfesor(profesor);
        nuevoCurso.setGrado(grado);
        nuevoCurso.setSeccion(seccion);

        if (request.emoji() != null) {
            nuevoCurso.setEmoji(request.emoji());
        }
        if (request.color() != null) {
            nuevoCurso.setColor(request.color());
        }

        Curso cursoGuardado = cursoRepository.save(nuevoCurso);

        if (request.semanas() != null) {
            int cantidadSemanas = request.semanas();
            for (int i = 1; i <= cantidadSemanas; i++) {
                com.example.tallerintegrador.entidades.postgres.Semana nuevaSemana =
                        new com.example.tallerintegrador.entidades.postgres.Semana();
                nuevaSemana.setNumSem("Semana " + i);
                nuevaSemana.setCurso(cursoGuardado);
                semanaRepository.save(nuevaSemana);
            }
        }
        return CursoResponseDTO.builder()
                .id(idHasher.encode(cursoGuardado.getId()))
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
                    .id(idHasher.encode(curso.getId()))
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
                    .id(idHasher.encode(curso.getId()))
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
                                        .id(idHasher.encode(mat.getId()))
                                        .nombreArchivo(mat.getNombreArchivo())
                                        .mongoId(mat.getMongoId())
                                        .visible(mat.isVisible())
                                        .fechaCarga(mat.getFechaCarga() != null ? mat.getFechaCarga().toString() : null)
                                        .build())
                                .collect(Collectors.toList());
                    }

                    return SemanaDTO.builder()
                            .id(idHasher.encode(semana.getId()))
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

    public CursoResponseDTO actualizarCurso(Long courseId, com.example.tallerintegrador.DTO.CursoRequestDTO request) {
        Curso curso = cursoRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Curso no encontrado"));

        if (request.nombre() != null) curso.setNombre(request.nombre());
        if (request.descripcion() != null) curso.setDescripcion(request.descripcion());
        if (request.emoji() != null) curso.setEmoji(request.emoji());
        if (request.color() != null) curso.setColor(request.color());

        Curso actualizado = cursoRepository.save(curso);

        return CursoResponseDTO.builder()
                .id(idHasher.encode(actualizado.getId()))
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

        // VALIDACIÓN: Evitar matricular al mismo alumno dos veces
        if (matriculaRepository.findByCursoIdAndUsuarioId(courseId, alumnoId).isPresent()) {
            throw new RuntimeException("El alumno ya se encuentra matriculado en este curso");
        }

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

    public List<Map<String, Object>> buscarEstudiantesPorNombre(String nombre) {
        List<Usuario> estudiantes;
        if (nombre == null || nombre.trim().isEmpty()) {
            estudiantes = userRepository.findByRol(com.example.tallerintegrador.entidades.postgres.Rol.STUDENT);
        } else {
            estudiantes = userRepository.findByRolAndNombreContainingIgnoreCase(com.example.tallerintegrador.entidades.postgres.Rol.STUDENT, nombre);
        }
        return estudiantes.stream().map(u -> Map.<String, Object>of(
                "id", u.getId(),
                "nombre", u.getNombre(),
                "correo", u.getCorreo()
        )).collect(Collectors.toList());
    }

    public List<Map<String, Object>> obtenerRendimientoCursos(Long profesorId) {
        return cursoRepository.findRendimientoAlumnosPorCurso(profesorId);
    }
}