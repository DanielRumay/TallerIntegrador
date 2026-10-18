package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.entidades.postgres.Permiso;
import com.example.tallerintegrador.service.academico.AdministradoresService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Administradores del colegio y sus permisos. Requiere PERM_ADMINISTRADORES_GESTIONAR; el
 * catálogo de permisos lo puede leer cualquier administrador (para mostrar su propio menú).
 */
@RestController
@RequestMapping("/admin/administradores")
@RequiredArgsConstructor
public class AdministradoresController {

    private final AdministradoresService administradoresService;

    public record CrearAdministradorRequest(String nombre, String correo, String password, Set<Permiso> permisos) {}

    public record PermisosRequest(Set<Permiso> permisos) {}

    @PreAuthorize("hasAuthority('ADMIN')")
    @GetMapping("/permisos")
    public List<Map<String, String>> catalogo() {
        return Arrays.stream(Permiso.values())
                .map(p -> Map.of("permiso", p.name(), "descripcion", p.getDescripcion()))
                .toList();
    }

    @PreAuthorize("hasAuthority('PERM_ADMINISTRADORES_GESTIONAR')")
    @GetMapping
    public List<AdministradoresService.AdministradorDTO> listar() {
        return administradoresService.listar();
    }

    @PreAuthorize("hasAuthority('PERM_ADMINISTRADORES_GESTIONAR')")
    @PostMapping
    public ResponseEntity<?> crear(@RequestBody CrearAdministradorRequest req) {
        try {
            return ResponseEntity.ok(administradoresService.crear(req.nombre(), req.correo(), req.password(), req.permisos()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PreAuthorize("hasAuthority('PERM_ADMINISTRADORES_GESTIONAR')")
    @PutMapping("/{id}/permisos")
    public ResponseEntity<?> cambiarPermisos(@PathVariable Long id, @RequestBody PermisosRequest req) {
        try {
            return ResponseEntity.ok(administradoresService.cambiarPermisos(id, req.permisos()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PreAuthorize("hasAuthority('PERM_ADMINISTRADORES_GESTIONAR')")
    @PutMapping("/{id}/toggle-block")
    public ResponseEntity<?> alternarBloqueo(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(Map.of("cuentaBloqueada", administradoresService.alternarBloqueo(id)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PreAuthorize("hasAuthority('PERM_ADMINISTRADORES_GESTIONAR')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> eliminar(@PathVariable Long id) {
        try {
            administradoresService.eliminar(id);
            return ResponseEntity.ok(Map.of("message", "Administrador eliminado"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
