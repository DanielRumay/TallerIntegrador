package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.DTO.CreateUserRequest;
import com.example.tallerintegrador.DTO.UserResponseDTO;
import com.example.tallerintegrador.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/admin/usuarios")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @PreAuthorize("hasAuthority('ADMIN')")
    @PostMapping("/crear")
    public ResponseEntity<?> crearUsuario(@RequestBody CreateUserRequest request) {
        try {
            return ResponseEntity.ok(adminUserService.registrarUsuario(request));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> eliminarUsuario(@PathVariable Long id) {
        try {
            adminUserService.eliminarUsuario(id);
            return ResponseEntity.ok(Map.of("message", "Usuario eliminado exitosamente"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @GetMapping
    public ResponseEntity<List<UserResponseDTO>> listarUsuarios() {
        return ResponseEntity.ok(adminUserService.obtenerTodosLosUsuarios());
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @GetMapping("/metricas")
    public ResponseEntity<?> obtenerMetricasAdmin() {
        return ResponseEntity.ok(adminUserService.obtenerMetricasAdmin());
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @PutMapping("/{id}/toggle-block")
    public ResponseEntity<?> toggleBlockUsuario(@PathVariable Long id) {
        try {
            boolean isBlocked = adminUserService.toggleBlockUsuario(id);
            return ResponseEntity.ok(Map.of(
                "message", "Estado de bloqueo actualizado", 
                "cuentaBloqueada", isBlocked
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @PostMapping("/crear-masivo")
    public ResponseEntity<?> crearUsuariosMasivo(@RequestBody List<CreateUserRequest> requests) {
        try {
            List<UserResponseDTO> creados = adminUserService.registrarUsuariosMasivo(requests);
            return ResponseEntity.ok(Map.of("message", "Se registraron " + creados.size() + " usuarios exitosamente.", "usuarios", creados));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @PutMapping("/{id}/desbloquear")
    public ResponseEntity<?> desbloquearUsuario(@PathVariable Long id) {
        try {
            adminUserService.desbloquearUsuario(id);
            return ResponseEntity.ok(Map.of("message", "Usuario desbloqueado exitosamente"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}