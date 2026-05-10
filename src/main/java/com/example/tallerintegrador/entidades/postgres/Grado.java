package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import java.util.List;

@Entity
@Table(name = "grados")
public class Grado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    @OneToMany(mappedBy = "grado")
    private List<Usuario> alumnos;

    @ManyToMany
    @JoinTable(
            name = "grado_curso",
            joinColumns = @JoinColumn(name = "grado_id"),
            inverseJoinColumns = @JoinColumn(name = "curso_id")
    )
    private List<Curso> cursos;
}