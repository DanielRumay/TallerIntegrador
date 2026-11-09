package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Libro de movimientos de monedas: premios únicos (podio, metas) y gastos (tienda).
 *
 * Las monedas que vienen de practicar NO se guardan aquí: se calculan desde los intentos, igual
 * que los puntos (ver MonedasService). Aquí va solo lo que no se puede recalcular: un gasto o un
 * premio que depende de un momento concreto.
 *
 * `clave` hace idempotente un premio: "podio:3:2026-10-26" solo puede cobrarse una vez por
 * alumno, aunque la comprobación se repita en cada visita. La restricción única lo garantiza
 * también si dos peticiones llegan a la vez.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "movimiento_monedas",
        uniqueConstraints = @UniqueConstraint(name = "uk_movimiento_monedas_clave", columnNames = {"usuario_id", "clave"}),
        indexes = @Index(name = "idx_movimiento_monedas_usuario", columnList = "usuario_id"))
public class MovimientoMonedas {

    public enum Tipo { PREMIO, GASTO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;

    /** Positiva en premios, negativa en gastos. */
    @Column(nullable = false)
    private int cantidad;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Tipo tipo;

    @Column(nullable = false, length = 120)
    private String motivo;

    @Column(length = 80)
    private String clave;

    @Column(nullable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    public MovimientoMonedas(Long usuarioId, int cantidad, Tipo tipo, String motivo, String clave) {
        this.usuarioId = usuarioId;
        this.cantidad = cantidad;
        this.tipo = tipo;
        this.motivo = motivo;
        this.clave = clave;
    }
}
