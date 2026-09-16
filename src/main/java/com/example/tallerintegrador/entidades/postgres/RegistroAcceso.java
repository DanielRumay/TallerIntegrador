package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Un inicio de sesión correcto.
 *
 * POR QUÉ EXISTE. El consentimiento que acepta el alumno promete "monitoreo de actividad y
 * frecuencia de uso del sistema", pero hasta ahora el login solo contaba intentos FALLIDOS:
 * no quedaba constancia de cuándo entraba nadie. Para un estudio de cuatro semanas la
 * adherencia —cuántos días usa la plataforma cada alumno— es una variable central, y no se
 * podía reconstruir: los intentos solo existen si el alumno llegó a hacer una evaluación, así
 * que quien entraba a leer el material y se iba era invisible.
 *
 * LÍMITE CONOCIDO. El token dura 24 horas: si el alumno vuelve el mismo día sin cerrar sesión,
 * esa segunda visita no genera fila. Mide días de uso, no visitas.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "registro_acceso", indexes = {
        @Index(name = "idx_registro_acceso_usuario_fecha", columnList = "usuario_id, fecha")
})
public class RegistroAcceso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(nullable = false)
    private LocalDateTime fecha;

    /** Rol en el momento del acceso, para separar alumnos de docentes al analizar. */
    @Column(length = 20)
    private String rol;

    public RegistroAcceso(Usuario usuario, String rol) {
        this.usuario = usuario;
        this.rol = rol;
        this.fecha = LocalDateTime.now();
    }
}
