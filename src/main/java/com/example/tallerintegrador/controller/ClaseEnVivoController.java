package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.seguridad.ContextoUsuario;
import com.example.tallerintegrador.service.analitica.ClaseEnVivoService;
import com.example.tallerintegrador.service.util.IdHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * Clase en vivo (ver ClaseEnVivoService). El docente crea y avanza la partida; el alumno se une
 * con el código y responde. Los ids salen siempre de la sesión, nunca del cuerpo.
 */
@RestController
@RequestMapping("/en-vivo")
@RequiredArgsConstructor
public class ClaseEnVivoController {

    private final ClaseEnVivoService servicio;
    private final IdHasher idHasher;

    public record Crear(String semanaId, List<ClaseEnVivoService.PreguntaVivo> preguntas, Integer segundos) {}

    @PreAuthorize("hasAuthority('TEACHER')")
    @PostMapping
    public ResponseEntity<?> crear(@RequestBody Crear body) {
        return responder(() -> {
            Long semana = idHasher.decode(body.semanaId());
            return Map.of("codigo", servicio.crear(ContextoUsuario.actual().id(), semana, body.preguntas(), body.segundos()));
        });
    }

    @PreAuthorize("hasAuthority('TEACHER')")
    @GetMapping("/{codigo}/tablero")
    public ResponseEntity<?> tablero(@PathVariable String codigo) {
        return responder(() -> servicio.tablero(ContextoUsuario.actual().id(), codigo));
    }

    @PreAuthorize("hasAuthority('TEACHER')")
    @PostMapping("/{codigo}/avanzar")
    public ResponseEntity<?> avanzar(@PathVariable String codigo) {
        return responder(() -> servicio.avanzar(ContextoUsuario.actual().id(), codigo));
    }

    @PreAuthorize("hasAuthority('STUDENT')")
    @PostMapping("/{codigo}/unirse")
    public ResponseEntity<?> unirse(@PathVariable String codigo) {
        return responder(() -> servicio.unirse(ContextoUsuario.actual().id(), codigo));
    }

    @PreAuthorize("hasAuthority('STUDENT')")
    @GetMapping("/{codigo}")
    public ResponseEntity<?> estado(@PathVariable String codigo) {
        return responder(() -> servicio.estado(ContextoUsuario.actual().id(), codigo));
    }

    @PreAuthorize("hasAuthority('STUDENT')")
    @PostMapping("/{codigo}/responder")
    public ResponseEntity<?> responder(@PathVariable String codigo, @RequestBody Map<String, Integer> body) {
        return responder(() -> servicio.responder(ContextoUsuario.actual().id(), codigo, body.getOrDefault("opcion", -1)));
    }

    private ResponseEntity<?> responder(Supplier<Object> accion) {
        try {
            return ResponseEntity.ok(accion.get());
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
