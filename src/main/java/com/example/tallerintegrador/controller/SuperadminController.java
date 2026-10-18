package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.service.academico.ColegiosService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Gestión de colegios. Solo el rol SUPERADMIN, que no tiene acceso a ningún dato académico. */
@RestController
@RequestMapping("/superadmin/colegios")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPERADMIN')")
public class SuperadminController {

    private final ColegiosService colegiosService;

    public record CrearColegioRequest(String nombre, String adminNombre, String adminCorreo, String adminPassword) {}

    public record AdministradorRequest(String nombre, String correo, String password) {}

    public record EstadoRequest(boolean activo) {}

    @GetMapping
    public List<ColegiosService.ColegioDTO> listar() {
        return colegiosService.listar();
    }

    @PostMapping
    public ResponseEntity<?> crear(@RequestBody CrearColegioRequest req) {
        try {
            return ResponseEntity.ok(colegiosService.crear(req.nombre(), req.adminNombre(), req.adminCorreo(), req.adminPassword()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/{id}/administrador")
    public ResponseEntity<?> agregarAdministrador(@PathVariable Long id, @RequestBody AdministradorRequest req) {
        try {
            return ResponseEntity.ok(colegiosService.agregarAdministradorPrincipal(id, req.nombre(), req.correo(), req.password()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{id}/estado")
    public ColegiosService.ColegioDTO cambiarEstado(@PathVariable Long id, @RequestBody EstadoRequest req) {
        return colegiosService.cambiarEstado(id, req.activo());
    }
}
