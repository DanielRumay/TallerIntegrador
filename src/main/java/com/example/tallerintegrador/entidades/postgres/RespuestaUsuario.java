package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "respuesta_usuario")
public class RespuestaUsuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @ManyToOne
    @JoinColumn(name = "pregunta_id")
    private Pregunta pregunta;

    @ManyToOne
    @JoinColumn(name = "respuesta_id")
    private Respuesta respuestaSeleccionada;

    private boolean correcta;

    // Nueva variable de fecha
    private LocalDateTime fechaCreacion;

    @Column(columnDefinition = "TEXT")
    private String respuestaTexto;

    /**
     * Explicación que el agente juez dio al calificar esta respuesta concreta. El alumno ya
     * la veía en pantalla durante el examen, pero se perdía al cerrar: no quedaba en el
     * historial ni en ninguna descarga. Nullable por los intentos anteriores a este cambio.
     */
    @Column(columnDefinition = "TEXT")
    private String retroalimentacion;

    @ManyToOne
    @JoinColumn(name = "intento_id")
    private Intento intento;
}
