package com.example.tallerintegrador.service;

import com.example.tallerintegrador.DTO.LoginRequest;
import com.example.tallerintegrador.DTO.UserDto;
import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.service.util.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder; // <-- NUEVO IMPORT
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository usuarioRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public Optional<UserDto> autenticar(LoginRequest request) {
        var usuarioOpt = usuarioRepository.findByCorreo(request.getEmail());

        if (usuarioOpt.isEmpty()) {
            System.out.println("Falla: El correo no existe en la BD.");
            return Optional.empty();
        }

        var usuario = usuarioOpt.get();

        boolean passwordCoincide = passwordEncoder.matches(request.getPassword(), usuario.getPassword());

        if (passwordCoincide) {
            System.out.println("🎉 Login exitoso para: " + usuario.getCorreo());

            String tokenGenerado = jwtService.generarToken(
                    String.valueOf(usuario.getId()),
                    usuario.getCorreo(),
                    usuario.getRol().name()
            );

            return Optional.of(UserDto.builder()
                    .id(String.valueOf(usuario.getId()))
                    .email(usuario.getCorreo())
                    .role(usuario.getRol().name())
                    .name(usuario.getNombre())
                    .token(tokenGenerado)
                    .build());
        }
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        System.out.println(encoder.encode("admin123"));

        System.out.println("Falla: Contraseña incorrecta.");
        return Optional.empty();
    }
}