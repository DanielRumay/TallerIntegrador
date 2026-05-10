package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "intentos")
public class Intento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Double puntaje;

    private Integer tiempoRespuesta;

    private String estado;

    private LocalDateTime fecha;

    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    // Getters y Setters
}