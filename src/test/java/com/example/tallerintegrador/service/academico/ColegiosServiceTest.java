package com.example.tallerintegrador.service.academico;

import com.example.tallerintegrador.controller.SuperadminController;
import com.example.tallerintegrador.entidades.postgres.Colegio;
import com.example.tallerintegrador.entidades.postgres.Permiso;
import com.example.tallerintegrador.entidades.postgres.RegistroAuditoria.Accion;
import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.exception.RecursoNoEncontradoException;
import com.example.tallerintegrador.repository.ColegioRepository;
import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.seguridad.ContextoUsuarioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Operaciones del SUPERADMIN: alta de colegios, de su administrador principal y activación. */
class ColegiosServiceTest {

    private ColegioRepository colegios;
    private UserRepository users;
    private AuditoriaService auditoria;
    private ContextoUsuarioService contextos;
    private ColegiosService servicio;

    @BeforeEach
    void setUp() {
        colegios = mock(ColegioRepository.class);
        users = mock(UserRepository.class);
        auditoria = mock(AuditoriaService.class);
        contextos = mock(ContextoUsuarioService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("hash");
        when(colegios.findByNombreIgnoreCase(any())).thenReturn(Optional.empty());
        when(colegios.save(any(Colegio.class))).thenAnswer(i -> {
            Colegio c = i.getArgument(0);
            if (c.getId() == null) c.setId(7L);
            return c;
        });
        when(users.save(any(Usuario.class))).thenAnswer(i -> i.getArgument(0));
        servicio = new ColegiosService(colegios, users, encoder, auditoria, contextos);
    }

    @Test
    @DisplayName("Crear un colegio crea su administrador principal con todos los permisos y cambio de contraseña obligatorio")
    void creaColegioYPrincipal() {
        servicio.crear("  Colegio Beta ", "Admin Beta", "admin@beta.pe", "AdminBeta-2026");

        ArgumentCaptor<Usuario> admin = ArgumentCaptor.forClass(Usuario.class);
        verify(users).save(admin.capture());
        Usuario u = admin.getValue();
        assertThat(u.getRol()).isEqualTo(Rol.ADMIN);
        assertThat(u.getColegio().getNombre()).isEqualTo("Colegio Beta");
        assertThat(u.isAdminPrincipal()).isTrue();
        assertThat(u.getPermisos()).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(Permiso.class));
        assertThat(u.isRequiresPasswordSetup()).as("la contraseña la eligió el superadministrador").isTrue();
        assertThat(u.getPassword()).isEqualTo("hash");
        verify(auditoria).registrar(eq(Accion.COLEGIO_CREADO), eq("colegio"), eq(7L), eq("Colegio Beta"));
        verify(auditoria).registrar(eq(Accion.ADMIN_CREADO), eq("administrador"), any(), anyString());
    }

    @Test
    @DisplayName("No se crea un colegio con nombre repetido")
    void rechazaNombreRepetido() {
        when(colegios.findByNombreIgnoreCase("Colegio Beta")).thenReturn(Optional.of(new Colegio("Colegio Beta")));

        assertThatThrownBy(() -> servicio.crear("Colegio Beta", "Admin", "a@b.pe", "Clave-2026"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(colegios, never()).save(any());
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("No se crea un administrador principal con contraseña corta o correo ya registrado")
    void validaAdministradorPrincipal() {
        Colegio a = new Colegio("A");
        a.setId(1L);
        when(colegios.findById(1L)).thenReturn(Optional.of(a));
        when(users.existeCorreo("ya@existe.pe")).thenReturn(true);

        assertThatThrownBy(() -> servicio.agregarAdministradorPrincipal(1L, "Admin", "nuevo@a.pe", "corta"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.agregarAdministradorPrincipal(1L, "Admin", "ya@existe.pe", "Clave-2026"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.agregarAdministradorPrincipal(99L, "Admin", "nuevo@a.pe", "Clave-2026"))
                .isInstanceOf(RecursoNoEncontradoException.class);
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("Desactivar un colegio invalida los contextos en caché: sus sesiones caen en la siguiente petición")
    void desactivarCortaSesiones() {
        Colegio a = new Colegio("A");
        a.setId(1L);
        when(colegios.findById(1L)).thenReturn(Optional.of(a));

        ColegiosService.ColegioDTO dto = servicio.cambiarEstado(1L, false);

        assertThat(dto.activo()).isFalse();
        verify(contextos).invalidarTodo();
        verify(auditoria).registrar(eq(Accion.COLEGIO_DESACTIVADO), eq("colegio"), eq(1L), eq("A"));
    }

    @Test
    @DisplayName("Los endpoints de colegios solo son del SUPERADMIN")
    void soloSuperadmin() {
        PreAuthorize regla = SuperadminController.class.getAnnotation(PreAuthorize.class);
        assertThat(regla).isNotNull();
        assertThat(regla.value()).isEqualTo("hasAuthority('SUPERADMIN')");
    }
}
