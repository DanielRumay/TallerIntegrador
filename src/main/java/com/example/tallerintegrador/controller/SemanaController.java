package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.DTO.SemanaDTO;
import com.example.tallerintegrador.service.rag.RagIngestionService;
import com.example.tallerintegrador.service.academico.SemanaService;
import com.example.tallerintegrador.service.util.IdHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map; // <-- NO OLVIDES IMPORTAR ESTO

@RestController
@RequestMapping("/semanas")
@RequiredArgsConstructor
public class SemanaController {

    private final SemanaService semanaService;
    @SuppressWarnings("unused")
    private final RagIngestionService ragIngestionService;
    private final com.example.tallerintegrador.service.rag.IngestaAsincronaService ingestaAsincronaService;
    private final com.example.tallerintegrador.service.rag.ProgresoIngestaService progresoIngestaService;
    private final IdHasher idHasher;

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('STUDENT')")
    @GetMapping("/{semanaId}")
    public ResponseEntity<SemanaDTO> obtenerSemana(@PathVariable String semanaId) {
        return ResponseEntity.ok(semanaService.obtenerSemana(idHasher.decode(semanaId)));
    }

    @PreAuthorize("hasAuthority('TEACHER')")
    @PostMapping(value = "/{semanaId}/archivos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SemanaDTO> subirArchivos(
            @PathVariable String semanaId,
            @RequestParam("archivos") List<MultipartFile> archivos) {
        return ResponseEntity.ok(semanaService.subirArchivos(idHasher.decode(semanaId), archivos));
    }

    /**
     * Subida asíncrona: responde de inmediato con un id de progreso y procesa en segundo
     * plano. Es la ruta que debe usar la interfaz del docente.
     *
     * Una obra larga tarda varios minutos —se lee el documento, se resume cada capítulo y se
     * embeben cientos de fragmentos—, y una petición HTTP abierta todo ese rato acaba cortada
     * por el navegador o por un proxy, dejando la ingesta a medias. La ruta síncrona de arriba
     * se conserva para documentos pequeños y para no romper a quien ya la usa.
     */
    @PreAuthorize("hasAuthority('TEACHER')")
    @PostMapping(value = "/{semanaId}/archivos-async", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> subirArchivosAsync(
            @PathVariable String semanaId,
            @RequestParam("archivos") List<MultipartFile> archivos) {
        try {
            String progresoId = ingestaAsincronaService.lanzar(idHasher.decode(semanaId), archivos);
            return ResponseEntity.accepted().body(Map.of(
                    "ingestaId", progresoId,
                    "mensaje", "Procesando el material. Puede seguir trabajando."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Progreso de una ingesta lanzada con `/archivos-async`. El frontend consulta esto. */
    @PreAuthorize("hasAuthority('TEACHER')")
    @GetMapping("/ingesta/{ingestaId}")
    public ResponseEntity<Map<String, Object>> progresoIngesta(@PathVariable String ingestaId) {
        return ResponseEntity.ok(progresoIngestaService.consultar(ingestaId));
    }

    @PreAuthorize("hasAuthority('TEACHER')")
    @DeleteMapping("/material/{materialId}")
    public ResponseEntity<Map<String, String>> eliminarMaterial(@PathVariable String materialId) {
        semanaService.eliminarMaterial(idHasher.decode(materialId));

        return ResponseEntity.ok(Map.of("message", "Archivo eliminado exitosamente"));
    }

    @PreAuthorize("hasAuthority('TEACHER')")
    @PatchMapping("/{semanaId}/nombre-tema")
    public ResponseEntity<?> renombrarSemana(
            @PathVariable String semanaId,
            @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(
                    semanaService.renombrarSemana(idHasher.decode(semanaId), body.get("nombreTema")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("hasAuthority('TEACHER')")
    @DeleteMapping("/{semanaId}")
    public ResponseEntity<?> eliminarSemana(@PathVariable String semanaId) {
        try {
            semanaService.eliminarSemana(idHasher.decode(semanaId));
            return ResponseEntity.ok(Map.of("message", "Semana eliminada exitosamente"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("hasAuthority('TEACHER')")
    @PatchMapping("/material/{materialId}/visibilidad")
    public ResponseEntity<Map<String, Object>> toggleVisibilidadMaterial(@PathVariable String materialId) {

        boolean estadoActualizado = semanaService.toggleVisibilidadMaterial(idHasher.decode(materialId));

        return ResponseEntity.ok(Map.of(
                "message", "Visibilidad actualizada",
                "visible", estadoActualizado));
    }

    @PreAuthorize("hasAuthority('TEACHER')")
    @PatchMapping("/{semanaId}/toggle-habilitada")
    public ResponseEntity<Map<String, Object>> toggleHabilitada(@PathVariable String semanaId) {
        boolean estadoActualizado = semanaService.toggleHabilitada(idHasher.decode(semanaId));

        return ResponseEntity.ok(Map.of(
                "message", "Estado de la semana actualizado",
                "habilitada", estadoActualizado));
    }
}