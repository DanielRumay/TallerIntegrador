package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Setter
@Getter
@Entity
@Table(name = "pregunta")
public class Pregunta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT")
    private String Pregunta;

    private Tipo tipodepregunta;

    @ManyToOne
    @JoinColumn(name = "semana_id")
    private Semana semana;

    @Enumerated(EnumType.STRING)
    @Column(name = "nivel_dificultad")
    private NivelDificultad nivelDificultad;

    @OneToMany(mappedBy = "pregunta", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<Respuesta> respuestas;
}
