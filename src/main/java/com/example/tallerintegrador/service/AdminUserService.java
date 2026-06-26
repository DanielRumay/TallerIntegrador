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
                .map(u -> new UserResponseDTO(u.getId(), u.getNombre(), u.getCorreo(), u.getRol().name(), u.isCuentaBloqueada()))
                .toList();
    }

    @Transactional
    public UserResponseDTO registrarUsuario(CreateUserRequest req) {

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
        nuevoUsuario.setRequiresPasswordSetup(true);

        Usuario guardado = userRepository.save(nuevoUsuario);

        return new UserResponseDTO(
                guardado.getId(),
                guardado.getNombre(),
                guardado.getCorreo(),
                guardado.getRol().name(),
                guardado.isCuentaBloqueada()
        );
    }

    @Transactional
    public void eliminarUsuario(Long id) {
        if (!userRepository.existsById(id)) {
            throw new RuntimeException("Usuario no encontrado");
        }
        userRepository.deleteById(id);
    }

    @Transactional
    public List<UserResponseDTO> registrarUsuariosMasivo(List<CreateUserRequest> requests) {
        return requests.stream().map(req -> {
            if (userRepository.findByCorreo(req.email()).isPresent()) {
                throw new RuntimeException("El correo ya está registrado en el sistema: " + req.email());
            }
            Usuario nuevoUsuario = new Usuario();
            nuevoUsuario.setNombre(req.name());
            nuevoUsuario.setCorreo(req.email());
            nuevoUsuario.setRol(req.role().equalsIgnoreCase("teacher") ? Rol.TEACHER : Rol.STUDENT);
            
            // Password genérico temporal y requiere setup
            nuevoUsuario.setPassword(passwordEncoder.encode(req.password() != null && !req.password().trim().isEmpty() ? req.password() : "123456"));
            nuevoUsuario.setRequiresPasswordSetup(true);

            Usuario guardado = userRepository.save(nuevoUsuario);
            return new UserResponseDTO(guardado.getId(), guardado.getNombre(), guardado.getCorreo(), guardado.getRol().name(), guardado.isCuentaBloqueada());
        }).toList();
    }

    @Transactional
    public void desbloquearUsuario(Long id) {
        Usuario usuario = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        usuario.setIntentosFallidos(0);
        usuario.setCuentaBloqueada(false);
        userRepository.save(usuario);
    }

    public Object obtenerMetricasAdmin() {
        long totalEstudiantes = userRepository.findAll().stream().filter(u -> u.getRol() == Rol.STUDENT).count();
        long totalDocentes = userRepository.findAll().stream().filter(u -> u.getRol() == Rol.TEACHER).count();
        long usuariosActivos = userRepository.findAll().stream().filter(u -> !u.isCuentaBloqueada()).count();
        
        return java.util.Map.of(
            "totalEstudiantes", totalEstudiantes,
            "totalDocentes", totalDocentes,
            "usuariosActivos", usuariosActivos
        );
    }

    public boolean toggleBlockUsuario(Long id) {
        Usuario usuario = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado con ID: " + id));
        usuario.setCuentaBloqueada(!usuario.isCuentaBloqueada());
        // Si lo estamos desbloqueando, reseteamos los intentos
        if (!usuario.isCuentaBloqueada()) {
            usuario.setIntentosFallidos(0);
        }
        userRepository.save(usuario);
        return usuario.isCuentaBloqueada();
    }
}