package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;

@Entity
@Table(name = "grados")
public class Grado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    // Getters y Setters
}