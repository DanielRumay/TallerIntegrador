package com.example.tallerintegrador.service;

import org.springframework.stereotype.Service;

@Service
public class PromptTemplateService {

    public static final String OPCION_MULTIPLE = "OPCION_MULTIPLE";
    public static final String VERDADERO_FALSO = "VERDADERO_FALSO";
    public static final String ABIERTA         = "ABIERTA";

    public static final String FEW_SHOT          = "FEW_SHOT";
    public static final String CHAIN_OF_THOUGHT  = "CHAIN_OF_THOUGHT";
    public static final String STRUCTURED_OUTPUT = "STRUCTURED_OUTPUT";

    private static final String SYSTEM_PROMPT = """
        Actúa como un Sistema Inteligente de Evaluación Educativa. Tu objetivo es generar reactivos (preguntas) de 
        comprensión lectora basándote estrictamente en la Dimensión del Proceso Cognitivo de la Taxonomía Revisada 
        de Bloom (Anderson y Krathwohl, 2001).
        
        Para cada texto proporcionado, deberás estructurar la evaluación en los siguientes tres niveles:
        - Nivel Literal (Recordar / Remember): Formula preguntas que requieran que el estudiante recupere conocimiento 
          relevante directamente del texto. Utiliza verbos de acción como reconocer, identificar y localizar.
        - Nivel Inferencial (Comprender y Analizar / Understand and Analyze): Formula preguntas donde el estudiante 
          deba determinar el significado, integrar ideas o deducir información implícita. Utiliza verbos como inferir, 
          clasificar, comparar, explicar y diferenciar.
        - Nivel Crítico (Evaluar y Crear / Evaluate and Create): Formula preguntas de orden superior donde el 
          estudiante deba emitir juicios de valor basados en criterios o justificar una postura. Utiliza verbos como 
          criticar, comprobar, argumentar o generar una hipótesis.
        """;

    // EL ESQUEMA UNIVERSAL (Para ahorrar peticiones)
    private static final String UNIVERSAL_SCHEMA = """
        {
          "preguntas": [
            {
              "enunciado": "texto de la pregunta",
              "opciones_o_respuesta": "opciones si es múltiple, V/F o rúbrica si es abierta",
              "justificacion_pregunta": "por qué es correcta"
            }
          ],
          "evaluacion_bloom": {
              "nivel_bloom": "Recordar|Comprender|Aplicar|Analizar|Evaluar|Crear",
              "nivel_bloom_orden": 1,
              "es_hots": true,
              "puntaje_calidad": 4.0,
              "justificacion_evaluacion": "por qué este nivel y puntaje según la Taxonomía Revisada"
          }
        }
        """;

    public String build(String tecnica, String tipoPregunta, String nivelBloom, String texto, int cantidad) {
        return switch (tecnica) {
            case FEW_SHOT          -> fewShot(tipoPregunta, nivelBloom, texto, cantidad);
            case CHAIN_OF_THOUGHT  -> chainOfThought(tipoPregunta, nivelBloom, texto, cantidad);
            case STRUCTURED_OUTPUT -> structuredOutput(tipoPregunta, nivelBloom, texto, cantidad);
            default -> throw new IllegalArgumentException("Técnica no válida: " + tecnica);
        };
    }

    //TÉCNICA 1: FEW-SHOT
    private String fewShot(String tipo, String bloom, String texto, int cantidad) {
        String bloomLinea = bloom != null
                ? "Nivel cognitivo objetivo (Taxonomía Revisada de Bloom): " + bloom
                : "Apunta a niveles de orden superior: Analizar, Evaluar o Crear.";

        String ejemplos = obtenerEjemplosPorTipo(tipo);

        return """
            %s
            %s
            
            EJEMPLOS DE ALTA CALIDAD PARA REFERENCIA:
            %s
            
            Usando estos ejemplos como modelo, genera %d pregunta(s) de tipo %s a partir del texto.
            
            CRÍTICO: Al finalizar, DEBES responder ÚNICAMENTE con un JSON válido siguiendo este esquema exacto, 
            el cual incluye tu propia autoevaluación:
            %s
            
            TEXTO:
            %s
            """.formatted(SYSTEM_PROMPT, bloomLinea, ejemplos, cantidad, tipo, UNIVERSAL_SCHEMA, texto);
    }

    //TÉCNICA 2: CHAIN-OF-THOUGHT
    private String chainOfThought(String tipo, String bloom, String texto, int cantidad) {
        String bloomLinea = bloom != null
                ? "Nivel Bloom objetivo: " + bloom
                : "Apunta al nivel más alto posible (Analizar/Evaluar/Crear).";

        return """
            %s
            %s
            
            Tu tarea: genera %d pregunta(s) de tipo %s.
            
            Antes de generar el JSON final, razona en voz alta siguiendo estos pasos:
            PASO 1 — IDENTIFICAR CONCEPTOS CLAVE: Lista los 3 a 5 conceptos más importantes del texto.
            PASO 2 — SELECCIÓN COGNITIVA: Decide qué nivel de Bloom evaluar priorizando el orden superior.
            PASO 3 — DISEÑO: Formula la pregunta sin que sea de copia literal.
            PASO 4 — AUTOCRÍTICA: Revisa si es ambigua o evalúa realmente el nivel elegido.
            
            PASO 5 — PRESENTACIÓN FINAL: Debes obligatoriamente encerrar el resultado final en un bloque de 
            código JSON siguiendo estrictamente este esquema:
            ```json
            %s
            ```
            
            TEXTO:
            %s
            """.formatted(SYSTEM_PROMPT, bloomLinea, cantidad, tipo, UNIVERSAL_SCHEMA, texto);
    }

    // TÉCNICA 3: STRUCTURED OUTPUT
    private String structuredOutput(String tipo, String bloom, String texto, int cantidad) {
        String bloomLinea = bloom != null
                ? "Nivel Bloom objetivo: " + bloom
                : "Apunta a niveles de orden superior (nivel_bloom_orden >= 3).";

        return """
            %s
            %s
            
            Genera exactamente %d pregunta(s) de tipo %s a partir del texto.
            
            REGLAS ESTRICTAS:
            - Responde ÚNICAMENTE con el JSON. Sin texto extra, sin markdown, sin ```.
            - El JSON debe ser válido y seguir exactamente este esquema, evaluando tu propio trabajo:
            %s
            
            TEXTO:
            %s
            """.formatted(SYSTEM_PROMPT, bloomLinea, cantidad, tipo, UNIVERSAL_SCHEMA, texto);
    }

    //EJEMPLOS DE FEW-SHOT
    private String obtenerEjemplosPorTipo(String tipo) {
        return switch (tipo) {
            case OPCION_MULTIPLE -> """
                [EJEMPLO 1 — Nivel: Analizar]
                Pregunta: ¿Cuál diferencia entre mitosis y meiosis explica mejor la variabilidad genética?
                A) La mitosis ocurre en células somáticas
                B) La meiosis produce recombinación genética y dos divisiones ← CORRECTA
                C) La mitosis produce 4 células hijas
                D) La meiosis requiere más energía
                Justificación: Solo B identifica el mecanismo real de variabilidad.

                [EJEMPLO 2 — Nivel: Evaluar]
                Pregunta: ¿Qué limitación tiene el argumento "subir impuestos siempre reduce el consumo"?
                A) Ignora el tipo de bien y la elasticidad de demanda ← CORRECTA
                B) Es correcto en todos los casos
                C) Solo aplica a bienes de lujo
                D) No considera el ingreso del consumidor
                Justificación: Una afirmación absoluta ignora variables contextuales clave.
                """;
            case VERDADERO_FALSO -> """
                [EJEMPLO 1 — Nivel: Analizar]
                Afirmación: "La fotosíntesis y la respiración celular son opuestas porque una consume CO₂ y la otra lo produce, pero ambas ocurren en la mitocondria."
                Respuesta: FALSO
                Justificación: La fotosíntesis ocurre en cloroplastos, no mitocondrias. Mezcla un hecho correcto con uno incorrecto.

                [EJEMPLO 2 — Nivel: Evaluar]
                Afirmación: "El método científico garantiza la verdad absoluta de una hipótesis si el experimento se repite suficientes veces."
                Respuesta: FALSO
                Justificación: El método científico solo puede falsificar hipótesis (Popper, 1959), no probar verdades absolutas.
                """;
            case ABIERTA -> """
                [EJEMPLO 1 — Nivel: Evaluar]
                "Considerando los efectos descritos en el texto, ¿cuál de las propuestas tiene mayor impacto a corto plazo? Justifica con al menos dos argumentos."
                Por qué es buena: exige selección, comparación y argumentación.

                [EJEMPLO 2 — Nivel: Crear]
                "Con base en los principios del texto, diseña una estrategia alternativa para el problema planteado y explica cómo aplicarías cada principio."
                Por qué es buena: el estudiante sintetiza y crea, no solo recuerda.
                """;
            default -> throw new IllegalArgumentException("Tipo no válido: " + tipo);
        };
    }
}