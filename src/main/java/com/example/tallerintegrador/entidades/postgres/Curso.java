package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;

import jakarta.persistence.*;
import java.util.List;

@Entity
@Table(name = "cursos")
public class Curso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    private String descripcion;

    @ManyToMany(mappedBy = "cursos")
    private List<Grado> grados;
}