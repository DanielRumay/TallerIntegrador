package com.example.tallerintegrador.service.rag;

import com.example.tallerintegrador.entidades.postgres.Material;
import com.example.tallerintegrador.entidades.postgres.Semana;
import com.example.tallerintegrador.repository.MaterialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Las preguntas de práctica deben salir de TODOS los materiales visibles de la semana, no solo
 * del primero que la pantalla puso en la URL.
 */
class AlcanceMaterialesServiceTest {

    private MaterialRepository repo;
    private AlcanceMaterialesService servicio;
    private Semana semana;

    @BeforeEach
    void setUp() {
        repo = mock(MaterialRepository.class);
        servicio = new AlcanceMaterialesService(repo);
        semana = new Semana();
        semana.setId(7L);
    }

    private Material material(String mongoId, boolean visible) {
        Material m = new Material();
        m.setMongoId(mongoId);
        m.setVisible(visible);
        m.setSemana(semana);
        return m;
    }

    @Test
    @DisplayName("Con el id del primer material, se busca en todos los de la semana")
    void ampliaATodaLaSemana() {
        var determinantes = material("m-determinantes", true);
        var signo = material("m-signo", true);
        when(repo.findByMongoId("m-determinantes")).thenReturn(Optional.of(determinantes));
        when(repo.findBySemanaId(7L)).thenReturn(List.of(determinantes, signo));

        assertEquals("m-determinantes,m-signo", servicio.ampliarASemana("m-determinantes"));
    }

    @Test
    @DisplayName("Los materiales ocultos por la docente quedan fuera")
    void excluyeOcultos() {
        var visible = material("m-visible", true);
        var oculto = material("m-oculto", false);
        when(repo.findByMongoId("m-visible")).thenReturn(Optional.of(visible));
        when(repo.findBySemanaId(7L)).thenReturn(List.of(visible, oculto));

        assertEquals("m-visible", servicio.ampliarASemana("m-visible"));
    }

    @Test
    @DisplayName("Si todo está oculto, NO se busca sin filtro: se conserva el id recibido")
    void todoOcultoNoQuitaElFiltro() {
        var oculto = material("m-oculto", false);
        when(repo.findByMongoId("m-oculto")).thenReturn(Optional.of(oculto));
        when(repo.findBySemanaId(7L)).thenReturn(List.of(oculto));

        String resultado = servicio.ampliarASemana("m-oculto");
        assertEquals("m-oculto", resultado);
        assertFalse(resultado.isBlank(), "un filtro vacío buscaría en los materiales de todos los cursos");
    }

    @Test
    @DisplayName("Un id que no es un material registrado (semana antigua) se respeta tal cual")
    void idDesconocidoSeRespeta() {
        when(repo.findByMongoId("semana-antigua")).thenReturn(Optional.empty());
        assertEquals("semana-antigua", servicio.ampliarASemana("semana-antigua"));
    }
}
