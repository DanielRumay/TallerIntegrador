package com.example.tallerintegrador.service.academico;

import com.example.tallerintegrador.DTO.CreateUserRequest;
import com.example.tallerintegrador.DTO.UserResponseDTO;
import com.example.tallerintegrador.entidades.postgres.Colegio;
import com.example.tallerintegrador.entidades.postgres.Permiso;
import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.exception.RecursoNoEncontradoException;
import com.example.tallerintegrador.repository.ColegioRepository;
import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.repository.mongo.BackupBDRepository;
import com.example.tallerintegrador.seguridad.ContextoUsuarioService;
import com.example.tallerintegrador.seguridad.SesionDePrueba;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** La gestión de cuentas de un administrador se limita a docentes y estudiantes de SU colegio. */
class AdminUserServiceColegioTest {

    private UserRepository users;
    private ContextoUsuarioService contextos;
    private AdminUserService servicio;
    private Colegio colegioA;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        contextos = mock(ContextoUsuarioService.class);
        ColegioRepository colegios = mock(ColegioRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("hash");
        when(users.save(any(Usuario.class))).thenAnswer(i -> i.getArgument(0));
        colegioA = colegio(1L);
        when(colegios.getReferenceById(1L)).thenReturn(colegioA);
        servicio = new AdminUserService(users, encoder, mock(BackupBDRepository.class),
                mock(AuditoriaService.class), colegios, contextos);
        SesionDePrueba.iniciar(10L, "adm@a.pe", Rol.ADMIN, 1L, true, EnumSet.allOf(Permiso.class));
    }

    @AfterEach
    void limpiar() {
        SesionDePrueba.cerrar();
    }

    private static Colegio colegio(long id) {
        Colegio c = new Colegio("C" + id);
        c.setId(id);
        return c;
    }

    private Usuario usuario(long id, Rol rol, Colegio c) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setNombre("U" + id);
        u.setCorreo("u" + id + "@x.pe");
        u.setRol(rol);
        u.setColegio(c);
        when(users.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    @DisplayName("El listado trae solo docentes y estudiantes de su colegio")
    void listado() {
        List<Usuario> delColegio = List.of(
                usuario(1, Rol.TEACHER, colegioA), usuario(2, Rol.STUDENT, colegioA), usuario(3, Rol.ADMIN, colegioA));
        when(users.findByColegioId(1L)).thenReturn(delColegio);
        assertThat(servicio.obtenerTodosLosUsuarios()).extracting(UserResponseDTO::id).containsExactly(1L, 2L);
        verify(users, never()).findAll();
    }

    @Test
    @DisplayName("La cuenta creada es del colegio del administrador, aunque el correo exista en otro")
    void creacion() {
        when(users.existeCorreo("nuevo@a.pe")).thenReturn(false);
        servicio.registrarUsuario(new CreateUserRequest("Nuevo", "nuevo@a.pe", "student", "clave1234"));
        verify(users).save(argThat(u -> u.getColegio() == colegioA && u.getCreadoPorId() == 10L));

        when(users.existeCorreo("repetido@b.pe")).thenReturn(true);
        assertThatThrownBy(() -> servicio.registrarUsuario(new CreateUserRequest("R", "repetido@b.pe", "student", "clave1234")))
                .hasMessageContaining("ya está registrado");
    }

    @Test
    @DisplayName("Bloquear, desbloquear, cambiar contraseña o eliminar en otro colegio: 'no encontrado'")
    void otroColegio() {
        usuario(20, Rol.STUDENT, colegio(2L));
        assertThatThrownBy(() -> servicio.toggleBlockUsuario(20L)).isInstanceOf(RecursoNoEncontradoException.class);
        assertThatThrownBy(() -> servicio.desbloquearUsuario(20L)).isInstanceOf(RecursoNoEncontradoException.class);
        assertThatThrownBy(() -> servicio.cambiarPasswordUsuario(20L, "otra1234")).isInstanceOf(RecursoNoEncontradoException.class);
        assertThatThrownBy(() -> servicio.eliminarUsuario(20L)).isInstanceOf(RecursoNoEncontradoException.class);
        verify(users, never()).deleteById(any());
    }

    @Test
    @DisplayName("Las cuentas de administrador no se gestionan desde aquí")
    void administradores() {
        usuario(30, Rol.ADMIN, colegioA);
        assertThatThrownBy(() -> servicio.toggleBlockUsuario(30L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.eliminarUsuario(30L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Bloquear corta la sesión abierta del usuario (invalida su contexto)")
    void bloqueoRevoca() {
        usuario(40, Rol.STUDENT, colegioA);
        assertThat(servicio.toggleBlockUsuario(40L)).isTrue();
        verify(contextos).invalidar("u40@x.pe");
    }
}
