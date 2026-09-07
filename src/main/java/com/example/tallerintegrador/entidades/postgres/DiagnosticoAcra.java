package com.example.tallerintegrador.entidades.postgres;
import com.example.tallerintegrador.service.academico.IntentoService;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Perfil de estrategias de aprendizaje ACRA de un alumno.
 *
 * Vive en su propia tabla, separado de `intento`, y la razón es de fondo, no de orden:
 * el ACRA NO es un examen. Es un cuestionario de autorreporte (Román y Gallego, 1994)
 * donde el alumno declara con qué frecuencia usa ciertas técnicas de estudio — "subrayo
 * lo importante", "repito mentalmente para memorizar". No hay respuestas correctas ni
 * incorrectas, así que no existe una "nota" que calcular.
 *
 * Guardarlo como un Intento obligaba a inventarle una nota (se guardaba 0.0) y a marcarlo
 * con tipoEvaluacion=DIAGNOSTICA para que cada consulta se acordara de excluirlo. Bastaba
 * con que UNA consulta lo olvidara para que el promedio académico del alumno se hundiera:
 * eso es exactamente lo que pasaba en el dashboard y el historial, donde
 * IntentoService no filtraba nada y un 0.0 ficticio arrastraba la media.
 *
 * Con una tabla aparte, el error deja de ser posible por construcción: ninguna consulta de
 * notas puede toparse con un ACRA porque el ACRA ya no vive ahí.
 */
@Getter
@Setter
@Entity
@Table(name = "diagnostico_acra", indexes = {
        @Index(name = "idx_acra_usuario", columnList = "usuario_id"),
        @Index(name = "idx_acra_fecha",   columnList = "fecha")
})
public class DiagnosticoAcra {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    private LocalDateTime fecha;

    /** Escala I — Adquisición de información (0–20). */
    @Column(name = "escala_adquisicion")
    private Integer escalaAdquisicion;

    /** Escala II — Codificación de información (0–20). */
    @Column(name = "escala_codificacion")
    private Integer escalaCodificacion;

    /** Escala III — Recuperación de información (0–20). */
    @Column(name = "escala_recuperacion")
    private Integer escalaRecuperacion;

    /** Escala IV — Apoyo al procesamiento (0–20). */
    @Column(name = "escala_apoyo")
    private Integer escalaApoyo;

    /** Suma de las cuatro escalas (0–80). NO es una nota: es un puntaje de perfil. */
    @Column(name = "puntaje_total")
    private Integer puntajeTotal;

    /**
     * Nivel sugerido por el puntaje ACRA. Se conserva por trazabilidad de lo que el
     * instrumento propuso, pero ver la nota de validez de constructo en el informe: el
     * ACRA mide CÓMO estudia el alumno, no CUÁNTO sabe de Lenguaje.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "nivel_sugerido", length = 20)
    private NivelConocimiento nivelSugerido;

    /** Respuestas Likert crudas (1–4) en orden de ítem, serializadas, para reanálisis. */
    @Column(name = "respuestas_json", columnDefinition = "TEXT")
    private String respuestasJson;

    @Column(name = "tiempo_empleado_segundos")
    private Integer tiempoEmpleadoSegundos;
}
