package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.exception.IaNoDisponibleException;
import com.example.tallerintegrador.seguridad.ContextoUsuario;
import com.example.tallerintegrador.service.analitica.CharlaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.function.Supplier;

/** Entrada a la charla con Aria. Responder, comodines y cierre pasan por RondaController. */
@RestController
@RequestMapping("/charla")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('STUDENT')")
public class CharlaController {

    private final CharlaService charlas;

    public record Inicio(String mongoId, Boolean desafio) {}

    @PostMapping("/iniciar")
    public ResponseEntity<?> iniciar(@RequestBody Inicio body) {
        return atender(() -> charlas.iniciar(ContextoUsuario.actual().correo(), body.mongoId(),
                Boolean.TRUE.equals(body.desafio())));
    }

    @GetMapping("/estado")
    public ResponseEntity<?> estado(@RequestParam String mongoId) {
        return atender(() -> charlas.estado(ContextoUsuario.actual().correo(), mongoId));
    }

    private ResponseEntity<?> atender(Supplier<Object> accion) {
        try {
            return ResponseEntity.ok(accion.get());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (java.util.concurrent.CompletionException e) {
            if (e.getCause() instanceof IaNoDisponibleException ia) throw ia;
            throw e;
        }
    }
}
