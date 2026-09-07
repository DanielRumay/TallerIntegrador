package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Registro de telemetría del pipeline de IA.
 *
 * Existe para que las métricas declaradas en el informe (conformidad Bloom, tasa de
 * deduplicación, degradación del RAG, latencia y coste del juez, tasa de veto del comité)
 * sean consultables y exportables como evidencia, en vez de afirmarse sin respaldo.
 */
@Getter
@Setter
@Entity
@Table(name = "evento_metrica_ia", indexes = {
        @Index(name = "idx_evento_tipo",        columnList = "tipo"),
        @Index(name = "idx_evento_fecha",       columnList = "fecha"),
        @Index(name = "idx_evento_tipo_fecha",  columnList = "tipo, fecha")
})
public class EventoMetricaIA {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private TipoEventoIA tipo;

    private LocalDateTime fecha;

    @Column(name = "usuario_id")
    private Long usuarioId;

    /** Resultado categórico del evento. Ej: ACEPTADA, DUPLICADO_VECTORIAL, UMBRAL_0_30, VETO_APLICADO. */
    @Column(length = 60)
    private String etiqueta;

    /** Nivel de Bloom declarado por el generador, cuando aplica. */
    @Column(name = "nivel_bloom", length = 20)
    private String nivelBloom;

    /** Valor numérico asociado: score de similitud, umbral efectivo, nota, etc. */
    private Double valor;

    @Column(name = "latencia_ms")
    private Long latenciaMs;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(length = 60)
    private String modelo;

    @Column(columnDefinition = "TEXT")
    private String detalle;

    public static EventoMetricaIA de(TipoEventoIA tipo, String etiqueta) {
        EventoMetricaIA e = new EventoMetricaIA();
        e.setTipo(tipo);
        e.setEtiqueta(etiqueta);
        e.setFecha(LocalDateTime.now());
        return e;
    }
}
