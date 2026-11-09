package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Constancia permanente de que una charla empezó y de si terminó. La ronda custodiada se borra a
 * los pocos días y el historial solo recibe las charlas cerradas: sin esto, las abandonadas no
 * dejarían rastro. No guarda preguntas ni respuestas.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "sesion_charla", indexes = {
        @Index(name = "idx_sesion_charla_alumno", columnList = "usuario_id, semana_id"),
        @Index(name = "idx_sesion_charla_iniciada", columnList = "iniciada")
})
public class SesionCharla {

    @Id
    @Column(name = "ronda_id", length = 36)
    private String rondaId;

    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;

    @Column(name = "semana_id", nullable = false)
    private Long semanaId;

    private boolean desafio;

    private boolean primaria;

    @Column(name = "total_preguntas")
    private int totalPreguntas;

    private int respondidas;

    @Column(nullable = false)
    private LocalDateTime iniciada;

    private LocalDateTime terminada;
}
