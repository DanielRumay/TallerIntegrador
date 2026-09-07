package com.example.tallerintegrador.service.academico;

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

        if (usuario.isCuentaBloqueada()) {
            throw new IllegalStateException("La cuenta está bloqueada temporalmente debido a múltiples intentos fallidos de inicio de sesión. Por favor, contacte al administrador.");
        }

        boolean passwordCoincide = passwordEncoder.matches(request.getPassword(), usuario.getPassword());

        if (passwordCoincide) {
            System.out.println("🎉 Login exitoso para: " + usuario.getCorreo());

            if (usuario.getIntentosFallidos() > 0) {
                usuario.setIntentosFallidos(0);
                usuarioRepository.save(usuario);
            }

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
                    .consentimientoAceptado(usuario.isConsentimientoAceptado())
                    .requiresPasswordSetup(usuario.isRequiresPasswordSetup())
                    .build());
        }
        
        int nuevosIntentos = usuario.getIntentosFallidos() + 1;
        usuario.setIntentosFallidos(nuevosIntentos);
        if (nuevosIntentos >= 5) {
            usuario.setCuentaBloqueada(true);
            usuarioRepository.save(usuario);
            throw new IllegalStateException("La cuenta ha sido bloqueada tras 5 intentos fallidos de inicio de sesión. Por favor, contacte al administrador.");
        }
        usuarioRepository.save(usuario);
        System.out.println("Falla: Contraseña incorrecta. Intentos fallidos: " + nuevosIntentos);
        throw new IllegalArgumentException("Credenciales incorrectas. Intentos fallidos: " + nuevosIntentos + "/5");
    }

    public boolean registrarConsentimiento(String correo, String version) {
        var usuarioOpt = usuarioRepository.findByCorreo(correo);
        if (usuarioOpt.isPresent()) {
            var usuario = usuarioOpt.get();
            usuario.setConsentimientoAceptado(true);
            usuario.setFechaAceptacionConsentimiento(java.time.LocalDateTime.now());
            usuario.setVersionPoliticaAceptada(version);
            usuarioRepository.save(usuario);
            return true;
        }
        return false;
    }

    public boolean setupPassword(String correo, String nuevaContrasena) {
        if (nuevaContrasena == null || nuevaContrasena.trim().length() < 6) {
            throw new IllegalArgumentException("La contraseña debe tener al menos 6 caracteres.");
        }
        var usuarioOpt = usuarioRepository.findByCorreo(correo);
        if (usuarioOpt.isPresent()) {
            var usuario = usuarioOpt.get();
            if (usuario.isRequiresPasswordSetup()) {
                usuario.setPassword(passwordEncoder.encode(nuevaContrasena));
                usuario.setRequiresPasswordSetup(false);
                usuario.setConsentimientoAceptado(true);
                usuario.setFechaAceptacionConsentimiento(java.time.LocalDateTime.now());
                usuario.setVersionPoliticaAceptada("v1.0 (Setup)");
                usuarioRepository.save(usuario);
                return true;
            }
        }
        return false;
    }
}