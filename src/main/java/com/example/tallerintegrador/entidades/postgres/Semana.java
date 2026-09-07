package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Entity
@Getter
@Setter
@Table(name = "semana")
public class Semana {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String numSem;

    /**
     * Nombre significativo de la semana (ej. "Revolución Francesa · Causas Económicas"),
     * derivado de los subtemas que Gemini extrae al ingestar el primer material. Antes
     * solo existía numSem ("Semana 1"), que no le dice nada al alumno sobre el contenido.
     */
    @Column(name = "nombre_tema")
    private String nombreTema;

    @ManyToOne
    @JoinColumn(name = "curso_id")
    private Curso curso;

    private String mongoId;

    @Column(name = "habilitada", nullable = false)
    private boolean habilitada = true;

    @OneToMany(mappedBy = "semana")
    private List<Pregunta> preguntas;

    @OneToMany(mappedBy = "semana", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Material> materiales;
}