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

    // Trayectoria en la charla con Aria. Todo nullable: los demás modos y los registros
    // anteriores no la tienen. `correcta` sigue siendo solo el primer intento.
    private Integer repreguntas;

    /** Alternativas elegidas antes de la respuesta final, una por línea. */
    @Column(name = "opciones_falladas", columnDefinition = "TEXT")
    private String opcionesFalladas;

    /** Ayudas usadas, separadas por coma: PISTA, CONGELAR, MITAD, TIEMPO. */
    @Column(length = 60)
    private String ayudas;

    @Column(name = "a_tiempo")
    private Boolean aTiempo;

    @Column(name = "es_repaso")
    private Boolean esRepaso;

    private Double puntaje;

    /** Si terminó acertando, aunque haya necesitado repreguntas. */
    @Column(name = "acierto_final")
    private Boolean aciertoFinal;
}
