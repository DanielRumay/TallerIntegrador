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
     * Obtiene el mapa de calor de conocimiento para un alumno.
     * Devuelve datos agrupados por semana con porcentajes de acierto y nivel de dominio.
     */
    @PreAuthorize("hasAuthority('STUDENT') or hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/mapa-calor/{usuarioId}")
    public ResponseEntity<?> obtenerMapaCalor(@PathVariable Long usuarioId) {
        return ResponseEntity.ok(rendimientoService.obtenerMapaCalor(usuarioId));
    }
}
