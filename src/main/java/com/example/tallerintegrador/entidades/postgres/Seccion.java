package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;

@Entity
@Table(name = "secciones")
public class Seccion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    @ManyToOne
    @JoinColumn(name = "grado_id")
    private Grado grado;

    // Getters y Setters
}