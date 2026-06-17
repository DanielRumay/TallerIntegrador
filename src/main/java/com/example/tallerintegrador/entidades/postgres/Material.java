package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime; // <-- ¡No olvides este import!

@Getter
@Setter
@Entity
@Table(name = "material")
public class Material {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombreArchivo;
    private String mongoId;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean visible = true;

    private LocalDateTime fechaCarga = LocalDateTime.now();

    @ManyToOne
    @JoinColumn(name = "semana_id")
    private Semana semana;

    @Enumerated(EnumType.STRING)
    private NivelDificultad nivelDificultad;

    @Column(columnDefinition = "TEXT")
    private String tagsConceptos;
}