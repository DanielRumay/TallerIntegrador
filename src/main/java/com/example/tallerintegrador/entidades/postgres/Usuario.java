package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;

@Entity
@Table(name = "usuarios")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    @Column(unique = true)
    private String correo;

    private String password;

    @Enumerated(EnumType.STRING)
    private Rol rol;

    @ManyToOne
    @JoinColumn(name = "grado_id")
    private Grado grado;

    @ManyToOne
    @JoinColumn(name = "seccion_id")
    private Seccion seccion;
}