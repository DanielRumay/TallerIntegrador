package com.example.tallerintegrador.service.ia;

import com.example.tallerintegrador.entidades.postgres.Curso;
import com.example.tallerintegrador.entidades.postgres.Grado;
import com.example.tallerintegrador.entidades.postgres.Material;
import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.entidades.postgres.Semana;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.MaterialRepository;
import com.example.tallerintegrador.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Orden de resolucion de la audiencia: el grado del propio estudiante, despues el del curso al
 * que pertenece el material y, si nada se conoce, el perfil general. Nunca debe impedir generar.
 */
class AudienciaServiceTest {

    private UserRepository usuarios;
    private MaterialRepository materiales;
    private AudienciaService servicio;

    @BeforeEach
    void setUp() {
        usuarios = mock(UserRepository.class);
        materiales = mock(MaterialRepository.class);
        servicio = new AudienciaService(usuarios, materiales, new MarcoCneb());
    }

    private static Grado grado(String nombre) {
        Grado g = new Grado();
        ReflectionTestUtils.setField(g, "nombre", nombre);
        return g;
    }

    private static Usuario usuario(Rol rol, Grado grado) {
        Usuario u = new Usuario();
        u.setRol(rol);
        u.setGrado(grado);
        return u;
    }

    private static Material materialDeCurso(Grado grado) {
        Curso curso = new Curso();
        curso.setGrado(grado);
        Semana semana = new Semana();
        semana.setCurso(curso);
        Material material = new Material();
        material.setSemana(semana);
        return material;
    }

    @Test
    @DisplayName("Un estudiante con grado recibe la audiencia de su propio grado")
    void estudianteUsaSuGrado() {
        when(usuarios.findByCorreo("alumno@colegio.pe"))
                .thenReturn(Optional.of(usuario(Rol.STUDENT, grado("2do Año de Secundaria"))));
        when(materiales.findByMongoId("m1"))
                .thenReturn(Optional.of(materialDeCurso(grado("5to Año de Secundaria"))));

        PerfilAudiencia p = servicio.resolver("alumno@colegio.pe", "m1");

        assertThat(p.grado()).isEqualTo(2);
        assertThat(p.ciclo()).isEqualTo("VI");
    }

    @Test
    @DisplayName("Un docente genera para el grado del curso al que pertenece el material")
    void docenteUsaElGradoDelCurso() {
        when(usuarios.findByCorreo("docente@colegio.pe"))
                .thenReturn(Optional.of(usuario(Rol.TEACHER, null)));
        when(materiales.findByMongoId("m1"))
                .thenReturn(Optional.of(materialDeCurso(grado("5to Año de Secundaria"))));

        PerfilAudiencia p = servicio.resolver("docente@colegio.pe", "m1");

        assertThat(p.grado()).isEqualTo(5);
        assertThat(p.ciclo()).isEqualTo("VII");
    }

    @Test
    @DisplayName("Sin usuario ni material conocidos, perfil general")
    void sinDatosEsGeneral() {
        when(usuarios.findByCorreo(anyString())).thenReturn(Optional.empty());
        when(materiales.findByMongoId(anyString())).thenReturn(Optional.empty());

        assertThat(servicio.resolver("nadie@colegio.pe", "x")).isEqualTo(PerfilAudiencia.GENERAL);
        assertThat(servicio.resolver(null, null)).isEqualTo(PerfilAudiencia.GENERAL);
    }

    @Test
    @DisplayName("Un curso sin grado asignado no rompe la generación")
    void cursoSinGradoEsGeneral() {
        when(materiales.findByMongoId("m1")).thenReturn(Optional.of(materialDeCurso(null)));

        assertThat(servicio.resolver(null, "m1")).isEqualTo(PerfilAudiencia.GENERAL);
    }

    @Test
    @DisplayName("Si la base falla, se genera igual con el perfil general")
    void falloDeLaBaseEsGeneral() {
        when(usuarios.findByCorreo(anyString())).thenThrow(new IllegalStateException("sin conexion"));

        assertThat(servicio.resolver("alumno@colegio.pe", "m1")).isEqualTo(PerfilAudiencia.GENERAL);
    }
}
