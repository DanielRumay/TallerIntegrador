package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
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

    /**
     * @BatchSize: al listar las semanas de un curso, Hibernate pedía los materiales con una
     * consulta por semana. Con el lote, resuelve hasta 25 semanas en una sola. No cambia el
     * comportamiento —sigue siendo carga perezosa—, solo agrupa las idas a la base.
     */
    @BatchSize(size = 25)
    @OneToMany(mappedBy = "semana", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Material> materiales;
}