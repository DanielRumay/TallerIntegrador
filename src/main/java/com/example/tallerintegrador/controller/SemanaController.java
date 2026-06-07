package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.DTO.SemanaDTO;
import com.example.tallerintegrador.service.RagIngestionService;
import com.example.tallerintegrador.service.SemanaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map; // <-- NO OLVIDES IMPORTAR ESTO

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/semanas")
@RequiredArgsConstructor
public class SemanaController {

    private final SemanaService semanaService;
    private final RagIngestionService ragIngestionService;

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('STUDENT')")
    @GetMapping("/{semanaId}")
    public ResponseEntity<SemanaDTO> obtenerSemana(@PathVariable Long semanaId) {
        return ResponseEntity.ok(semanaService.obtenerSemana(semanaId));
    }

    @PreAuthorize("hasAuthority('TEACHER')")
    @PostMapping(value = "/{semanaId}/archivos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SemanaDTO> subirArchivos(
            @PathVariable Long semanaId,
            @RequestParam("archivos") List<MultipartFile> archivos) {
        return ResponseEntity.ok(semanaService.subirArchivos(semanaId, archivos));
    }

    @PreAuthorize("hasAuthority('TEACHER')")
    @DeleteMapping("/material/{materialId}")
    public ResponseEntity<Map<String, String>> eliminarMaterial(@PathVariable Long materialId) {
        semanaService.eliminarMaterial(materialId);

        return ResponseEntity.ok(Map.of("message", "Archivo eliminado exitosamente"));
    }

    @PreAuthorize("hasAuthority('TEACHER')")
    @PatchMapping("/material/{materialId}/visibilidad")
    public ResponseEntity<Map<String, Object>> toggleVisibilidadMaterial(@PathVariable Long materialId) {

        boolean estadoActualizado = semanaService.toggleVisibilidadMaterial(materialId);

        return ResponseEntity.ok(Map.of(
                "message", "Visibilidad actualizada",
                "visible", estadoActualizado
        ));
    }
}