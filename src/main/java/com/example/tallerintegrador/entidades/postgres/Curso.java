package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "curso")
public class Curso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    private String descripcion;

    @ManyToOne
    @JoinColumn(name = "grado_id")
    private Grado grado;

    @ManyToOne
    @JoinColumn(name = "seccion_id")
    private Seccion seccion;

    /**
     * Docente TITULAR: quien creó el curso. Se conserva tal cual (misma columna) para no tocar
     * los datos existentes. Es el único, junto al administrador, que puede añadir o quitar
     * co-docentes.
     */
    @ManyToOne
    @JoinColumn(name = "profesor_id")
    private Usuario profesor;

    /**
     * CO-DOCENTES: otros profesores con acceso completo al curso (semanas, materiales,
     * alumnos, banco de preguntas), salvo gestionar a los propios docentes.
     *
     * POR QUÉ UNA TABLA APARTE Y NO CAMBIAR `profesor` A UNA LISTA. Convertir la columna
     * existente obligaría a migrar los cursos ya creados; añadir una tabla intermedia no toca
     * ni una fila y, con ddl-auto=update, Hibernate la crea sola al arrancar.
     *
     * @JsonIgnore: son entidades Usuario (con su hash de contraseña). Nunca deben salir
     * serializadas por accidente; la API expone solo nombre y correo.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToMany
    @JoinTable(name = "curso_profesor",
            joinColumns = @JoinColumn(name = "curso_id"),
            inverseJoinColumns = @JoinColumn(name = "profesor_id"))
    private java.util.Set<Usuario> coDocentes = new java.util.HashSet<>();

    /** ¿Es el titular del curso? */
    public boolean esTitular(Long usuarioId) {
        return usuarioId != null && profesor != null && usuarioId.equals(profesor.getId());
    }

    /** ¿Enseña este curso, como titular o como co-docente? */
    public boolean esDocente(Long usuarioId) {
        if (usuarioId == null) return false;
        if (esTitular(usuarioId)) return true;
        return coDocentes != null && coDocentes.stream().anyMatch(u -> usuarioId.equals(u.getId()));
    }

    private String emoji;

    /** Clave heredada (primary, lime, coral) o un color hexadecimal (#RRGGBB). */
    private String color;

    /** null = sin portada. Cambia al reemplazarla, para invalidar la caché (ver CursoBanner). */
    @Column(name = "banner_version")
    private Long bannerVersion;
}