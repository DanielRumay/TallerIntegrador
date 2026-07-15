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

    @PreAuthorize("hasAuthority('ADMIN')")
    @PutMapping("/{id}/change-password")
    public ResponseEntity<?> cambiarPasswordUsuario(@PathVariable Long id, @RequestBody Map<String, String> request) {
        try {
            String nuevaPassword = request.get("password");
            adminUserService.cambiarPasswordUsuario(id, nuevaPassword);
            return ResponseEntity.ok(Map.of("message", "Contraseña actualizada exitosamente"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PreAuthorize("hasAuthority('ADMIN')")
    @GetMapping("/backup")
    public ResponseEntity<?> backupDatabase() {
        try {
            byte[] backupBytes = adminUserService.generarYGuardarBackup();
            String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(java.time.LocalDateTime.now());
            String fileName = "colegio_db_backup_" + timestamp + ".sql";
            return ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .contentType(org.springframework.http.MediaType.parseMediaType("application/sql"))
                    .body(backupBytes);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}