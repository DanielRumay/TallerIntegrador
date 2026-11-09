package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Lote de preguntas en poder del servidor mientras un alumno lo resuelve.
 *
 * El navegador solo recibe enunciados y alternativas, con el id de la ronda. Las respuestas
 * correctas, la calificación de cada pregunta, las ayudas usadas y los tiempos viven aquí, de modo
 * que la nota no depende de nada que el alumno pueda editar desde el navegador.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ronda_custodiada", indexes = {
        @Index(name = "idx_ronda_usuario_semana", columnList = "usuario_id, semana_id"),
        @Index(name = "idx_ronda_creada", columnList = "creada")
})
public class RondaCustodiada {

    public enum Clase { PRACTICA, VIDEO, AVATAR, ADAPTATIVA, UBICACION }

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;

    @Column(name = "semana_id", nullable = false)
    private Long semanaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Clase clase;

    @Column(length = 40)
    private String tipo;

    @Column(name = "nivel_bloom", length = 40)
    private String nivelBloom;

    /** Preguntas completas, con su respuesta y su rúbrica. Nunca salen tal cual hacia el alumno. */
    @Column(name = "preguntas_json", columnDefinition = "text", nullable = false)
    private String preguntasJson;

    /** Por cada pregunta: respuesta, calificación, ayudas y si llegó a tiempo. */
    @Column(name = "estado_json", columnDefinition = "text", nullable = false)
    private String estadoJson;

    /** Solo en el tutor: preguntas de la sesión. Es el denominador de la nota. */
    @Column(name = "total_turnos")
    private Integer totalTurnos;

    @Column(nullable = false)
    private LocalDateTime creada;

    /** Desde aquí se mide cuánto tardó la pregunta en curso. */
    @Column(name = "ultima_marca", nullable = false)
    private LocalDateTime ultimaMarca;

    private LocalDateTime cerrada;

    @Column(name = "nota_final")
    private Double notaFinal;

    private Integer bono;
}
