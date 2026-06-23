package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter@Setter
@Entity
@Table(name = "intento")
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
    private TipoEvaluacion tipoEvaluacion;

    @Column(name = "tecnica")
    private String tecnica;
}