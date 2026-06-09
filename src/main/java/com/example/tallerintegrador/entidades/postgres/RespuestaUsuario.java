package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Setter@Getter
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

    @ManyToOne
    @JoinColumn(name = "intento_id")
    private Intento intento;
}
