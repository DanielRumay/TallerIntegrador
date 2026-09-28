package com.example.tallerintegrador.service.academico;

import com.example.tallerintegrador.entidades.postgres.RegistroAuditoria;
import com.example.tallerintegrador.repository.RegistroAuditoriaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditoriaServiceTest {

    private RegistroAuditoriaRepository repositorio;
    private AuditoriaService servicio;

    @BeforeEach
    void setUp() {
        repositorio = mock(RegistroAuditoriaRepository.class);
        servicio = new AuditoriaService(repositorio);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void autenticarComo(String correo, String rol) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(correo, null,
                        Collections.singletonList(new SimpleGrantedAuthority(rol))));
    }

    @Test
    @DisplayName("Guarda quien hizo que, sobre que recurso y cuando")
    void guardaLaAccionCompleta() {
        autenticarComo("docente@colegio.edu.pe", "TEACHER");

        servicio.registrar(RegistroAuditoria.Accion.MATERIAL_SUBIDO, "semana", 7L, "guia.pdf");

        ArgumentCaptor<RegistroAuditoria> captor = ArgumentCaptor.forClass(RegistroAuditoria.class);
        verify(repositorio).save(captor.capture());
        RegistroAuditoria r = captor.getValue();

        assertThat(r.getActorCorreo()).isEqualTo("docente@colegio.edu.pe");
        assertThat(r.getActorRol()).isEqualTo("TEACHER");
        assertThat(r.getAccion()).isEqualTo(RegistroAuditoria.Accion.MATERIAL_SUBIDO);
        assertThat(r.getRecursoTipo()).isEqualTo("semana");
        assertThat(r.getRecursoId()).isEqualTo(7L);
        assertThat(r.getDetalle()).isEqualTo("guia.pdf");
        assertThat(r.getFecha()).isNotNull();
    }

    @Test
    @DisplayName("Una accion sin sesion queda atribuida al sistema, no se pierde")
    void sinSesionAtribuyeAlSistema() {
        servicio.registrar(RegistroAuditoria.Accion.SEMANA_CREADA, "semana", 1L, "Semana 1");

        ArgumentCaptor<RegistroAuditoria> captor = ArgumentCaptor.forClass(RegistroAuditoria.class);
        verify(repositorio).save(captor.capture());
        assertThat(captor.getValue().getActorCorreo()).isEqualTo("sistema");
    }

    /**
     * La regla mas importante de todas: auditar es secundario frente a que el docente pueda
     * trabajar. Si el registro falla, la operacion de negocio NO puede caerse con el.
     */
    @Test
    @DisplayName("Si falla el registro, la operacion del docente no se rompe")
    void unFalloAlAuditarNoTumbaLaOperacion() {
        autenticarComo("docente@colegio.edu.pe", "TEACHER");
        doThrow(new RuntimeException("base de datos caida")).when(repositorio).save(any());

        // No debe lanzar nada.
        servicio.registrar(RegistroAuditoria.Accion.CURSO_CREADO, "curso", 3L, "Comunicacion");
    }
}
