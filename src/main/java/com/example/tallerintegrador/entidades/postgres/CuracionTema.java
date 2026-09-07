package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Decision de un docente sobre un tema extraido automaticamente de un material.
 *
 * POR QUE EXISTE. La extraccion de subtemas se hace sobre secciones del documento, y una
 * seccion puede caer sobre un indice, una pagina de creditos o una bibliografia. El modelo,
 * obligado a responder, inventa temas de ahi. Ninguna heuristica automatica distingue con
 * fiabilidad "Indice de contenidos" de un tema real; un docente lo ve en dos segundos.
 *
 * DOBLE PROPOSITO. Ademas de limpiar lo que ve el alumno, cada decision es un DATO: la
 * proporcion de temas aceptados es una medida de calidad de la extraccion **validada por un
 * experto**, reportable en el informe. Es el mismo patron de docente-en-el-bucle que ya usa
 * la validacion del juez.
 *
 * Se guarda en PostgreSQL y no junto a los subtemas en Mongo precisamente por eso: interesa
 * saber QUIEN decidio y CUANDO, no solo el resultado.
 */
@Getter
@Setter
@Entity
@Table(name = "curacion_tema",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_curacion_material_tema",
           columnNames = {"material_id", "tema_canonico"}),
       indexes = @Index(name = "idx_curacion_material", columnList = "material_id"))
public class CuracionTema {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "material_id")
    private Material material;

    /**
     * Forma canonica del tema (la misma que usa el BKT). Es la clave: si el modelo devuelve
     * "La fotosintesis" en una reingesta y antes se descarto "Fotosintesis", debe seguir
     * descartado. Sin canonizar, el docente tendria que descartarlo otra vez.
     */
    @Column(name = "tema_canonico", nullable = false, length = 200)
    private String temaCanonico;

    /** Texto tal como se le muestra al docente y al alumno. */
    @Column(name = "tema_etiqueta", columnDefinition = "TEXT")
    private String temaEtiqueta;

    /** true = es un tema real y se le muestra al alumno. false = descartado, se oculta. */
    @Column(nullable = false)
    private boolean aceptado;

    /** Opcional: por que se descarto. Util para revisar despues que tipo de basura aparece. */
    @Column(columnDefinition = "TEXT")
    private String motivo;

    @ManyToOne
    @JoinColumn(name = "docente_id")
    private Usuario docente;

    @Column(nullable = false)
    private LocalDateTime fecha;

    @PrePersist
    void alGuardar() {
        if (fecha == null) {
            fecha = LocalDateTime.now();
        }
    }
}
