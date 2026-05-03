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


    public String build(String tecnica, String tipoPregunta,
                        String nivelBloom, String texto, int cantidad) {
        return switch (tecnica) {
            case FEW_SHOT          -> fewShot(tipoPregunta, nivelBloom, texto, cantidad);
            case CHAIN_OF_THOUGHT  -> chainOfThought(tipoPregunta, nivelBloom, texto, cantidad);
            case STRUCTURED_OUTPUT -> structuredOutput(tipoPregunta, nivelBloom, texto, cantidad);
            default -> throw new IllegalArgumentException("Técnica no válida: " + tecnica);
        };
    }

    //  TÉCNICA 1 — FEW-SHOT
    //  Da ejemplos antes de pedir la generación

    private String fewShot(String tipo, String bloom, String texto, int cantidad) {
        String bloomLinea = bloom != null
                ? "Nivel cognitivo objetivo (Taxonomía de Bloom): " + bloom
                : "Apunta a niveles de Bloom de orden superior: Analizar, Evaluar o Crear.";

        return switch (tipo) {
            case OPCION_MULTIPLE -> """
                Eres un experto en diseño de evaluaciones educativas.
                %s

                EJEMPLOS de preguntas de opción múltiple de alta calidad:

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

                Usando estos ejemplos como modelo, genera %d pregunta(s) de opción múltiple
                (4 opciones, una sola correcta) a partir del texto.
                Indica el nivel Bloom de cada pregunta e incluye la justificación.

                TEXTO:
                %s
                """.formatted(bloomLinea, cantidad, texto);

            case VERDADERO_FALSO -> """
                Eres un experto en diseño de evaluaciones educativas.
                %s

                EJEMPLOS de preguntas V/F de calidad:

                [EJEMPLO 1 — Nivel: Analizar]
                Afirmación: "La fotosíntesis y la respiración celular son opuestas porque una consume
                CO₂ y la otra lo produce, pero ambas ocurren en la mitocondria."
                Respuesta: FALSO
                Justificación: La fotosíntesis ocurre en cloroplastos, no mitocondrias.
                La afirmación mezcla un hecho correcto con uno incorrecto, exigiendo análisis.

                [EJEMPLO 2 — Nivel: Evaluar]
                Afirmación: "El método científico garantiza la verdad absoluta de una hipótesis
                si el experimento se repite suficientes veces."
                Respuesta: FALSO
                Justificación: El método científico solo puede falsificar hipótesis (Popper, 1959),
                no probar verdades absolutas.

                Genera %d afirmación(es) V/F con respuesta y justificación a partir del texto.
                Incluye el nivel Bloom de cada una.

                TEXTO:
                %s
                """.formatted(bloomLinea, cantidad, texto);

            case ABIERTA -> """
                Eres un experto en diseño de evaluaciones educativas.
                %s

                EJEMPLOS de preguntas abiertas de alta exigencia cognitiva:

                [EJEMPLO 1 — Nivel: Evaluar]
                "Considerando los efectos descritos en el texto, ¿cuál de las propuestas tiene
                mayor impacto a corto plazo? Justifica con al menos dos argumentos del texto."
                Por qué es buena: exige selección, comparación y argumentación.

                [EJEMPLO 2 — Nivel: Crear]
                "Con base en los principios del texto, diseña una estrategia alternativa para
                el problema planteado y explica cómo aplicarías cada principio."
                Por qué es buena: el estudiante sintetiza y crea, no solo recuerda.

                Genera %d pregunta(s) abierta(s) a partir del texto.
                Incluye el nivel Bloom objetivo y una rúbrica de 3 criterios por pregunta.

                TEXTO:
                %s
                """.formatted(bloomLinea, cantidad, texto);

            default -> throw new IllegalArgumentException("Tipo no válido: " + tipo);
        };
    }

    // ════════════════════════════════════════════════════════════════════
    //  TÉCNICA 2 — CHAIN-OF-THOUGHT
    //  El modelo razona en pasos antes de generar
    // ════════════════════════════════════════════════════════════════════

    private String chainOfThought(String tipo, String bloom, String texto, int cantidad) {
        String bloomLinea = bloom != null
                ? "Nivel Bloom objetivo: " + bloom
                : "Apunta al nivel más alto posible (Analizar/Evaluar/Crear).";

        String instruccionTipo = switch (tipo) {
            case OPCION_MULTIPLE ->
                    "genera %d pregunta(s) de OPCIÓN MÚLTIPLE (4 opciones, una correcta) con justificación.".formatted(cantidad);
            case VERDADERO_FALSO ->
                    "genera %d afirmación(es) de VERDADERO/FALSO con respuesta y justificación.".formatted(cantidad);
            case ABIERTA ->
                    "genera %d pregunta(s) ABIERTA(S) con rúbrica de 3 criterios.".formatted(cantidad);
            default -> throw new IllegalArgumentException("Tipo no válido: " + tipo);
        };

        return """
            Eres un experto en evaluaciones educativas. %s
            
            Tu tarea: %s
            
            Antes de generar, razona en voz alta siguiendo estos pasos:
            
            PASO 1 — IDENTIFICAR CONCEPTOS CLAVE:
            Lista los 3 a 5 conceptos más importantes del texto.
            
            PASO 2 — SELECCIÓN COGNITIVA:
            Para cada concepto, decide qué nivel de Bloom evaluar y por qué.
            Prioriza niveles de orden superior (Aplicar en adelante).
            
            PASO 3 — DISEÑO:
            Formula la pregunta asegurándote de que NO se pueda responder
            solo copiando una frase del texto.
            
            PASO 4 — AUTOCRÍTICA:
            Revisa: ¿Es ambigua? ¿Los distractores son plausibles?
            ¿Realmente evalúa el nivel Bloom elegido?
            
            PASO 5 — PREGUNTA FINAL:
            Presenta la versión corregida con el nivel Bloom asignado.
            
            TEXTO:
            %s
            """.formatted(bloomLinea, instruccionTipo, texto);
    }

    // ════════════════════════════════════════════════════════════════════
    //  TÉCNICA 3 — STRUCTURED OUTPUT
    //  Respuesta en JSON para procesamiento automático
    // ════════════════════════════════════════════════════════════════════

    private String structuredOutput(String tipo, String bloom, String texto, int cantidad) {
        String bloomLinea = bloom != null
                ? "Nivel Bloom objetivo: " + bloom
                : "Apunta a niveles de orden superior (nivel_bloom_orden >= 3).";

        String schema = switch (tipo) {
            case OPCION_MULTIPLE -> """
                {
                  "preguntas": [
                    {
                      "enunciado": "texto de la pregunta",
                      "opciones": { "A": "...", "B": "...", "C": "...", "D": "..." },
                      "respuesta_correcta": "A|B|C|D",
                      "justificacion": "por qué esa opción es correcta",
                      "nivel_bloom": "Recordar|Comprender|Aplicar|Analizar|Evaluar|Crear",
                      "nivel_bloom_orden": 1,
                      "es_hots": true
                    }
                  ]
                }""";
            case VERDADERO_FALSO -> """
                {
                  "preguntas": [
                    {
                      "afirmacion": "texto de la afirmación",
                      "respuesta": "VERDADERO|FALSO",
                      "justificacion": "explicación detallada",
                      "concepto_evaluado": "nombre del concepto",
                      "nivel_bloom": "Recordar|Comprender|Aplicar|Analizar|Evaluar|Crear",
                      "nivel_bloom_orden": 1,
                      "es_hots": true
                    }
                  ]
                }""";
            case ABIERTA -> """
                {
                  "preguntas": [
                    {
                      "enunciado": "texto de la pregunta abierta",
                      "nivel_bloom": "Recordar|Comprender|Aplicar|Analizar|Evaluar|Crear",
                      "nivel_bloom_orden": 1,
                      "es_hots": true,
                      "rubrica": [
                        { "criterio": "descripción", "puntaje_maximo": 2 },
                        { "criterio": "descripción", "puntaje_maximo": 2 },
                        { "criterio": "descripción", "puntaje_maximo": 1 }
                      ],
                      "respuesta_esperada": "ejemplo de respuesta de calidad"
                    }
                  ]
                }""";
            default -> throw new IllegalArgumentException("Tipo no válido: " + tipo);
        };

        return """
            Eres un experto en evaluaciones educativas. %s
            
            Genera exactamente %d pregunta(s) de tipo %s a partir del texto.
            
            REGLAS ESTRICTAS:
            - Responde ÚNICAMENTE con el JSON. Sin texto extra, sin markdown, sin ```.
            - El JSON debe ser válido y seguir exactamente este esquema:
            %s
            
            TEXTO:
            %s
            """.formatted(bloomLinea, cantidad, tipo, schema, texto);
    }

    // ════════════════════════════════════════════════════════════════════
    //  PROMPT DE EVALUACIÓN BLOOM
    //  Se usa para evaluar las respuestas de Few-Shot y CoT
    // ════════════════════════════════════════════════════════════════════

    public String buildEvaluationPrompt(String preguntaGenerada) {
        return """
            Eres un evaluador de la Taxonomía de Bloom Revisada (Anderson & Krathwohl, 2001).
            
            Evalúa la siguiente pregunta educativa. Responde SOLO con JSON válido, sin texto adicional:
            
            {
              "nivel_bloom": "Recordar|Comprender|Aplicar|Analizar|Evaluar|Crear",
              "nivel_bloom_orden": 1,
              "es_hots": true,
              "puntaje_calidad": 4.0,
              "justificacion": "por qué este nivel y puntaje",
              "sugerencia_mejora": "cómo mejorar el nivel cognitivo"
            }
            
            Escala puntaje_calidad:
            1 = Ambigua o solo memorización trivial
            2 = Solo Recordar/Comprender
            3 = Aplicar/Analizar con alguna imprecisión
            4 = Analizar/Evaluar con claridad
            5 = Evaluar/Crear con distractores plausibles y justificación sólida
            
            PREGUNTA:
            %s
            """.formatted(preguntaGenerada);
    }
}