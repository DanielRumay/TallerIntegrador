package com.example.tallerintegrador.service;
import com.example.tallerintegrador.service.academico.AuthService;

import com.example.tallerintegrador.DTO.LoginRequest;
import com.example.tallerintegrador.DTO.UserDto;
import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.service.util.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtService jwtService;
    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, jwtService, passwordEncoder);
    }

    @Test
    void testAutenticarSuccessResetsAttempts() {
        // GIVEN
        LoginRequest req = new LoginRequest();
        req.setEmail("irwin@colegio.edu.pe");
        req.setPassword("teacher");

        Usuario user = new Usuario();
        user.setId(2L);
        user.setNombre("Profesor Irwin");
        user.setCorreo("irwin@colegio.edu.pe");
        user.setPassword("hashed_password");
        user.setRol(Rol.TEACHER);
        user.setIntentosFallidos(3);
        user.setCuentaBloqueada(false);

        when(userRepository.findByCorreo(req.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(req.getPassword(), user.getPassword())).thenReturn(true);
        when(jwtService.generarToken(anyString(), anyString(), anyString())).thenReturn("mockJwtToken");

        // WHEN
        Optional<UserDto> resultOpt = authService.autenticar(req);

        // THEN
        assertTrue(resultOpt.isPresent());
        UserDto userDto = resultOpt.get();
        assertEquals("mockJwtToken", userDto.getToken());
        assertEquals("irwin@colegio.edu.pe", userDto.getEmail());
        assertEquals("TEACHER", userDto.getRole());
        assertEquals(0, user.getIntentosFallidos());

        verify(userRepository).save(user);
    }

    @Test
    void testAutenticarIncorrectPasswordIncrementsCounter() {
        // GIVEN
        LoginRequest req = new LoginRequest();
        req.setEmail("irwin@colegio.edu.pe");
        req.setPassword("wrong_password");

        Usuario user = new Usuario();
        user.setId(2L);
        user.setCorreo("irwin@colegio.edu.pe");
        user.setPassword("hashed_password");
        user.setIntentosFallidos(2);
        user.setCuentaBloqueada(false);

        when(userRepository.findByCorreo(req.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(req.getPassword(), user.getPassword())).thenReturn(false);

        // WHEN & THEN
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            authService.autenticar(req);
        });

        assertTrue(ex.getMessage().contains("Credenciales incorrectas"));
        assertEquals(3, user.getIntentosFallidos());
        assertFalse(user.isCuentaBloqueada());

        verify(userRepository).save(user);
    }

    @Test
    void testAutenticarIncorrectPasswordLocksAccount() {
        // GIVEN
        LoginRequest req = new LoginRequest();
        req.setEmail("irwin@colegio.edu.pe");
        req.setPassword("wrong_password");

        Usuario user = new Usuario();
        user.setId(2L);
        user.setCorreo("irwin@colegio.edu.pe");
        user.setPassword("hashed_password");
        user.setIntentosFallidos(4);
        user.setCuentaBloqueada(false);

        when(userRepository.findByCorreo(req.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(req.getPassword(), user.getPassword())).thenReturn(false);

        // WHEN & THEN
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            authService.autenticar(req);
        });

        assertTrue(ex.getMessage().contains("bloqueada"));
        assertEquals(5, user.getIntentosFallidos());
        assertTrue(user.isCuentaBloqueada());

        verify(userRepository).save(user);
    }

    @Test
    void testAutenticarLockedAccountThrowsException() {
        // GIVEN
        LoginRequest req = new LoginRequest();
        req.setEmail("irwin@colegio.edu.pe");
        req.setPassword("any");

        Usuario user = new Usuario();
        user.setId(2L);
        user.setCorreo("irwin@colegio.edu.pe");
        user.setCuentaBloqueada(true);

        when(userRepository.findByCorreo(req.getEmail())).thenReturn(Optional.of(user));

        // WHEN & THEN
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            authService.autenticar(req);
        });

        assertTrue(ex.getMessage().contains("bloqueada"));
        verifyNoInteractions(passwordEncoder);
        verify(userRepository, never()).save(any());
    }
}
