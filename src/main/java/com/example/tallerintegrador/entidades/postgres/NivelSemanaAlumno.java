package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Nivel de un alumno EN UNA SEMANA CONCRETA, obtenido de su prueba de ubicacion.
 *
 * POR QUE POR SEMANA Y NO UNO GLOBAL. Un alumno puede manejar bien fotosintesis y estar
 * perdido en genetica. Un unico nivel para todo el curso promedia esas dos cosas y produce
 * preguntas mal calibradas en ambos temas: demasiado faciles donde ya sabe, imposibles donde
 * no. El nivel es una propiedad del par (alumno, tema), no del alumno.
 *
 * Efecto secundario util para la tesis: con una ubicacion por semana se obtienen VARIAS
 * mediciones por alumno en vez de una sola, lo que permite observar su trayectoria a lo largo
 * de la intervencion.
 *
 * QUE NO ES: esto no es el "rango" de puntos (ver ProgresoAlumnoService). Aquello mide
 * constancia y sirve para motivar; esto mide competencia y decide la dificultad.
 */
@Getter
@Setter
@Entity
@Table(name = "nivel_semana_alumno",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_nivel_semana_alumno",
           columnNames = {"usuario_id", "semana_id"}),
       indexes = @Index(name = "idx_nivel_semana_usuario", columnList = "usuario_id"))
public class NivelSemanaAlumno {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @ManyToOne(optional = false)
    @JoinColumn(name = "semana_id")
    private Semana semana;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NivelConocimiento nivel;

    /**
     * Proporcion de aciertos por estrato, en JSON: {"Comprender":1.0,"Analizar":0.5,...}
     *
     * Se guarda el detalle y no solo el nivel porque es lo que permite calcular despues el
     * COEFICIENTE DE REPRODUCIBILIDAD de Guttman: hace falta saber quien rompio la escalera
     * (fallo lo facil y acerto lo dificil), no solo donde acabo cada uno.
     */
    @Column(name = "desempeno_json", columnDefinition = "TEXT")
    private String desempenoJson;

    /** Texto legible del criterio aplicado, para poder auditar la decision. */
    @Column(columnDefinition = "TEXT")
    private String justificacion;

    /** Cuantas preguntas tuvo la prueba. Con pocas, la ubicacion es menos fiable. */
    @Column(name = "total_reactivos")
    private int totalReactivos;

    @Column(nullable = false)
    private LocalDateTime fecha;

    @PrePersist
    void alGuardar() {
        if (fecha == null) {
            fecha = LocalDateTime.now();
        }
    }
}
