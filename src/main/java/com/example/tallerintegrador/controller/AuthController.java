package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.DTO.LoginRequest;
import com.example.tallerintegrador.DTO.UserDto;
import com.example.tallerintegrador.service.academico.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.security.access.prepost.PreAuthorize;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final com.example.tallerintegrador.repository.SoporteRequestRepository soporteRepo;

    @PostMapping("/soporte")
    public ResponseEntity<?> solicitarSoporte(@RequestBody com.example.tallerintegrador.entidades.postgres.SoporteRequest request) {
        try {
            soporteRepo.save(request);
            return ResponseEntity.ok(Map.of("message", "Solicitud de soporte recibida exitosamente"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Error al procesar solicitud: " + e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            Optional<UserDto> userOpt = authService.autenticar(request);

            if (userOpt.isPresent()) {
                return ResponseEntity.ok(Map.of("user", userOpt.get()));
            } else {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Credenciales incorrectas o rol no autorizado"));
            }
        } catch (IllegalStateException e) {
            // Cuenta bloqueada
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            // Intentos fallidos
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", e.getMessage()));
        }
    }
    @GetMapping("/me")
    public ResponseEntity<?> me() {
        return ResponseEntity.ok(Map.of("message", "Endpoint activo"));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PreAuthorize("hasAuthority('STUDENT') or hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @PostMapping("/consent")
    public ResponseEntity<?> registrarConsentimiento(@RequestBody Map<String, String> body) {
        String correo = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
        String version = body.getOrDefault("version", "v1.0");
        boolean exito = authService.registrarConsentimiento(correo, version);
        if (exito) {
            return ResponseEntity.ok(Map.of("ok", true, "message", "Consentimiento registrado con éxito."));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Usuario no encontrado"));
        }
    }

    @PostMapping("/setup-password")
    public ResponseEntity<?> setupPassword(@RequestBody Map<String, String> body) {
        String correo = body.get("email");
        String nuevaContrasena = body.get("newPassword");
        
        if (authService.setupPassword(correo, nuevaContrasena)) {
            return ResponseEntity.ok(Map.of("message", "Contraseña configurada con éxito."));
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "No se pudo configurar la contraseña."));
        }
    }
}