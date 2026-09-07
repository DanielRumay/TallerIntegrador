package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.DTO.CursoDocenteDTO;
import com.example.tallerintegrador.DTO.CursoResponseDTO;
import com.example.tallerintegrador.DTO.SemanaDTO;
import com.example.tallerintegrador.entidades.mongodb.ArchivoPrompt;
import com.example.tallerintegrador.repository.mongo.ArchivoPromptRepository;
import com.example.tallerintegrador.repository.MaterialRepository;
import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.repository.MatriculaRepository;
import com.example.tallerintegrador.service.academico.CursoService;
import com.example.tallerintegrador.service.academico.SemanaService;
import com.example.tallerintegrador.service.util.IdHasher;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/cursos")
@RequiredArgsConstructor
public class CursoController {

    private final ArchivoPromptRepository archivoPromptRepo;
    private final CursoService cursoService;
    private final com.example.tallerintegrador.controller.util.UsuarioAutenticado usuarioAutenticado;
    private final SemanaService semanaService;
    private final IdHasher idHasher;
    private final MaterialRepository materialRepo;
    private final UserRepository userRepo;
    private final MatriculaRepository matriculaRepo;

    /** Sin la comprobación, cualquier alumno podía listar los cursos de otro cambiando el id. */
    @PreAuthorize("hasAuthority('STUDENT')")
    @GetMapping("/estudiante/{alumnoId}")
    public ResponseEntity<List<CursoDocenteDTO>> listarCursosEstudiante(
            @PathVariable Long alumnoId,
            org.springframework.security.core.Authentication authentication) {
        if (!usuarioAutenticado.puedeConsultarA(alumnoId, authentication)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(cursoService.obtenerCursosResumenAlumno(alumnoId));
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @PostMapping("/crear")
    public ResponseEntity<CursoResponseDTO> crearCurso(@Valid @RequestBody com.example.tallerintegrador.DTO.CursoRequestDTO request) {
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

    /**
     * Crea una semana nueva al final del curso. El docente puede darle desde ya un
     * nombreTema si sabe qué tema va a cubrir; si lo omite, se deriva automáticamente al
     * subir el primer material (ver SemanaService.subirArchivos).
     */
    @PreAuthorize("hasAuthority('TEACHER')")
    @PostMapping("/{courseId}/semanas")
    public ResponseEntity<?> crearSemana(
            @PathVariable String courseId,
            @RequestBody(required = false) Map<String, String> body) {
        try {
            String nombreTema = body != null ? body.get("nombreTema") : null;
            return ResponseEntity.ok(semanaService.crearSemana(idHasher.decode(courseId), nombreTema));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("hasAuthority('TEACHER')")
    @PutMapping("/{courseId}/semanas/reordenar")
    public ResponseEntity<?> reordenarSemanas(
            @PathVariable String courseId,
            @RequestBody List<String> semanaIds) {
        cursoService.reordenarSemanas(idHasher.decode(courseId), semanaIds);
        return ResponseEntity.ok(Map.of("message", "Semanas reordenadas exitosamente"));
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @PutMapping("/{courseId}")
    public ResponseEntity<CursoResponseDTO> editarCurso(
            @PathVariable String courseId,
            @Valid @RequestBody com.example.tallerintegrador.DTO.CursoRequestDTO request) {
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
        validarAccesoArchivo(mongoId);
        ArchivoPrompt archivo = archivoPromptRepo.findById(mongoId)
                .orElseThrow(() -> new RuntimeException("Archivo no encontrado"));

        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + archivo.getNombre() + "\"")
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
                    "message", "Alumno matriculado exitosamente"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "message", e.getMessage()));
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
                    "message", "Alumno retirado del curso"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "message", e.getMessage()));
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
        validarAccesoArchivo(mongoId);
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

    @PreAuthorize("hasAuthority('TEACHER')")
    @GetMapping("/docente/{profesorId}/rendimiento")
    public ResponseEntity<List<Map<String, Object>>> obtenerRendimientoCursos(@PathVariable Long profesorId) {
        return ResponseEntity.ok(cursoService.obtenerRendimientoCursos(profesorId));
    }

    private void validarAccesoArchivo(String mongoId) {
        String correo = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
        var userOpt = userRepo.findByCorreo(correo);
        if (userOpt.isPresent()) {
            var usuario = userOpt.get();
            if (usuario.getRol() == com.example.tallerintegrador.entidades.postgres.Rol.STUDENT) {
                var materialOpt = materialRepo.findByMongoId(mongoId);
                if (materialOpt.isPresent()) {
                    var material = materialOpt.get();
                    if (material.getSemana() != null && material.getSemana().getCurso() != null) {
                        Long cursoId = material.getSemana().getCurso().getId();
                        boolean matriculado = matriculaRepo.findByCursoIdAndUsuarioId(cursoId, usuario.getId()).isPresent();
                        if (!matriculado) {
                            throw new org.springframework.security.access.AccessDeniedException("No está matriculado en este curso para acceder al material.");
                        }
                    }
                }
            }
        }
    }
}