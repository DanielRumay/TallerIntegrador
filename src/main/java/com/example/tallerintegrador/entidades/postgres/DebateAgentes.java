package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Traza persistente de una deliberación del comité de agentes.
 *
 * El informe técnico afirma que la transcripción se almacena en PostgreSQL; esta entidad
 * es lo que hace cierta esa afirmación. Además guarda la separación entre lo que el comité
 * PROPUSO y lo que el sistema finalmente APLICÓ, que es lo que permite auditar el veto.
 */
@Getter
@Setter
@Entity
@Table(name = "debate_agentes", indexes = {
        @Index(name = "idx_debate_usuario", columnList = "usuario_id"),
        @Index(name = "idx_debate_fecha",   columnList = "fecha")
})
public class DebateAgentes {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @ManyToOne
    @JoinColumn(name = "intento_id")
    private Intento intento;

    private LocalDateTime fecha;

    /** Nivel que tenía el alumno antes de la deliberación. */
    @Enumerated(EnumType.STRING)
    @Column(name = "nivel_anterior", length = 20)
    private NivelConocimiento nivelAnterior;

    /** Nivel que propuso el comité (puede diferir del aplicado si hubo veto). */
    @Enumerated(EnumType.STRING)
    @Column(name = "nivel_propuesto", length = 20)
    private NivelConocimiento nivelPropuesto;

    /** Nivel efectivamente aplicado al perfil del alumno. */
    @Enumerated(EnumType.STRING)
    @Column(name = "nivel_aplicado", length = 20)
    private NivelConocimiento nivelAplicado;

    @Column(name = "veto_aplicado", nullable = false)
    private boolean vetoAplicado = false;

    @Column(name = "motivo_veto", columnDefinition = "TEXT")
    private String motivoVeto;

    @Column(name = "debate_transcripcion", columnDefinition = "TEXT")
    private String debateTranscripcion;

    /**
     * Las 4 Posturas estructuradas (agente, nivelPropuesto, confianza, evidencia[], mensaje)
     * serializadas como JSON. debateTranscripcion sigue siendo el texto legible para
     * mostrar al usuario; esto es la traza de evidencia detrás de cada turno, para que el
     * criterio del comité sea auditable turno por turno, no solo como un párrafo de prosa.
     */
    @Column(name = "posturas_json", columnDefinition = "TEXT")
    private String posturasJson;

    @Column(name = "conceptos_a_reforzar", columnDefinition = "TEXT")
    private String conceptosAReforzar;

    @Column(columnDefinition = "TEXT")
    private String recomendaciones;

    /** true si el Coordinador cayó en el fallback local por fallo del LLM. */
    @Column(name = "uso_fallback", nullable = false)
    private boolean usoFallback = false;

    @Column(name = "latencia_total_ms")
    private Long latenciaTotalMs;
}
