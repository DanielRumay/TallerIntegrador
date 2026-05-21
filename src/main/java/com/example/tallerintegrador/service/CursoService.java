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

    // 👇 AQUÍ ESTÁ LA CORRECCIÓN: Mapeamos la lista de MaterialDTO
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
}