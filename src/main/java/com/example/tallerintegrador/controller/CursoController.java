package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.DTO.CursoDocenteDTO;
import com.example.tallerintegrador.DTO.CursoResponseDTO;
import com.example.tallerintegrador.DTO.SemanaDTO;
import com.example.tallerintegrador.entidades.postgres.Curso;
import com.example.tallerintegrador.service.CursoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/cursos")
@RequiredArgsConstructor
public class CursoController {

    private final CursoService cursoService;
    @PreAuthorize("hasAuthority('STUDENT')")
    @GetMapping("/estudiante/{alumnoId}")
    public ResponseEntity<List<CursoDocenteDTO>> listarCursosEstudiante(@PathVariable Long alumnoId) {
        return ResponseEntity.ok(cursoService.obtenerCursosResumenAlumno(alumnoId));
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @PostMapping("/crear")
    public ResponseEntity<CursoResponseDTO> crearCurso(@RequestBody Map<String, Object> request) {
        try {
            CursoResponseDTO nuevo = cursoService.crearCurso(request);
            return ResponseEntity.ok(nuevo);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PreAuthorize("hasAuthority('TEACHER')")
    @GetMapping("/docente/{profesorId}")
    public ResponseEntity<List<CursoDocenteDTO>> listarCursosDocente(@PathVariable Long profesorId) {
        return ResponseEntity.ok(cursoService.obtenerCursosResumenProfesor(profesorId));
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('STUDENT')")
    @GetMapping("/{courseId}/semanas")
    public ResponseEntity<List<SemanaDTO>> listarSemanas(@PathVariable Long courseId) {
        return ResponseEntity.ok(cursoService.obtenerSemanasPorCurso(courseId));
    }
}