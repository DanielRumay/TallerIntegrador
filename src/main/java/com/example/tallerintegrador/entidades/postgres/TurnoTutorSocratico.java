package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Un turno cerrado de tutoría con Aria.
 *
 * Existe por una razón concreta: el andamiaje socrático (cuántos peldaños de ayuda necesitó
 * el alumno antes de resolver la pregunta) se calculaba en cada sesión y se perdía al
 * cerrarla. Guardado, deja de ser una característica de la interfaz y pasa a ser una
 * variable de proceso medible: si un alumno pasa de necesitar el escalón 3 a resolver en el
 * 1 sobre los mismos conceptos, eso es evidencia de aprendizaje, y es lo que permite
 * replicar el diseño experimental de Computers & Education 241:105494 (2025) — agente
 * socrático frente a agente no socrático.
 */
@Getter
@Setter
@Entity
@Table(name = "turno_tutor_socratico", indexes = {
    @Index(name = "idx_turno_tutor_usuario", columnList = "usuario_id"),
    @Index(name = "idx_turno_tutor_fecha", columnList = "fecha")
})
public class TurnoTutorSocratico {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    /** Tema declarado de la sesión, tal como lo envía la vista del tutor. */
    @Column(columnDefinition = "TEXT")
    private String tema;

    @Column(columnDefinition = "TEXT")
    private String pregunta;

    @Column(columnDefinition = "TEXT")
    private String respuestaEstudiante;

    /**
     * Peldaños de andamiaje consumidos para cerrar esta pregunta: 1 si el alumno la resolvió
     * al primer intento, 2 si necesitó una repregunta, 3 si hubo que explicársela.
     * Es la variable de proceso central de este registro.
     */
    @Column(name = "escalon_consumido", nullable = false)
    private int escalonConsumido;

    /** Puntuación final del turno (1 a 4). Nula si el turno no llegó a cerrarse. */
    @Column(name = "puntuacion")
    private Integer puntuacion;

    /** frustrado | inseguro | neutral | confiado, según lo detectado por el modelo. */
    @Column(length = 20)
    private String sentimiento;

    /** true cuando el turno se cerró (ACCION=AVANZAR); false si quedó en una repregunta. */
    @Column(name = "cerrado", nullable = false)
    private boolean cerrado;

    /** Modalidad de la respuesta: TEXTO o AUDIO. Permite comparar ambas rutas. */
    @Column(length = 10)
    private String modalidad;

    @Column(nullable = false)
    private LocalDateTime fecha;

    @PrePersist
    void alGuardar() {
        if (fecha == null) {
            fecha = LocalDateTime.now();
        }
    }
}
