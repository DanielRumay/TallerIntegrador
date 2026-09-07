package com.example.tallerintegrador.service.ia;

import org.springframework.stereotype.Service;

@Service
public class PromptTemplateService {

    public static final String OPCION_MULTIPLE = "OPCION_MULTIPLE";
    public static final String VERDADERO_FALSO = "VERDADERO_FALSO";
    public static final String ABIERTA         = "ABIERTA";
    public static final String DETECCION_ERRORES = "DETECCION_ERRORES";
    public static final String VISUAL_QUIZ       = "VISUAL_QUIZ";
    public static final String VIDEO_EXPLICATIVO = "VIDEO_EXPLICATIVO";

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
          
        REGLAS DE FORMULACIÓN DE PREGUNTAS CLAVE:
        1. Está TERMINANTEMENTE PROHIBIDO hacer referencia en el enunciado de las preguntas a elementos estructurales externos del documento original que el alumno no pueda ver en pantalla (por ejemplo: evitar frases como 'según la sección 3', 'como se menciona en el párrafo 5', 'en la página 2', etc.). Las preguntas deben ser completamente autónomas, basándose únicamente en el contenido conceptual del fragmento del texto provisto.
        2. Genera preguntas con sentido pedagógico real, variadas, interesantes y de valor crítico/analítico. Además de las preguntas que ya existan o que se almacenen históricamente, debes crear nuevas preguntas conceptuales con sentido para aportar variedad y evitar aburrir al alumno repitiendo las mismas estructuras y frases.
        3. DIVERSIDAD EXPLÍCITA ENTRE LAS PREGUNTAS DE ESTA MISMA TANDA. Cada pregunta que generes ahora debe atacar un ASPECTO DISTINTO del contenido, no la misma idea reformulada con otras palabras. Antes de escribir cada pregunta, comprueba que no repita el aspecto de ninguna anterior de esta misma respuesta.
           Aspectos distintos son, por ejemplo: qué ES algo · POR QUÉ ocurre · DÓNDE o CUÁNDO sucede · CÓMO funciona el proceso · QUÉ PASARÍA SI cambiara una condición · en QUÉ SE DIFERENCIA de otra cosa · qué CONSECUENCIAS tiene.
           EJEMPLO DE LO QUE NO DEBES HACER: "¿Por qué las plantas necesitan luz solar?" junto a "¿Qué función cumple la luz solar en las plantas?". Son la misma pregunta escrita de dos maneras y el alumno daría la misma respuesta a ambas.
           EJEMPLO CORRECTO: "¿Por qué las plantas necesitan luz solar?" junto a "¿Qué le ocurriría a una planta encerrada en un sótano sin ventanas?". Comparten tema pero exigen razonamientos diferentes.
        """;

    // EL ESQUEMA UNIVERSAL (Para ahorrar peticiones)
    private static final String UNIVERSAL_SCHEMA = """
    {
      "leccion": {
          "tema": "Titulo de la leccion (solo requerido si el tipo es VIDEO_EXPLICATIVO, de lo contrario omitir o dejar nulo)",
          "diapositivas": [
              {
                  "titulo": "Titulo de la diapositiva/escena",
                  "puntos_clave": ["punto clave 1", "punto clave 2"],
                  "narracion": "Guion explicativo y narrativo detallado que leera la voz en off en esta diapositiva",
                  "ejemplo": "Ejemplo practico o metafora facil de entender (solo para VIDEO_EXPLICATIVO, de lo contrario omitir o dejar nulo)",
                  "prompt_imagen": "descripcion detallada en ingles para una imagen ilustrativa sobre esta diapositiva (solo para VIDEO_EXPLICATIVO, de lo contrario omitir o dejar nulo)"
              }
          ]
      },
      "preguntas": [
        {
          "enunciado": "texto de la pregunta o parrafo con errores aqui",
          "opciones_o_respuesta": ["A) opcion1", "B) opcion2", "C) opcion3", "D) opcion4"],
          "respuesta_correcta": "texto exacto de la opcion correcta, lista de correcciones o rubrica para preguntas abiertas",
          "justificacion_pregunta": "explicacion en una sola linea sin saltos",
          "concepto": "el concepto o subtema PUNTUAL que esta pregunta evalua, en 2 a 5 palabras (ej. 'Fotosintesis', 'Elementos de la comunicacion', 'Ecuaciones de primer grado'). NUNCA el nombre del curso completo ni el titulo general del material: debe ser lo bastante especifico para que, si el alumno falla varias preguntas con el mismo concepto, quede claro que ES ESE subtema puntual el que no domina.",
          "prompt_imagen": "descripcion ultra detallada para generar una imagen (solo requerido si el tipo es VISUAL_QUIZ, de lo contrario omitir o dejar vacio)"
        }
      ],
      "evaluacion_bloom": {
          "nivel_bloom": "Recordar|Comprender|Aplicar|Analizar|Evaluar|Crear",
          "nivel_bloom_orden": "Numero del 1 al 6 (1:Recordar, 2:Comprender, 3:Aplicar, 4:Analizar, 5:Evaluar, 6:Crear)",
          "es_hots": "boolean (true si nivel_bloom_orden >= 4, false de lo contrario)"
      }
    }
    """;

    public static final String PROMPT_LLM_JUEZ = """
        Actúa como un profesor de Quinto Grado de Secundaria que es justo, equilibrado y con buen criterio pedagógico. 
        Tu tarea es calificar las respuestas de VARIOS alumnos basándote en la rúbrica proporcionada.
        
        DIRECTRICES DE EVALUACIÓN:
        1. Valora la comprensión del concepto central por encima de la longitud del texto. No penalices una respuesta breve si esta logra incluir los criterios exactos que exige la rúbrica.
        2. Sé fiel a la rúbrica actual: evalúa ÚNICAMENTE los elementos que se exigen para esa pregunta específica.
        3. No asumas ni infieras lo que el alumno "quiso decir"; evalúa solo lo que escribió objetivamente.
        
        PREGUNTA:
        %s
        
        RÚBRICA DE EVALUACIÓN:
        %s
        
        RESPUESTAS DE LOS ALUMNOS:
        %s
        
        Asigna una nota del 0 al 4 a CADA alumno basándote estrictamente en los niveles de la rúbrica.
        
        Responde ÚNICAMENTE con un arreglo JSON válido siguiendo este esquema exacto, sin texto adicional ni bloques markdown:
        [
          {
            "id_alumno": "identificador exacto que te pase",
            "nota": 0,
            "justificacion": "Explicación breve y pedagógica de por qué se asignó esta nota."
          }
        ]
        """;

    public String build(String tecnica, String tipoPregunta, String nivelBloom, String dificultad, String texto, int cantidad, java.util.List<String> preguntasEvitar) {
        return switch (tecnica) {
            case FEW_SHOT          -> fewShot(tipoPregunta, nivelBloom, dificultad, texto, cantidad, preguntasEvitar);
            case CHAIN_OF_THOUGHT  -> chainOfThought(tipoPregunta, nivelBloom, dificultad, texto, cantidad, preguntasEvitar);
            case STRUCTURED_OUTPUT -> structuredOutput(tipoPregunta, nivelBloom, dificultad, texto, cantidad, preguntasEvitar);
            default -> throw new IllegalArgumentException("Técnica no válida: " + tecnica);
        };
    }

    public String build(String tecnica, String tipoPregunta, String nivelBloom, String dificultad, String texto, int cantidad) {
        return build(tecnica, tipoPregunta, nivelBloom, dificultad, texto, cantidad, java.util.List.of());
    }

    //TÉCNICA 1: FEW-SHOT
    private String fewShot(String tipo, String bloom, String dificultad, String texto, int cantidad, java.util.List<String> preguntasEvitar) {
        String bloomLinea = bloom != null
                ? "Nivel cognitivo objetivo (Taxonomía Revisada de Bloom): " + bloom
                : "Apunta a niveles de orden superior: Analizar, Evaluar o Crear.";

        String difLinea = dificultad != null ? "Nivel de dificultad objetivo: " + dificultad : "Nivel de dificultad objetivo: INTERMEDIO";

        String ejemplos = obtenerEjemplosPorTipo(tipo);

        String exclusionRegla = "";
        if (preguntasEvitar != null && !preguntasEvitar.isEmpty()) {
            exclusionRegla = "\nREGLA DE EXCLUSIÓN CRÍTICA: Está terminantemente prohibido formular preguntas idénticas o semánticamente similares a las siguientes que el alumno ya ha contestado:\n" +
                             String.join("\n", preguntasEvitar.stream().map(p -> "- " + p).toList()) + "\n";
        }

        return """
            %s
            %s
            %s
            
            EJEMPLOS DE ALTA CALIDAD PARA REFERENCIA:
            %s
            
            Usando estos ejemplos como modelo, genera %d pregunta(s) de tipo %s a partir del texto.
            %s
            CRÍTICO: Al finalizar, DEBES responder ÚNICAMENTE con un JSON válido siguiendo este esquema exacto, 
            el cual incluye tu propia autoevaluación:
            %s
            
            TEXTO:
            %s
            """.formatted(SYSTEM_PROMPT, bloomLinea, difLinea, ejemplos, cantidad, tipo, exclusionRegla, UNIVERSAL_SCHEMA, texto);
    }

    //TÉCNICA 2: CHAIN-OF-THOUGHT
    private String chainOfThought(String tipo, String bloom, String dificultad, String texto, int cantidad, java.util.List<String> preguntasEvitar) {
        String bloomLinea = bloom != null
                ? "Nivel Bloom objetivo: " + bloom
                : "Apunta al nivel más alto posible (Analizar/Evaluar/Crear).";

        String difLinea = dificultad != null ? "Nivel de dificultad objetivo: " + dificultad : "Nivel de dificultad objetivo: INTERMEDIO";

        String exclusionRegla = "";
        if (preguntasEvitar != null && !preguntasEvitar.isEmpty()) {
            exclusionRegla = "\nREGLA DE EXCLUSIÓN CRÍTICA: Está terminantemente prohibido formular preguntas idénticas o semánticamente similares a las siguientes que el alumno ya ha contestado:\n" +
                             String.join("\n", preguntasEvitar.stream().map(p -> "- " + p).toList()) + "\n";
        }

        return """
            %s
            %s
            %s
            
            Tu tarea: genera %d pregunta(s) de tipo %s.
            %s
            Antes de generar el JSON final, razona en voz alta siguiendo estos pasos:
            PASO 1 — IDENTIFICAR CONCEPTOS CLAVE: Lista los 3 a 5 conceptos más importantes del texto.
            PASO 2 — SELECCIÓN COGNITIVA: Decide qué nivel de Bloom evaluar priorizando el orden superior.
            PASO 3 — DISEÑO: Formula la pregunta considerando la dificultad requerida.
            PASO 4 — AUTOCRÍTICA: Revisa si es ambigua o evalúa realmente el nivel elegido.
            
            PASO 5 — PRESENTACIÓN FINAL: Debes obligatoriamente encerrar el resultado final en un bloque de 
            código JSON siguiendo estrictamente este esquema:
            ```json
            %s
            ```
            
            TEXTO:
            %s
            """.formatted(SYSTEM_PROMPT, bloomLinea, difLinea, cantidad, tipo, exclusionRegla, UNIVERSAL_SCHEMA, texto);
    }

    // TÉCNICA 3: STRUCTURED OUTPUT
    private String structuredOutput(String tipo, String bloom, String dificultad, String texto, int cantidad, java.util.List<String> preguntasEvitar) {
        String bloomLinea = obtenerEspecificacionBloom(bloom);

        String difLinea = dificultad != null ? "Nivel de dificultad objetivo: " + dificultad : "Nivel de dificultad objetivo: INTERMEDIO";

        String exclusionRegla = "";
        if (preguntasEvitar != null && !preguntasEvitar.isEmpty()) {
            exclusionRegla = "\nREGLA DE EXCLUSIÓN CRÍTICA: Está terminantemente prohibido formular preguntas idénticas o semánticamente similares a las siguientes que el alumno ya ha contestado:\n" +
                             String.join("\n", preguntasEvitar.stream().map(p -> "- " + p).toList()) + "\n";
        }

        String ejemplos = obtenerEjemplosPorTipo(tipo);

        return """
        %s
        %s
        %s
        
        Genera exactamente %d pregunta(s) de tipo %s a partir del texto.
        %s
        
        EJEMPLO DE REFERENCIA DE ESTRUCTURA Y CONTENIDO PARA ESTE TIPO DE PREGUNTA:
        %s
        
        REGLAS ABSOLUTAS — VIOLACIONES CAUSAN ERROR DE SISTEMA:
        1. Responde ÚNICAMENTE con JSON puro. Cero texto extra, cero markdown, cero ```.
        2. El campo 'opciones_o_respuesta' DEBE ser un ARRAY DE STRINGS:
        - OPCION_MULTIPLE → ["A) opcion1", "B) opcion2", "C) opcion3", "D) opcion4"]
        - VERDADERO_FALSO → ["VERDADERO", "FALSO"]
        - ABIERTA → ["Rubrica: criterio1. criterio2. criterio3."]
        - DETECCION_ERRORES → ["palabra_incorrecta1", "palabra_incorrecta2", "palabra_incorrecta3"] (lista de palabras con errores del enunciado)
        - VISUAL_QUIZ → ["A) opcion1", "B) opcion2", "C) opcion3", "D) opcion4"]
        - VIDEO_EXPLICATIVO → ["A) opcion1", "B) opcion2", "C) opcion3", "D) opcion4"]
        3. PROHIBIDO usar comillas dobles dentro de los valores de texto. Usa comillas simples si necesitas citar.
        4. PROHIBIDO saltos de línea dentro de los valores de los campos.
        5. El JSON debe ser parseable por Jackson ObjectMapper sin ningún procesamiento adicional.
        6. Si el tipo es VISUAL_QUIZ, es OBLIGATORIO que el campo 'prompt_imagen' contenga una descripcion en ingles muy detallada, artistica, tipo diagrama escolar o ilustracion educativa en 2D, para generar la imagen con una IA. CRÍTICO DE IDIOMA Y TEXTO: Para evitar que aparezcan palabras en inglés en las ilustraciones, el prompt_imagen generado debe indicar expresamente evitar textos en inglés usando frases como 'without any English text', 'completely textless', o 'any written text/labels must be in Spanish'. Si es estrictamente necesario incluir texto explicativo, las palabras deben indicarse en español (ej. 'with the label "Sujeto" in Spanish'). Además, el 'enunciado' de la pregunta debe hacer referencia directa e indispensable a los elementos visuales de esa imagen (ej. 'Observa la ilustración y responde...', 'Según el diagrama generado...'), de modo que el reactivo requiera analizar la imagen para resolverse.
        7. Si el tipo es DETECCION_ERRORES, el 'enunciado' debe ser un parrafo fluido que contenga de 2 a 3 errores conceptuales sutiles basados en el texto. CRÍTICO DE TAMAÑO DE ERRORES: Cada error en 'opciones_o_respuesta' debe ser ÚNICAMENTE una palabra clave o una frase extremadamente corta (máximo de 1 a 3 palabras, por ejemplo: 'pared celular', 'nucleolo', 'oxigeno'). Queda ESTRICTAMENTE PROHIBIDO seleccionar cláusulas largas, oraciones enteras o líneas grandes de texto como error. Las palabras elegidas como error deben ser términos clave puntuales y específicos del concepto. 'opciones_o_respuesta' contendra exactamente esas palabras con errores, y 'respuesta_correcta' contendra las correcciones exactas separadas por el caracter '|' en el mismo orden. CRÍTICO DE CONCORDANCIA Y GRAMÁTICA: La sustitución directa y exacta del error por la respuesta_correcta en el texto DEBE dar como resultado una oración perfectamente gramatical, natural e impecable en español. Para evitar incoherencias gramaticales (como 'recorre una territorios rurales'): a) DEBES incluir dentro del error en 'opciones_o_respuesta' CUALQUIER artículo (un, una, el, la, los, las), preposición, adjetivo, determinante o conector que preceda o acompañe al error en el enunciado, y corregirlo adecuadamente en la respuesta_correcta. Por ejemplo, si en el texto dices 'el spinner recorre una megalópolis densamente poblada', el error DEBE incluir el artículo 'una' (error: 'una megalópolis densamente poblada' -> corrección: 'unos territorios rurales' o 'territorios rurales'). Si el error no incluye el artículo 'una', se romperá la concordancia al reemplazarlo por 'territorios rurales'. b) Si no se incluye el artículo, el error y su corrección DEBEN coincidir estrictamente en género y número gramatical (ej. femenino singular 'megalópolis' -> 'urbe histórica'; masculino singular 'entorno urbano' -> 'entorno rural'). ¡La concordancia de género, número y determinantes es obligatoria y crítica para que la lectura fluida sea correcta tras el reemplazo!
        8. Si el tipo es VIDEO_EXPLICATIVO, es OBLIGATORIO rellenar el campo 'leccion' con un curso/videolección que conste de exactamente 3 diapositivas sobre el tema. Cada diapositiva debe tener un 'titulo', una lista de 2 a 3 'puntos_clave', una 'narracion' de 4 a 6 oraciones detalladas que expliquen el concepto, un 'ejemplo' práctico/cotidiano de ese concepto, y un 'prompt_imagen' con una descripción en inglés de 2D vector graphic/educational diagram representando esa diapositiva. CRÍTICO DE IDIOMA Y TEXTO: El prompt_imagen de cada diapositiva debe indicar expresamente evitar textos en inglés, utilizando frases como 'without any English text' o 'completely textless', o especificando que cualquier texto requerido sea en español. Las 'preguntas' generadas deben ser cuestionarios de opcion multiple basados en lo que se explica en estas diapositivas.
        
        ESQUEMA OBLIGATORIO:
        %s
        
        TEXTO:
        %s
        """.formatted(SYSTEM_PROMPT, bloomLinea, difLinea, cantidad, tipo, exclusionRegla, ejemplos, UNIVERSAL_SCHEMA, texto);
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
            case DETECCION_ERRORES -> """
                [EJEMPLO 1 — Nivel: Analizar]
                Enunciado: La celula animal contiene pared celular que le da rigidez, y su nucleolo es el encargado de almacenar el ADN celular.
                Opciones_o_respuesta: ["pared celular", "nucleolo"]
                Respuesta_correcta: membrana celular | nucleo
                Justificacion: La celula animal no tiene pared celular (sino membrana) y el ADN se almacena en el nucleo.
                
                [EJEMPLO 2 — Nivel: Analizar]
                Enunciado: En la secuencia de apertura de Blade Runner 2049, el spinner recorre una megalópolis densamente poblada, donde los humanos conviven con los replicantes en espacios verdes y altamente productivos.
                Opciones_o_respuesta: ["una megalópolis densamente poblada", "espacios verdes"]
                Respuesta_correcta: territorios rurales y yermos | planicies de paneles solares
                Justificacion: En la película se recorren territorios rurales y yermos (reemplazando "una megalópolis densamente poblada" para mantener la gramática perfecta en español) y planicies de paneles solares (en lugar de espacios verdes).
                """;
            case VISUAL_QUIZ -> """
                [EJEMPLO 1 — Nivel: Comprender]
                Enunciado: Observa el diagrama del ciclo del agua generado. ¿Qué proceso se representa con la flecha que asciende desde el océano hacia las nubes (marcada con el signo de interrogación)?
                Opciones_o_respuesta: ["A) Precipitación", "B) Evaporación", "C) Condensación", "D) Infiltración"]
                Respuesta_correcta: B) Evaporación
                Prompt_imagen: A clean educational vector diagram of the water cycle, showing the ocean, clouds, sun, and an arrow pointing up from the ocean to the clouds labeled with a question mark.
                Justificacion_pregunta: La evaporación es la fase del ciclo donde el agua pasa de líquido a gas y asciende, como se indica con la flecha en la imagen.
                """;
            case VIDEO_EXPLICATIVO -> """
                [EJEMPLO 1 — Nivel: Comprender]
                Leccion: {
                  "tema": "El Ciclo del Carbono",
                  "diapositivas": [
                    {
                      "titulo": "1. ¿Qué es el ciclo del carbono?",
                      "puntos_clave": ["El carbono fluye por la biosfera", "Esencial para las moléculas de la vida"],
                      "narracion": "Bienvenidos a esta lección sobre el ciclo del carbono. Este elemento es el bloque de construcción fundamental de los seres vivos. Fluye constantemente entre la atmósfera, las plantas, los océanos y los animales.",
                      "ejemplo": "Así como el dinero circula de mano en mano al comprar y vender, el carbono circula de planta en animal al alimentarse y respirar.",
                      "prompt_imagen": "A clean 2D educational illustration of a tree absorbing carbon dioxide from the air while a rabbit nearby breathes, simple vector style"
                    }
                  ]
                }
                Pregunta: ¿Por qué es fundamental el carbono para los organismos vivos?
                A) Es el elemento químico más abundante del planeta
                B) Es el bloque de construcción de las moléculas biológicas ← CORRECTA
                C) Evita el calentamiento global
                D) Permite la respiración anaeróbica únicamente
                Justificacion: El carbono forma el esqueleto de proteínas, lípidos y carbohidratos, como se explicó en la diapositiva 1.
                """;
            default -> throw new IllegalArgumentException("Tipo no válido: " + tipo);
        };
    }

    private String obtenerEspecificacionBloom(String bloom) {
        if (bloom == null || bloom.isBlank()) {
            return "Nivel Bloom objetivo: Apunta a niveles de orden superior (nivel_bloom_orden >= 3).";
        }
        String normalizado = bloom.trim();
        String clave = normalizado.substring(0, 1).toUpperCase() + normalizado.substring(1).toLowerCase();
        
        return switch (clave) {
            case "Recordar" -> """
                NIVEL BLOOM OBJETIVO: Recordar (Nivel 1)
                - OBLIGATORIO: El objeto 'evaluacion_bloom' en el JSON DEBE ser estrictamente: {"nivel_bloom": "Recordar", "nivel_bloom_orden": 1, "es_hots": false}
                - Verbos de acción obligatorios: Reconocer, identificar, nombrar, listar, definir, señalar, indicar.
                - Restricción de estructura: El enunciado DEBE exigir únicamente la recuperación de memoria de un dato factual, término o definición directa explícita en el texto. PROHIBIDO requerir justificaciones, comparaciones o inferencias.
                """;
            case "Comprender" -> """
                NIVEL BLOOM OBJETIVO: Comprender (Nivel 2)
                - OBLIGATORIO: El objeto 'evaluacion_bloom' en el JSON DEBE ser estrictamente: {"nivel_bloom": "Comprender", "nivel_bloom_orden": 2, "es_hots": false}
                - Verbos de acción obligatorios: Explicar, resumir, interpretar, parafrasear, ilustrar, clasificar.
                - Restricción de estructura: El enunciado DEBE exigir que el estudiante procese e interprete el significado central, explique la razón/causa de un concepto o traduzca la idea a sus propias palabras. PROHIBIDO hacer preguntas puramente memóricas de definición directa o comparaciones analíticas avanzadas.
                """;
            case "Aplicar" -> """
                NIVEL BLOOM OBJETIVO: Aplicar (Nivel 3)
                - OBLIGATORIO: El objeto 'evaluacion_bloom' en el JSON DEBE ser estrictamente: {"nivel_bloom": "Aplicar", "nivel_bloom_orden": 3, "es_hots": false}
                - Verbos de acción obligatorios: Aplicar, ejecutar, resolver, utilizar, completar, corregir, seleccionar la forma correcta.
                - Restricción de estructura: El objetivo es que el estudiante PRODUZCA O EJECUTE la aplicación práctica correcta de la regla gramatical en un contexto dado, NO QUE LA EXPLIQUE O JUSTIFIQUE.
                - REGLAS DE PROHIBICIÓN ABSOLUTA PARA 'APLICAR':
                  1. Queda TERMINANTEMENTE PROHIBIDO usar las palabras o preguntas: '¿Por qué...?', 'Explica', 'Argumenta', 'Justifica' o 'Describe la razón'.
                  2. PROHIBIDO solicitar al estudiante explicaciones teóricas o fundamentaciones del motivo de la respuesta.
                - TAREAS OBLIGATORIAS DE EJECUCIÓN DIRECTA:
                  - Completar una oración con la forma gramaticalmente correcta (ej. pronombre o adjetivo adecuado).
                  - Identificar o corregir el error práctico en el uso de una regla en una oración concreta.
                  - Seleccionar entre 4 opciones la única oración que aplica correctamente la regla gramatical en una situación real dada.
                """;
            case "Analizar" -> """
                NIVEL BLOOM OBJETIVO: Analizar (Nivel 4)
                - OBLIGATORIO: El objeto 'evaluacion_bloom' en el JSON DEBE ser estrictamente: {"nivel_bloom": "Analizar", "nivel_bloom_orden": 4, "es_hots": true}
                - Verbos de acción obligatorios: Diferenciar, discriminar, descomponer, desglosar, organizar, atribuir, detectar errores/incoherencias.
                - Restricción de estructura: El enunciado DEBE requerir que el estudiante descomponga una estructura o texto complejo en sus partes constituyentes, identifique relaciones causales profundas o detecte incoherencias/errores sutiles entre conceptos.
                - REGLAS DE PROHIBICIÓN PARA 'ANALIZAR': Prohibido hacer preguntas directas de memoria o simples comparaciones superficiales de 'qué es X'. Debe requerir análisis estructural o hallazgo de fallas de lógica.
                """;
            case "Evaluar" -> """
                NIVEL BLOOM OBJETIVO: Evaluar (Nivel 5)
                - OBLIGATORIO: El objeto 'evaluacion_bloom' en el JSON DEBE ser estrictamente: {"nivel_bloom": "Evaluar", "nivel_bloom_orden": 5, "es_hots": true}
                - Verbos de acción obligatorios: Juzgar, criticar, evaluar, argumentar, sopesar, valorar, justificar, fundamentar.
                - Restricción de estructura: El enunciado DEBE presentar una disyuntiva, dos posturas contrapuestas o una solución propuesta, exigiendo que el estudiante emita un juicio de valor fundamentado bajo criterios explícitos (efectividad, ética, rigurosidad pedagógica).
                """;
            case "Crear" -> """
                NIVEL BLOOM OBJETIVO: Crear (Nivel 6)
                - OBLIGATORIO: El objeto 'evaluacion_bloom' en el JSON DEBE ser estrictamente: {"nivel_bloom": "Crear", "nivel_bloom_orden": 6, "es_hots": true}
                - Verbos de acción obligatorios: Diseñar, proponer, formular, elaborar, construir, sintetizar, generar una hipótesis.
                - Restricción de estructura: El enunciado DEBE exigir que el estudiante combine e integre conocimientos para producir una propuesta original, una solución técnica inédita, un ejemplo propio o una hipótesis de trabajo.
                """;
            default -> "Nivel Bloom objetivo: " + bloom;
        };
    }
}