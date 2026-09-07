package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Una respuesta de alumno seleccionada para que un docente la califique a ciegas.
 *
 * Es una FOTOGRAFÍA, no una referencia viva: se copian aquí el enunciado, la respuesta y la
 * nota que puso la IA. El motivo es de método — si se leyeran de las tablas originales,
 * cualquier reprocesamiento o borrado posterior cambiaría los datos sobre los que ya
 * calificó un docente, y la medida de concordancia dejaría de ser reproducible. Un resultado
 * que se cita en una tesis tiene que poder recalcularse igual dentro de un año.
 */
@Getter
@Setter
@Entity
@Table(name = "muestra_validacion", indexes = {
    @Index(name = "idx_muestra_origen", columnList = "origen")
})
public class MuestraValidacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT")
    private String pregunta;

    @Column(name = "respuesta_alumno", columnDefinition = "TEXT")
    private String respuestaAlumno;

    /** Criterio esperado, si se conocía. Se le muestra al docente igual que a la IA. */
    @Column(name = "respuesta_correcta", columnDefinition = "TEXT")
    private String respuestaCorrecta;

    /**
     * La nota que puso el juez de IA. NUNCA se envía al docente antes de que califique:
     * verla anclaría su juicio y la concordancia medida sería artificialmente alta.
     */
    @Column(name = "puntuacion_ia", nullable = false)
    private int puntuacionIa;

    @Column(name = "escala_min", nullable = false)
    private int escalaMin;

    @Column(name = "escala_max", nullable = false)
    private int escalaMax;

    /** PRACTICA (respuesta abierta de un examen) o ARIA (turno de tutoría). */
    @Column(nullable = false, length = 20)
    private String origen;

    /** Id en la tabla de procedencia, para poder rastrear de dónde salió cada caso. */
    @Column(name = "origen_id")
    private Long origenId;

    @Column(name = "fecha_creacion", nullable = false)
    private LocalDateTime fechaCreacion;

    @PrePersist
    void alGuardar() {
        if (fechaCreacion == null) {
            fechaCreacion = LocalDateTime.now();
        }
    }
}
