package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.service.RendimientoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/rendimiento")
@RequiredArgsConstructor
public class RendimientoController {

    private final RendimientoService rendimientoService;

    /**
     * GET /api/rendimiento/mapa-calor/{usuarioId}
     * Devuelve la actividad del alumno para el componente heatmap delegando al servicio.
     */
    @PreAuthorize("hasAuthority('STUDENT') or hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/mapa-calor/{usuarioId}")
    public ResponseEntity<?> obtenerMapaCalor(@PathVariable Long usuarioId) {
        try {
            return ResponseEntity.ok(rendimientoService.obtenerMapaCalor(usuarioId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
