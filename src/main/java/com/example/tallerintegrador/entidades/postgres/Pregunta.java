package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Setter
@Getter
@Entity
@Table(name = "pregunta")
public class Pregunta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(columnDefinition = "TEXT")
    private String Pregunta;

    private Tipo tipodepregunta;

    @ManyToOne
    @JoinColumn(name = "semana_id")
    private Semana semana;

    @Enumerated(EnumType.STRING)
    @Column(name = "nivel_dificultad")
    private NivelDificultad nivelDificultad;

    /**
     * Nivel de la Taxonomía de Bloom que el generador declaró para este reactivo
     * (Recordar/Comprender/Aplicar/Analizar/Evaluar/Crear). El generador ya lo produce en
     * evaluacion_bloom.nivel_bloom, pero hasta ahora se descartaba al guardar la pregunta —
     * por eso el mapa de calor solo podía agregar por curso/semana, nunca por concepto ni
     * por nivel cognitivo.
     */
    @Column(name = "nivel_bloom", length = 20)
    private String nivelBloom;

    /** Conceptos que cubre el reactivo, separados por coma (ej. "fotosíntesis, cloroplasto"). */
    @Column(columnDefinition = "TEXT")
    private String conceptos;

    /**
     * Respuesta correcta o criterio esperado, tal como se usó para calificar al alumno.
     *
     * Hasta ahora no se persistía en ningún sitio: el frontend la tenía al momento de
     * evaluar (`respuesta_correcta` / la rúbrica de la pregunta abierta) y la descartaba al
     * guardar. El efecto era que el historial podía decirle al alumno "Incorrecto" pero no
     * qué era lo correcto, que es justo lo que sirve para estudiar. Nullable a propósito:
     * los intentos guardados antes de este cambio no la tienen.
     */
    @Column(name = "respuesta_correcta", columnDefinition = "TEXT")
    private String respuestaCorrecta;

    /**
     * Alternativas de la pregunta. LAZY con @BatchSize, NO EAGER.
     *
     * POR QUÉ. Con EAGER, cada vez que se cargaba una Pregunta —aunque solo se quisiera contar
     * las de una semana o filtrarlas por dificultad— Hibernate lanzaba una consulta más para
     * traer sus alternativas. Abrir una semana o pedir la evaluación adaptativa producía
     * decenas de `select ... from respuesta where pregunta_id=?` seguidos en el log.
     *
     * Con LAZY solo se piden cuando alguien las lee de verdad (hoy: el banco de preguntas del
     * docente y las preguntas estáticas de la evaluación adaptativa, ambos dentro de una
     * transacción), y @BatchSize las trae de 100 en 100 en una sola consulta en vez de una por
     * pregunta.
     */
    @BatchSize(size = 100)
    @OneToMany(mappedBy = "pregunta", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Respuesta> respuestas;
}
