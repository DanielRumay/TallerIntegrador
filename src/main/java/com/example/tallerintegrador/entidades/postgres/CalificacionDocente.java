package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * La nota que un docente puso a una respuesta de la muestra, sin haber visto la de la IA.
 *
 * La restricción de unicidad (muestra, docente) no es cosmética: si un docente pudiera
 * calificar dos veces el mismo caso, ese caso pesaría el doble en el cálculo de kappa y el
 * resultado quedaría sesgado hacia su criterio.
 */
@Getter
@Setter
@Entity
@Table(name = "calificacion_docente",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_calificacion_muestra_docente",
           columnNames = {"muestra_id", "docente_id"}),
       indexes = @Index(name = "idx_calificacion_docente", columnList = "docente_id"))
public class CalificacionDocente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "muestra_id")
    private MuestraValidacion muestra;

    @ManyToOne(optional = false)
    @JoinColumn(name = "docente_id")
    private Usuario docente;

    @Column(nullable = false)
    private int puntuacion;

    /** Opcional: por qué calificó así. Sirve para revisar los desacuerdos uno a uno. */
    @Column(columnDefinition = "TEXT")
    private String comentario;

    @Column(nullable = false)
    private LocalDateTime fecha;

    @PrePersist
    void alGuardar() {
        if (fecha == null) {
            fecha = LocalDateTime.now();
        }
    }
}
