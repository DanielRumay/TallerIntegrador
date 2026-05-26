package com.example.tallerintegrador.service;

import com.example.tallerintegrador.DTO.CreateUserRequest;
import com.example.tallerintegrador.DTO.UserResponseDTO;
import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.dominio-institucional}")
    private String dominioInstitucional;

    @Transactional(readOnly = true)
    public List<UserResponseDTO> obtenerTodosLosUsuarios() {
        return userRepository.findAll().stream()
                .map(u -> new UserResponseDTO(u.getId(), u.getNombre(), u.getCorreo(), u.getRol().name()))
                .toList();
    }

    @Transactional
    public UserResponseDTO registrarUsuario(CreateUserRequest req) {

        if (!req.email().toLowerCase().endsWith(dominioInstitucional)) {
            throw new RuntimeException("El correo debe pertenecer al dominio institucional (" + dominioInstitucional + ")");
        }

        if (userRepository.findByCorreo(req.email()).isPresent()) {
            throw new RuntimeException("El correo ya está registrado en el sistema.");
        }

        if (req.password() == null || req.password().trim().isEmpty()) {
            throw new RuntimeException("La contraseña no puede estar vacía.");
        }

        Usuario nuevoUsuario = new Usuario();
        nuevoUsuario.setNombre(req.name());
        nuevoUsuario.setCorreo(req.email());
        nuevoUsuario.setRol(req.role().equalsIgnoreCase("teacher") ? Rol.TEACHER : Rol.STUDENT);

        nuevoUsuario.setPassword(passwordEncoder.encode(req.password()));

        Usuario guardado = userRepository.save(nuevoUsuario);

        return new UserResponseDTO(
                guardado.getId(),
                guardado.getNombre(),
                guardado.getCorreo(),
                guardado.getRol().name()
        );
    }

    @Transactional
    public void eliminarUsuario(Long id) {
        if (!userRepository.existsById(id)) {
            throw new RuntimeException("Usuario no encontrado");
        }
        userRepository.deleteById(id);
    }
}