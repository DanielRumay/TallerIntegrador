package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.seguridad.ContextoUsuario;
import com.example.tallerintegrador.service.analitica.CustodiaRondaService;
import com.example.tallerintegrador.service.analitica.PoderesService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/** Lo que un alumno puede hacer con una ronda. Ninguna ruta acepta notas ni resultados del cliente. */
@RestController
@RequestMapping("/rondas")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('STUDENT')")
public class RondaController {

    private final CustodiaRondaService custodia;

    public record Respuesta(Integer indice, String respuesta) {}
    public record Comodin(Integer indice, PoderesService.Poder poder) {}
    public record Fin(Boolean contrarreloj) {}

    @PostMapping("/{id}/responder")
    public ResponseEntity<?> responder(@PathVariable String id, @RequestBody Respuesta body) {
        return atender(() -> custodia.responder(usuario(), id, indice(body.indice()), body.respuesta()));
    }

    @PostMapping("/{id}/comodin")
    public ResponseEntity<?> comodin(@PathVariable String id, @RequestBody Comodin body) {
        return atender(() -> custodia.comodin(usuario(), id, indice(body.indice()), body.poder()));
    }

    @PostMapping("/{id}/finalizar")
    public ResponseEntity<?> finalizar(@PathVariable String id, @RequestBody(required = false) Fin body) {
        return atender(() -> custodia.finalizar(usuario(), id, body != null && Boolean.TRUE.equals(body.contrarreloj())));
    }

    private static Long usuario() {
        return ContextoUsuario.actual().id();
    }

    private static int indice(Integer i) {
        if (i == null) throw new IllegalArgumentException("Pregunta no válida.");
        return i;
    }

    private ResponseEntity<?> atender(Supplier<Object> accion) {
        try {
            return ResponseEntity.ok(accion.get());
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
