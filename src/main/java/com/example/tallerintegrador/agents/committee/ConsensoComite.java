package com.example.tallerintegrador.agents.committee;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/**
 * Decisión terminal del Coordinador tras leer las posturas de sus colegas. A diferencia de
 * Postura (que usan los tres agentes deliberantes), esta lleva además las recomendaciones
 * pedagógicas concretas que el frontend usa para desbloquear contenido.
 */
public record ConsensoComite(

        @Description("1 a 2 oraciones de cierre del Coordinador, citando en qué coincidieron " +
                "o discreparon sus colegas y por qué decide lo que decide")
        String mensaje,

        @Description("Nivel final propuesto para el alumno: PRINCIPIANTE, INTERMEDIO o AVANZADO")
        String nivelPropuesto,

        @Description("Qué tan respaldada por evidencia consistente está esta decisión, de 0.0 a 1.0. " +
                "Si las posturas de los colegas se contradicen entre sí, la confianza debe ser baja.")
        double confianza,

        @Description("Conceptos concretos que el alumno debe reforzar, separados por coma")
        String conceptosAReforzar,

        @Description("2 a 3 recomendaciones de estudio en lenguaje natural, dirigidas AL ALUMNO. " +
                "Explica POR QUÉ le conviene cada una según lo que falló, no solo qué hacer. " +
                "No hace falta que menciones el nombre técnico del formato: para eso está el " +
                "campo modosRecomendados.")
        List<String> recomendaciones,

        @Description("Los formatos de evaluación que le convienen, como CÓDIGOS exactos de esta " +
                "lista y nada más: AVATAR, VIDEO, OPCION_MULTIPLE, VERDADERO_FALSO, ABIERTA, " +
                "DETECCION_ERRORES, VISUAL_QUIZ. De 1 a 3, del más al menos prioritario. " +
                "Cualquier valor fuera de esa lista se descarta.")
        List<String> modosRecomendados
) {

    /*
       Dos campos y no uno, a propósito.

       `recomendaciones` es prosa para el alumno: le dice por qué le conviene algo. Los
       `modosRecomendados` son códigos para la aplicación: encienden los botones.

       Antes solo existía la prosa, y la interfaz la escaneaba buscando palabras sueltas para
       adivinar qué formato encender. Eso hacía que la recomendación dependiera de que el
       modelo eligiera el vocabulario correcto: "que escriba un ensayo" no activaba ABIERTA,
       porque el buscador esperaba "redacc". Separando ambos, la decisión es un dato
       verificable y el alumno sigue recibiendo la explicación.
    */
}
