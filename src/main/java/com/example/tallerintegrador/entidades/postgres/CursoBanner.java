package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Imagen de portada de un curso.
 *
 * POR QUE EN UNA TABLA APARTE y no como columna de `curso`. Los listados de cursos (panel del
 * alumno, del docente) cargan entidades Curso completas. Con la imagen dentro, cada listado
 * arrastraría cientos de KB por curso aunque la pantalla solo necesite el nombre y el color.
 * Aquí la imagen solo se lee cuando alguien pide GET /cursos/{id}/banner.
 *
 * `Curso.bannerVersion` hace de marca: null = sin portada; un número = hay portada, y cambia
 * cada vez que se reemplaza, así el frontend puede cachear la imagen por versión.
 */
@Getter
@Setter
@Entity
@Table(name = "curso_banner")
public class CursoBanner {

    /** Mismo id que el curso: hay como mucho una portada por curso. */
    @Id
    @Column(name = "curso_id")
    private Long cursoId;

    @Column(nullable = false)
    private byte[] imagen;

    @Column(name = "tipo_contenido", nullable = false, length = 40)
    private String tipoContenido;
}
