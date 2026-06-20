package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.DTO.CursoDocenteDTO;
import com.example.tallerintegrador.DTO.CursoResponseDTO;
import com.example.tallerintegrador.DTO.SemanaDTO;
import com.example.tallerintegrador.entidades.mongodb.ArchivoPrompt;
import com.example.tallerintegrador.repository.ArchivoPromptRepository;
import com.example.tallerintegrador.service.CursoService;
import com.example.tallerintegrador.service.util.IdHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

    private final ArchivoPromptRepository archivoPromptRepo;

    private final CursoService cursoService;
    private final IdHasher idHasher;
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
    public ResponseEntity<List<SemanaDTO>> listarSemanas(@PathVariable String courseId) {
        return ResponseEntity.ok(cursoService.obtenerSemanasPorCurso(idHasher.decode(courseId)));
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @PutMapping("/{courseId}")
    public ResponseEntity<CursoResponseDTO> editarCurso(
            @PathVariable String courseId,
            @RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(cursoService.actualizarCurso(idHasher.decode(courseId), request));
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @DeleteMapping("/{courseId}")
    public ResponseEntity<Map<String, String>> eliminarCurso(@PathVariable String courseId) {
        cursoService.eliminarCurso(idHasher.decode(courseId));
        return ResponseEntity.ok(Map.of("message", "Curso eliminado exitosamente"));
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('STUDENT')")
    @GetMapping(value = "/ver-pdf/{mongoId}", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> verPdfEnNavegador(@PathVariable String mongoId) {
        ArchivoPrompt archivo = archivoPromptRepo.findById(mongoId)
                .orElseThrow(() -> new RuntimeException("Archivo no encontrado"));

        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + archivo.getNombre() + "\"")
                .body(archivo.getArchivoFisico());
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @PostMapping("/{courseId}/matricular")
    public ResponseEntity<Map<String, Object>> matricularAlumno(
            @PathVariable String courseId,
            @RequestBody Map<String, Long> request) {

        Long studentId = request.get("studentId");

        try {
            cursoService.matricularAlumno(idHasher.decode(courseId), studentId);

            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "message", "Alumno matriculado exitosamente"
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "message", e.getMessage()
            ));
        }
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @DeleteMapping("/{courseId}/desmatricular/{studentId}")
    public ResponseEntity<Map<String, Object>> desmatricularAlumno(
            @PathVariable String courseId,
            @PathVariable Long studentId) {

        try {
            cursoService.desmatricularAlumno(idHasher.decode(courseId), studentId);

            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "message", "Alumno retirado del curso"
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "message", e.getMessage()
            ));
        }
    }
    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/{courseId}/alumnos")
    public ResponseEntity<List<Map<String, Object>>> listarAlumnos(@PathVariable String courseId) {
        return ResponseEntity.ok(cursoService.obtenerAlumnosPorCurso(idHasher.decode(courseId)));
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/estudiantes/buscar")
    public ResponseEntity<?> buscarEstudiantes(@RequestParam(required = false, defaultValue = "") String nombre) {
        return ResponseEntity.ok(cursoService.buscarEstudiantesPorNombre(nombre));
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN') or hasAuthority('STUDENT')")
    @GetMapping("/ver-archivo/{mongoId}")
    public ResponseEntity<byte[]> verArchivoFisico(@PathVariable String mongoId) {

        ArchivoPrompt archivo = archivoPromptRepo.findById(mongoId)
                .orElseThrow(() -> new RuntimeException("Archivo no encontrado en la base de datos"));

        byte[] archivoEnBruto = archivo.getArchivoFisico();

        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (archivo.getTipo() != null && !archivo.getTipo().isEmpty()) {
            mediaType = MediaType.parseMediaType(archivo.getTipo());
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + archivo.getNombre() + "\"")
                .body(archivoEnBruto);
    }
}