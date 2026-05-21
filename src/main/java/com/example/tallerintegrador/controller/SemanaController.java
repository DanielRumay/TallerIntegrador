package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.DTO.SemanaDTO;
import com.example.tallerintegrador.service.SemanaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/semanas")
@RequiredArgsConstructor
public class SemanaController {

    private final SemanaService semanaService;

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

    // Cambiamos la ruta para que borre por ID de material, no por ID de semana
    @PreAuthorize("hasAuthority('TEACHER')")
    @DeleteMapping("/material/{materialId}")
    public ResponseEntity<Void> eliminarMaterial(@PathVariable Long materialId) {
        semanaService.eliminarMaterial(materialId);
        return ResponseEntity.noContent().build();
    }
}