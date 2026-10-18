package com.example.tallerintegrador.seguridad;

import com.example.tallerintegrador.entidades.postgres.Colegio;
import com.example.tallerintegrador.entidades.postgres.Permiso;
import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * El contexto se lee de la base: un bloqueo, una baja, un colegio desactivado o un cambio de
 * permisos rigen en la siguiente petición, sin esperar a que caduque el token (RFS-08).
 */
class ContextoUsuarioServiceTest {

    private UserRepository repo;
    private ContextoUsuarioService servicio;
    private Colegio colegio;

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
        servicio = new ContextoUsuarioService(repo);
        colegio = new Colegio("Colegio A");
        colegio.setId(1L);
    }

    private Usuario usuario(String correo, Rol rol) {
        Usuario u = new Usuario();
        u.setId(5L);
        u.setCorreo(correo);
        u.setRol(rol);
        u.setColegio(colegio);
        when(repo.findByCorreo(correo)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    @DisplayName("Un usuario activo de un colegio activo obtiene su contexto")
    void usuarioActivo() {
        usuario("doc@a.pe", Rol.TEACHER);
        ContextoUsuario ctx = servicio.cargar("doc@a.pe").orElseThrow();
        assertThat(ctx.colegioId()).isEqualTo(1L);
        assertThat(ctx.authorities()).extracting(GrantedAuthority::getAuthority).containsExactly("TEACHER");
    }

    @Test
    @DisplayName("Cuenta bloqueada, eliminada o de colegio desactivado: sin contexto (401)")
    void rechazos() {
        usuario("bloq@a.pe", Rol.STUDENT).setCuentaBloqueada(true);
        assertThat(servicio.cargar("bloq@a.pe")).isEmpty();

        when(repo.findByCorreo("borrado@a.pe")).thenReturn(Optional.empty());
        assertThat(servicio.cargar("borrado@a.pe")).isEmpty();

        usuario("inactivo@a.pe", Rol.TEACHER);
        colegio.setActivo(false);
        assertThat(servicio.cargar("inactivo@a.pe")).isEmpty();
    }

    @Test
    @DisplayName("Un usuario sin colegio solo es válido si es SUPERADMIN")
    void sinColegio() {
        usuario("huerfano@a.pe", Rol.ADMIN).setColegio(null);
        assertThat(servicio.cargar("huerfano@a.pe")).isEmpty();

        usuario("sa@plataforma.pe", Rol.SUPERADMIN).setColegio(null);
        assertThat(servicio.cargar("sa@plataforma.pe")).isPresent();
    }

    @Test
    @DisplayName("Los permisos del ADMIN se exponen como PERM_*; los de otros roles no")
    void authorities() {
        Usuario admin = usuario("adm@a.pe", Rol.ADMIN);
        admin.setPermisos(EnumSet.of(Permiso.USO_VER, Permiso.AUDITORIA_VER));
        assertThat(servicio.cargar("adm@a.pe").orElseThrow().authorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ADMIN", "PERM_USO_VER", "PERM_AUDITORIA_VER");

        Usuario doc = usuario("doc2@a.pe", Rol.TEACHER);
        doc.setPermisos(EnumSet.of(Permiso.USO_VER)); // no debería pasar, pero no le da nada
        assertThat(servicio.cargar("doc2@a.pe").orElseThrow().authorities())
                .extracting(GrantedAuthority::getAuthority).containsExactly("TEACHER");
    }

    @Test
    @DisplayName("Usa el caché, e invalidar hace que el cambio rija en la siguiente petición")
    void cacheEInvalidacion() {
        Usuario u = usuario("doc@a.pe", Rol.TEACHER);
        servicio.cargar("doc@a.pe");
        servicio.cargar("doc@a.pe");
        verify(repo, times(1)).findByCorreo("doc@a.pe");

        u.setCuentaBloqueada(true);
        assertThat(servicio.cargar("doc@a.pe")).as("sin invalidar sigue el caché").isPresent();
        servicio.invalidar("doc@a.pe");
        assertThat(servicio.cargar("doc@a.pe")).as("tras invalidar, el bloqueo rige").isEmpty();
    }
}
