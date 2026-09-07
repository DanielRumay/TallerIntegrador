package com.example.tallerintegrador.entidades.postgres;

/**
 * Para qué sirve una evaluación dentro del recorrido del alumno.
 *
 * Se persiste como TEXTO (`@Enumerated(EnumType.STRING)`), así que añadir un valor no obliga
 * a migrar nada; lo que sí importa es que coincida con lo que envía el frontend, porque
 * Jackson rechaza la petición ENTERA si recibe un nombre que no existe aquí — y devuelve un
 * 400 antes de llegar al controlador, con el cuerpo vacío. Fue exactamente lo que pasó con
 * UBICACION: la pantalla mandaba ese valor y aquí no existía.
 */
public enum TipoEvaluacion {

    /** Diagnóstico inicial del alumno (ACRA y perfil de entrada). */
    DIAGNOSTICA,

    /** Práctica normal de una semana: genera nota e intento. */
    FORMATIVA,

    /** Repaso dirigido a lo que falló. */
    REFUERZO,

    /**
     * Prueba de ubicación de una semana.
     *
     * NO es un examen: sitúa al alumno en un nivel de Bloom para esa semana y por eso no
     * produce nota ni intento — enviarla por la ruta normal le hundiría el promedio con un
     * cero antes de haber estudiado. Tiene su propio endpoint (`POST /adaptive/ubicacion`).
     */
    UBICACION
}
