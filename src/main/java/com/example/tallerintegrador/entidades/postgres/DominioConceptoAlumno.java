package com.example.tallerintegrador.entidades.postgres;
import com.example.tallerintegrador.service.analitica.ConocimientoBktService;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Probabilidad de dominio de un alumno sobre un concepto, en un nivel de Bloom concreto.
 *
 * Reemplaza al "% de aciertos por curso/semana" del mapa de calor original: aquí un 60%
 * sobre 5 preguntas y un 60% sobre 40 dejan de pintarse igual, porque el valor no es un
 * promedio sino la salida de un modelo de Bayesian Knowledge Tracing (Corbett & Anderson,
 * 1995) que se actualiza con cada respuesta — ver ConocimientoBktService.
 */
@Getter
@Setter
@Entity
@Table(name = "dominio_concepto_alumno", indexes = {
        @Index(name = "idx_dominio_usuario", columnList = "usuario_id"),
        @Index(name = "idx_dominio_usuario_concepto_bloom", columnList = "usuario_id, concepto, nivel_bloom", unique = true)
})
public class DominioConceptoAlumno {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    /**
     * A qué curso pertenece este concepto. Se guarda de forma desnormalizada (en vez de
     * derivarlo cada vez desde Pregunta→Semana→Curso) porque es exactamente el eje por el
     * que el mapa de conocimiento del frontend necesita agrupar: un alumno quiere ver sus
     * puntos débiles organizados por curso, no una lista plana de conceptos sueltos.
     */
    @ManyToOne
    @JoinColumn(name = "curso_id")
    private Curso curso;

    @Column(length = 200)
    private String concepto;

    /**
     * Nombre tal como se le muestra al alumno, con tildes y mayuscula inicial.
     *
     * `concepto` guarda la clave canonica (minusculas, sin tildes, sin articulo) porque es
     * la que agrupa; esta columna guarda la version legible porque "fotosintesis" en un
     * informe se lee como un error de ortografia, no como una clave tecnica.
     */
    @Column(name = "concepto_etiqueta")
    private String conceptoEtiqueta;

    /**
     * Ultima semana en la que se practico este concepto.
     *
     * IMPORTANTE: es METADATO, no forma parte de la clave de busqueda. Si la semana entrara
     * en la clave, el mismo concepto practicado en dos semanas abriria dos filas y volveria
     * a fragmentarse la evidencia del alumno: exactamente el defecto que se corrigio con la
     * canonizacion. Sirve solo para poder situar el concepto en el eje temporal del mapa.
     */
    @ManyToOne
    @JoinColumn(name = "semana_id")
    private Semana semana;

    @Column(name = "nivel_bloom", length = 20)
    private String nivelBloom;

    @Column(name = "probabilidad_dominio")
    private double probabilidadDominio;

    /** Cuántas respuestas alimentaron esta estimación. Un valor bajo pesa menos en el mapa. */
    private int observaciones;

    @Column(name = "fecha_actualizacion")
    private LocalDateTime fechaActualizacion;
}
