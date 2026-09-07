package com.example.tallerintegrador.entidades.postgres;
import com.example.tallerintegrador.service.academico.IntentoService;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "intento", indexes = {
    @Index(name = "idx_intento_usuario_id", columnList = "usuario_id"),
    @Index(name = "idx_intento_fecha", columnList = "fecha"),
    @Index(name = "idx_intento_usuario_fecha", columnList = "usuario_id, fecha")
})
public class Intento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Double nota;

    private LocalDateTime fecha;

    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @ManyToOne
    @JoinColumn(name = "semana_id")
    private Semana semana;

    @Column(name = "tiempo_empleado_segundos")
    private Integer tiempoEmpleadoSegundos;

    @Column(name = "numero_intentos")
    private Integer numeroIntentos;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoEvaluacion tipoEvaluacion;

    /**
     * Red de seguridad: si algún camino de código olvida asignar el tipo, se asume
     * FORMATIVA en vez de guardar un NULL.
     *
     * El motivo es concreto: durante meses, IntentoService no asignaba este campo y todos
     * los intentos de práctica normal quedaron con tipo_evaluacion NULL. Las consultas
     * seguían funcionando solo porque el filtro estaba escrito de forma defensiva
     * (`== null || != DIAGNOSTICA`); cualquier consulta que preguntara por FORMATIVA de
     * forma directa no habría devuelto nada. Con esto, el dato inválido deja de poder
     * llegar a la tabla, y la columna puede declararse NOT NULL sin riesgo de romper
     * un guardado.
     */
    @PrePersist
    void asignarTipoPorDefecto() {
        if (tipoEvaluacion == null) {
            tipoEvaluacion = TipoEvaluacion.FORMATIVA;
        }
    }

    @Column(name = "tecnica")
    private String tecnica;
}