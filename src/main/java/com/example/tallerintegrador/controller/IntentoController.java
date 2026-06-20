package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.DTO.GuardarIntentoRequest;
import com.example.tallerintegrador.service.IntentoService;
import com.example.tallerintegrador.service.util.IdHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/intentos")
@RequiredArgsConstructor
public class IntentoController {

    private final IntentoService intentoService;
    private final IdHasher idHasher;

    @PreAuthorize("hasAuthority('STUDENT')")
    @PostMapping("/guardar")
    public ResponseEntity<?> guardarIntento(@RequestBody GuardarIntentoRequest request) {
        try {
            intentoService.guardarIntentoCompleto(request);
            return ResponseEntity.ok(Map.of("message", "Examen guardado correctamente en el historial"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("hasAuthority('STUDENT')")
    @GetMapping("/mis-intentos/{usuarioId}")
    public ResponseEntity<?> misIntentos(@PathVariable Long usuarioId) {
        return ResponseEntity.ok(intentoService.obtenerIntentosPorUsuario(usuarioId));
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/semana/{semanaId}")
    public ResponseEntity<?> intentosPorSemana(@PathVariable String semanaId) {
        return ResponseEntity.ok(intentoService.obtenerIntentosPorSemana(idHasher.decode(semanaId)));
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/todos")
    public ResponseEntity<?> obtenerTodosLosIntentos() {
        return ResponseEntity.ok(intentoService.obtenerTodosLosIntentos());
    }
}