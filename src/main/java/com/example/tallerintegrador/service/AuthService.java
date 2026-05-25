package com.example.tallerintegrador.service;

import com.example.tallerintegrador.DTO.LoginRequest;
import com.example.tallerintegrador.DTO.UserDto;
import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.service.util.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository usuarioRepository;
    private final JwtService jwtService; // Inyectamos el creador de tokens

    public Optional<UserDto> autenticar(LoginRequest request) {
        System.out.println("=== INTENTO DE LOGIN ===");
        System.out.println("1. Frontend envía Correo: " + request.getEmail());

        var usuarioOpt = usuarioRepository.findByCorreo(request.getEmail());

        if (usuarioOpt.isEmpty()) {
            System.out.println("Falla: El correo no existe en la BD.");
            return Optional.empty();
        }

        var usuario = usuarioOpt.get();

        boolean passwordCoincide = usuario.getPassword().equals(request.getPassword());

        if (passwordCoincide) {
            System.out.println("🎉 Login exitoso para: " + usuario.getCorreo());

            // 1. Fabricamos el token con sus datos
            String tokenGenerado = jwtService.generarToken(
                    String.valueOf(usuario.getId()),
                    usuario.getCorreo(),
                    usuario.getRol().name()
            );

            // 2. Lo metemos en el DTO para mandarlo al frontend
            return Optional.of(UserDto.builder()
                    .id(String.valueOf(usuario.getId()))
                    .email(usuario.getCorreo())
                    .role(usuario.getRol().name())
                    .name(usuario.getNombre())
                    .token(tokenGenerado) // ¡Aquí viaja el token!
                    .build());
        }

        System.out.println("Falla: Contraseña incorrecta.");
        return Optional.empty();
    }
}