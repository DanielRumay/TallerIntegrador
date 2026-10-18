package com.example.tallerintegrador.service.academico;

import com.example.tallerintegrador.entidades.postgres.Colegio;
import com.example.tallerintegrador.entidades.postgres.Permiso;
import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.exception.RecursoNoEncontradoException;
import com.example.tallerintegrador.repository.ColegioRepository;
import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.seguridad.ContextoUsuarioService;
import com.example.tallerintegrador.seguridad.SesionDePrueba;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Reglas contra la escalada de privilegios entre administradores de un colegio. */
class AdministradoresServiceTest {

    private UserRepository users;
    private ColegioRepository colegios;
    private ContextoUsuarioService contextos;
    private AuditoriaService auditoria;
    private AdministradoresService servicio;
    private Colegio colegioA;

    /** Miniadministrador que gestiona administradores, pero solo con USO_VER además. */
    private static final Set<Permiso> PERMISOS_ACTOR = EnumSet.of(Permiso.ADMINISTRADORES_GESTIONAR, Permiso.USO_VER);

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        colegios = mock(ColegioRepository.class);
        contextos = mock(ContextoUsuarioService.class);
        auditoria = mock(AuditoriaService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("hash");
        when(users.save(any(Usuario.class))).thenAnswer(i -> i.getArgument(0));
        colegioA = new Colegio("A");
        colegioA.setId(1L);
        when(colegios.getReferenceById(1L)).thenReturn(colegioA);
        servicio = new AdministradoresService(users, colegios, encoder, auditoria, contextos);
        SesionDePrueba.iniciar(10L, "mini@a.pe", Rol.ADMIN, 1L, false, PERMISOS_ACTOR);
    }

    @AfterEach
    void limpiar() {
        SesionDePrueba.cerrar();
    }

    private Usuario admin(long id, long colegioId, boolean principal, Set<Permiso> permisos) {
        Colegio c = new Colegio("C" + colegioId);
        c.setId(colegioId);
        Usuario u = new Usuario();
        u.setId(id);
        u.setCorreo("admin" + id + "@x.pe");
        u.setRol(Rol.ADMIN);
        u.setColegio(c);
        u.setAdminPrincipal(principal);
        u.setPermisos(permisos.isEmpty() ? EnumSet.noneOf(Permiso.class) : EnumSet.copyOf(permisos));
        when(users.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    @DisplayName("Crear con un permiso que el actor no tiene: rechazado")
    void noOtorgaLoQueNoTiene() {
        assertThatThrownBy(() -> servicio.crear("Ana", "ana@a.pe", "clave1234", EnumSet.of(Permiso.CUENTAS_GESTIONAR)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CUENTAS_GESTIONAR");
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("Crear con permisos propios: queda en su colegio, no principal, y se audita")
    void creaMiniadmin() {
        var dto = servicio.crear("Ana", "ana@a.pe", "clave1234", EnumSet.of(Permiso.USO_VER));
        assertThat(dto.permisos()).containsExactly("USO_VER");
        assertThat(dto.adminPrincipal()).isFalse();
        verify(users).save(argThat(u -> u.getColegio() == colegioA && u.getCreadoPorId() == 10L));
        verify(auditoria).registrar(any(), eq("administrador"), any(), contains("USO_VER"));
    }

    @Test
    @DisplayName("Un administrador debe tener al menos un permiso")
    void sinPermisos() {
        assertThatThrownBy(() -> servicio.crear("Ana", "ana@a.pe", "clave1234", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Tampoco puede QUITAR un permiso que no tiene")
    void noQuitaLoQueNoTiene() {
        admin(20L, 1L, false, EnumSet.of(Permiso.USO_VER, Permiso.AUDITORIA_VER));
        assertThatThrownBy(() -> servicio.cambiarPermisos(20L, EnumSet.of(Permiso.USO_VER)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("AUDITORIA_VER");
    }

    @Test
    @DisplayName("Cambio válido: rige de inmediato (invalida el contexto) y se audita")
    void cambioValido() {
        Usuario objetivo = admin(20L, 1L, false, EnumSet.of(Permiso.USO_VER, Permiso.AUDITORIA_VER));
        servicio.cambiarPermisos(20L, EnumSet.of(Permiso.AUDITORIA_VER)); // quita USO_VER, que sí tiene
        assertThat(objetivo.getPermisos()).containsExactly(Permiso.AUDITORIA_VER);
        verify(contextos).invalidar("admin20@x.pe");
        verify(auditoria).registrar(any(), eq("administrador"), eq(20L), contains("->"));
    }

    @Test
    @DisplayName("Nadie se modifica a sí mismo")
    void noAsiMismo() {
        admin(10L, 1L, false, PERMISOS_ACTOR);
        assertThatThrownBy(() -> servicio.cambiarPermisos(10L, EnumSet.allOf(Permiso.class)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.eliminar(10L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Un administrador principal no se toca desde aquí (bloquear, eliminar, permisos)")
    void principalIntocable() {
        admin(30L, 1L, true, EnumSet.allOf(Permiso.class));
        assertThatThrownBy(() -> servicio.alternarBloqueo(30L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.eliminar(30L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.cambiarPermisos(30L, Set.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Un administrador de otro colegio es 'no encontrado'")
    void otroColegio() {
        admin(40L, 2L, false, EnumSet.of(Permiso.USO_VER));
        assertThatThrownBy(() -> servicio.alternarBloqueo(40L)).isInstanceOf(RecursoNoEncontradoException.class);
        assertThatThrownBy(() -> servicio.cambiarPermisos(40L, Set.of())).isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    @DisplayName("Un docente no es un administrador gestionable")
    void soloAdministradores() {
        Usuario docente = admin(50L, 1L, false, Set.of());
        docente.setRol(Rol.TEACHER);
        assertThatThrownBy(() -> servicio.eliminar(50L)).isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    @DisplayName("Sin el permiso ADMINISTRADORES_GESTIONAR, ni siquiera el servicio responde")
    void sinPermisoDeGestion() {
        SesionDePrueba.iniciar(11L, "otro@a.pe", Rol.ADMIN, 1L, false, EnumSet.of(Permiso.USO_VER));
        assertThatThrownBy(() -> servicio.crear("X", "x@a.pe", "clave1234", EnumSet.of(Permiso.USO_VER)))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }
}
