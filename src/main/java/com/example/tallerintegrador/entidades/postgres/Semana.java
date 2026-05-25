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

    @ManyToOne
    @JoinColumn(name = "curso_id")
    private Curso curso;

    private String mongoId;

    @OneToMany(mappedBy = "semana")
    private List<Pregunta> preguntas;

    @OneToMany(mappedBy = "semana", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Material> materiales;
}