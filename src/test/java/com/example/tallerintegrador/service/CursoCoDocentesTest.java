package com.example.tallerintegrador.service;

import com.example.tallerintegrador.entidades.postgres.Curso;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Un curso puede tener varios docentes: el titular (quien lo creó) y co-docentes. Estas son
 * las reglas de acceso de las que dependen el panel del docente y el banco de preguntas.
 */
class CursoCoDocentesTest {

    private Curso curso;
    private Usuario titular;
    private Usuario colega;

    private Usuario usuario(long id) {
        Usuario u = new Usuario();
        u.setId(id);
        return u;
    }

    @BeforeEach
    void setUp() {
        titular = usuario(1L);
        colega = usuario(2L);
        curso = new Curso();
        curso.setProfesor(titular);
    }

    @Test
    @DisplayName("El titular enseña el curso y es titular")
    void titular() {
        assertTrue(curso.esTitular(1L));
        assertTrue(curso.esDocente(1L));
    }

    @Test
    @DisplayName("Un co-docente enseña el curso, pero NO es titular")
    void coDocente() {
        curso.getCoDocentes().add(colega);
        assertTrue(curso.esDocente(2L));
        assertFalse(curso.esTitular(2L), "un co-docente no puede gestionar a los demás docentes");
    }

    @Test
    @DisplayName("Un profesor ajeno al curso no tiene acceso")
    void ajeno() {
        curso.getCoDocentes().add(colega);
        assertFalse(curso.esDocente(99L));
        assertFalse(curso.esTitular(99L));
    }

    @Test
    @DisplayName("Sin usuario no hay acceso, ni con curso sin titular")
    void nulos() {
        assertFalse(curso.esDocente(null));
        Curso huerfano = new Curso();
        assertFalse(huerfano.esDocente(1L));
        assertFalse(huerfano.esTitular(1L));
    }
}
