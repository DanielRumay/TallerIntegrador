# Semantika — Cómo funciona por dentro

> Documento técnico. Explica lo que **no se ve** en pantalla: qué ocurre entre que un docente
> sube un PDF y un alumno recibe una pregunta, con los valores reales del código y la
> procedencia de cada técnica.
>
> Todo lo que aparece aquí está verificado contra el código fuente, no descrito de memoria.
> Los números entre corchetes remiten a la tabla de referencias del final.

---

## 1. Panorama general

```
   ┌──────────────────────────────────────────────────────────────────────────┐
   │                         NAVEGADOR (React + Vite)                         │
   │                                                                          │
   │   Alumno                    Docente                    Administrador     │
   │   · práctica                · sube material            · usuarios        │
   │   · Aria (voz)              · valida el juez           · métricas        │
   │   · mapa de calor           · ve resultados                              │
   └────────────────────────────────┬─────────────────────────────────────────┘
                                    │  HTTPS · JWT en cabecera
                                    ▼
   ┌──────────────────────────────────────────────────────────────────────────┐
   │                    BACKEND (Spring Boot 3.2.5 · Java 21)                 │
   │                                                                          │
   │   ┌────────────────────────────────────────────────────────────────┐     │
   │   │  FILTROS   RateLimitFilter → JwtFilter → AuthorizationFilter    │     │
   │   └────────────────────────────────────────────────────────────────┘     │
   │                                    │                                     │
   │   ┌────────────────────────────────▼───────────────────────────────┐     │
   │   │  CONTROLADORES  (14)   solo traducen HTTP ↔ servicio           │     │
   │   └────────────────────────────────┬───────────────────────────────┘     │
   │                                    │                                     │
   │   ┌────────────────────────────────▼───────────────────────────────┐     │
   │   │  SERVICIOS  (37, en 7 subpaquetes)                             │     │
   │   │                                                                │     │
   │   │   academico/   rag/     ia/    analitica/  metricas/  spike/   │     │
   │   │      6         10        2         6           5        2      │     │
   │   └────────────────────────────────┬───────────────────────────────┘     │
   │                                    │                                     │
   │   ┌────────────────────────────────▼───────────────────────────────┐     │
   │   │  AGENTES  (20)   comité · juez · tutora · validador de imagen  │     │
   │   └────────────────────────────────┬───────────────────────────────┘     │
   │                                    │                                     │
   │   ┌────────────────────────────────▼───────────────────────────────┐     │
   │   │  REPOSITORIOS (23)  →  ENTIDADES (29)                          │     │
   │   └────────────────────────────────────────────────────────────────┘     │
   └───────┬───────────────────┬──────────────────┬───────────────┬───────────┘
           │                   │                  │               │
           ▼                   ▼                  ▼               ▼
   ┌───────────────┐   ┌──────────────┐   ┌─────────────┐  ┌─────────────┐
   │  PostgreSQL   │   │   MongoDB    │   │   Qdrant    │  │   Gemini    │
   │               │   │              │   │             │  │             │
   │ usuarios      │   │ PDF en bruto │   │ vectores    │  │ generar     │
   │ intentos      │   │ subtemas     │   │  nivel 0    │  │ embeddings  │
   │ preguntas     │   │              │   │  nivel 1    │  │ visión      │
   │ BKT           │   │              │   │  nivel 2    │  │             │
   │ debates       │   │              │   │             │  │             │
   │ telemetría    │   │              │   │             │  │             │
   └───────────────┘   └──────────────┘   └─────────────┘  └─────────────┘
```

**Por qué tres bases de datos y no una.** Cada una guarda algo que las otras hacen mal:
PostgreSQL guarda relaciones y garantiza transacciones; MongoDB guarda archivos binarios sin
esquema fijo; Qdrant busca por **significado**, no por texto — es lo que permite encontrar un
párrafo sobre fotosíntesis cuando el alumno pregunta por "cómo comen las plantas", aunque no
compartan una sola palabra.

---

## 2. El canal de ingesta: de un PDF a vectores buscables

Es la parte más elaborada del sistema y la que menos se ve.

```
  El docente suelta un PDF
            │
            ▼
  ╔═════════════════════════════════════════════════════════════════════════╗
  ║ ETAPA 0 · SE COPIAN LOS BYTES  (IngestaAsincronaService)                 ║
  ║                                                                         ║
  ║   Spring BORRA el fichero temporal cuando termina la petición HTTP.      ║
  ║   Si se pasara el MultipartFile a un hilo de fondo, ese hilo intentaría  ║
  ║   leer un fichero que ya no existe → fallo intermitente e irreproducible ║
  ║   Por eso se copian los bytes AQUÍ, antes de responder.                  ║
  ╚═════════════════════════════════════════════════════════════════════════╝
            │
            │  responde YA con un ingestaId  ──────────►  el docente sigue trabajando
            │                                             y ve una barra de progreso
            ▼  (a partir de aquí, en segundo plano)
  ┌─────────────────────────────────────────────────────────────────────────┐
  │ ETAPA 1 · MongoDB                                          [ 5% ]       │
  │   Guarda el PDF en bruto. Límite real: 15 MB.                           │
  │   ¿Por qué 15 y no 25? MongoDB corta a los 16 MB por documento; con 25   │
  │   un archivo de 20 MB pasaba la subida y reventaba después.              │
  └─────────────────────────────────────────────────────────────────────────┘
            ▼
  ┌─────────────────────────────────────────────────────────────────────────┐
  │ ETAPA 2 · Apache Tika extrae el texto                      [ 15% ]      │
  │                                                                         │
  │   > 2 MB  →  NO decodifica las imágenes incrustadas                     │
  │              (en una obra ilustrada consume memoria sin aportar nada)    │
  │   OCR     →  solo si ocr.habilitado=true Y tesseract está instalado      │
  │              Estrategia OCR_AND_TEXT_EXTRACTION: usa la capa de texto    │
  │              cuando existe, y el OCR solo para lo que falte.             │
  └─────────────────────────────────────────────────────────────────────────┘
            ▼
  ╔═════════════════════════════════════════════════════════════════════════╗
  ║ ETAPA 2.1 · GUARDA:  ¿se extrajo texto de verdad?                       ║
  ║                                                                         ║
  ║   < 200 caracteres  →  FALLA, y lo dice claro:                          ║
  ║   "Si es un PDF escaneado o una foto de páginas, no tiene texto          ║
  ║    seleccionable y este sistema no puede leerlo."                        ║
  ║                                                                         ║
  ║   ANTES DE ESTA GUARDA: un PDF escaneado recorría todo el canal y        ║
  ║   devolvía exitoso=true con CERO vectores. El docente veía "material     ║
  ║   listo" y los alumnos recibían preguntas sin ningún contexto.           ║
  ║   Un fallo disfrazado de éxito es peor que una excepción: nadie lo       ║
  ║   investiga.                                                            ║
  ╚═════════════════════════════════════════════════════════════════════════╝
            ▼
  ┌─────────────────────────────────────────────────────────────────────────┐
  │ ETAPA 2.2 · SEGMENTACIÓN EN CASCADA                        [ 30% ]      │
  │             (SegmentadorDocumentoService)                                │
  │                                                                         │
  │   ¿< 20.000 caracteres?  ──SÍ──► DOCUMENTO_COMPLETO (1 sección)          │
  │            │ no                   un PDF de clase no se parte            │
  │            ▼                                                            │
  │   ¿"CAPÍTULO III"?       ──SÍ──► ENCABEZADOS                            │
  │   ¿"UNIDAD 1"?                   también: PARTE, SECCIÓN, TEMA, "1. X"  │
  │            │ no                                                         │
  │            ▼                                                            │
  │   ¿líneas cortas         ──SÍ──► TIPOGRAFICO                            │
  │    aisladas en mayúscula?         pero solo si hay ≥3 y ≤ longitud/3000  │
  │            │ no                   (un poemario dispararía el patrón 900  │
  │            ▼                       veces: una heurística que acierta     │
  │   VENTANAS de 12.000 car.          demasiado no está acertando)          │
  │   ⚠ MODO DEGRADADO, queda registrado                                    │
  └─────────────────────────────────────────────────────────────────────────┘
            ▼
  ┌─────────────────────────────────────────────────────────────────────────┐
  │ ETAPA 2.5 · SUBTEMAS, uno de cada N secciones              [ 40% ]      │
  │                                                                         │
  │   Máximo 12 llamadas, REPARTIDAS a lo largo del documento.               │
  │   Con 83 secciones toma 1 de cada 7, no las 12 primeras.                 │
  │                                                                         │
  │   Antes se mandaba el texto ENTERO en un prompt pidiendo "3 a 5          │
  │   subtemas". Sobre una novela devolvía "el amor, la guerra, el destino": │
  │   funcionaba y devolvía basura.                                         │
  │                                                                         │
  │   Los subtemas se deduplican por FORMA CANÓNICA (§7), así que            │
  │   "La fotosíntesis" y "Fotosíntesis" no ocupan dos huecos.               │
  └─────────────────────────────────────────────────────────────────────────┘
            ▼
  ┌─────────────────────────────────────────────────────────────────────────┐
  │ ETAPA 3 · TROCEADO, sección por sección                    [ 55% ]      │
  │                                                                         │
  │   2000 caracteres (~500 tokens) con 200 de SOLAPE.                       │
  │                                                                         │
  │   ¿Para qué sirve el solape?                                            │
  │      ...el signo es arbitrario porque no │ existe relación natural...    │
  │                  fragmento 1             │      fragmento 2              │
  │   Sin solape, NINGUNO contiene la definición completa y esa idea queda   │
  │   perdida para la búsqueda. Repitiendo los últimos 200 caracteres al     │
  │   inicio del siguiente, al menos una copia la contiene entera.           │
  │                                                                         │
  │   El corte busca el punto final más cercano: no parte una oración.       │
  │   Cada fragmento se etiqueta con  seccion · seccionOrden · nivel=0       │
  └─────────────────────────────────────────────────────────────────────────┘
            ▼
  ┌─────────────────────────────────────────────────────────────────────────┐
  │ ETAPA 3.5 · RESÚMENES JERÁRQUICOS — RAPTOR [1]             [ 65% ]      │
  │                                                                         │
  │        nivel 2   ┌───────────── resumen de la obra ─────────────┐       │
  │                  │                                              │       │
  │        nivel 1   ├── cap.1-3 ──┬── cap.4-6 ──┬── cap.7-9 ──┬────┤       │
  │                  │             │             │             │    │       │
  │        nivel 0   ├─ frag ─ frag┼ frag ─ frag ┼ frag ─ frag ┼────┤       │
  │                                                                         │
  │   Techo de 30 resúmenes. Si hay 83 secciones NO se muestrean: se AGRUPAN │
  │   las contiguas de 3 en 3. Un capítulo sin resumir quedaría invisible    │
  │   para cualquier pregunta de comprensión global, y nada lo avisaría.     │
  │                                                                         │
  │   Fusión de 8 en 8 hacia arriba, máximo 4 pasadas.                       │
  │   Los tres niveles van a la MISMA colección de Qdrant: es la estrategia  │
  │   de "árbol colapsado", que no obliga a cambiar el recuperador.          │
  └─────────────────────────────────────────────────────────────────────────┘
            ▼
  ┌─────────────────────────────────────────────────────────────────────────┐
  │ ETAPA 4 · EMBEBIDO POR LOTES → Qdrant                      [ 80% ]      │
  │                                                                         │
  │   Lotes de 25 con embedAll. Antes: una llamada por fragmento con         │
  │   Thread.sleep(200) entre cada una → 556 llamadas y 3 minutos.           │
  │   Si un lote falla, se reintenta fragmento a fragmento: es preferible    │
  │   perder unos pocos vectores a perder el documento entero.               │
  │                                                                         │
  │   GUARDA FINAL: si NINGÚN vector llegó a Qdrant, se declara fallido.     │
  └─────────────────────────────────────────────────────────────────────────┘
            ▼
                        [ 100% ]  "Material listo"
```

### Un libro de 600 páginas, en cifras reales

| | Valor |
|---|---|
| Caracteres | ~1.000.000 |
| Secciones (sin capítulos detectables) | 83 bloques |
| Fragmentos nivel 0 | ~556 |
| Resúmenes nivel 1 | 30 (agrupando de 3 en 3) |
| Resumen nivel 2 | 1 |
| **Vectores totales** | **~587** |
| Llamadas de generación | 12 + 30 + 5 = **47** |
| Lotes de embeddings | **24** |
| **Tiempo total** | **~3 minutos**, en segundo plano |

---

## 3. La recuperación: escalera de umbrales

Cuando hay que generar una pregunta sobre un tema, primero se busca contexto.

```
   consulta ──► se convierte en vector ──► se busca en Qdrant
                                                │
                                                ▼
                            ┌──────────────────────────────────┐
                            │  ¿hay resultados con ≥ 0,65?     │──SÍ──► CONFIABLE
                            └──────────────┬───────────────────┘
                                           │ no
                            ┌──────────────▼───────────────────┐
                            │  ¿hay resultados con ≥ 0,50?     │──SÍ──► CONFIABLE
                            └──────────────┬───────────────────┘
                                           │ no
                            ┌──────────────▼───────────────────┐
                            │  ¿hay resultados con ≥ 0,30?     │──SÍ──► ⚠ DEGRADADO
                            └──────────────┬───────────────────┘        se registra
                                           │ no
                                           ▼
                                    SIN CONTEXTO
                                    (también se registra)

   Se devuelven los 8 mejores fragmentos (TOP_K = 8).
```

**Por qué una escalera y no un umbral fijo.** Con un umbral único hay que elegir entre
quedarse sin contexto a menudo o aceptar contexto malo siempre. La escalera intenta lo bueno
primero y, si tiene que conformarse, **deja constancia de que lo hizo**. Eso permite reportar
en el informe *"en el X % de las generaciones el contexto fue confiable"* — un dato, no una
impresión.

El filtro por `archivoId` se aplica **dentro de Qdrant**, no en memoria: se traen solo los
fragmentos del material correcto en lugar de traerlos todos y descartarlos después.

---

## 4. Generación de una pregunta, y las cuatro capas contra repetidos

```
  recuperar fragmentos ──► seleccionar contexto ──► generar N preguntas
        (RAG)                (ContextSelector)      (una sola llamada)
                                                            │
                                                            ▼
                                             para CADA pregunta generada:
                                                            │
       ┌────────────────────────────────────────────────────┘
       ▼
  ╔═══════════════════════════════════════════════════════════════════════╗
  ║ CAPA 0 · PREVENCIÓN — en el propio prompt                             ║
  ║                                                                       ║
  ║   "Cada pregunta debe atacar un ASPECTO DISTINTO: qué es · por qué ·   ║
  ║    dónde · cómo · qué pasaría si · en qué se diferencia..."            ║
  ║                                                                       ║
  ║   Prevenir es mejor que rechazar: un filtro descarta DESPUÉS de haber  ║
  ║   gastado la llamada. Principio de MMR [2] y de la diversidad          ║
  ║   explícita en generación de preguntas [3].                            ║
  ╚═══════════════════════════════════════════════════════════════════════╝
       ▼
  ┌───────────────────────────────────────────────────────────────────────┐
  │ GUARDIA DE ENUNCIADO   ¿dice "según la sección 3"?                    │
  │   6 patrones con (?iu) — la marca u es imprescindible: sin ella,      │
  │   "SEGÚN LA SECCIÓN 1" en mayúsculas con tilde NO se detectaba.       │
  │   El alumno no ve el documento: una pregunta así es irresoluble.      │
  └───────────────────────────────┬───────────────────────────────────────┘
       ▼
  ┌───────────────────────────────────────────────────────────────────────┐
  │ CAPA 1 · texto exacto contra el historial (últimas 50)                │
  └───────────────────────────────┬───────────────────────────────────────┘
       ▼
  ┌───────────────────────────────────────────────────────────────────────┐
  │ CAPA 2 · vectorial en Qdrant, contra TODO el histórico del alumno     │
  │                                                                       │
  │      similitud ≥ 0,95   ──►  duplicado claro, se rechaza              │
  │      0,82 ─ 0,95        ──►  ZONA DUDOSA: se le muestran las DOS      │
  │                              preguntas juntas al modelo y decide      │
  │      < 0,82             ──►  ni se considera                          │
  │                                                                       │
  │   Con un solo umbral no hay valor bueno:                              │
  │     0,95 dejaba pasar "¿Por qué las plantas necesitan luz?" junto a   │
  │          "¿Qué función cumple la luz solar en las plantas?" (~0,85)   │
  │     0,85 rechazaría "¿Qué es la fotosíntesis?" junto a                │
  │          "¿Dónde ocurre la fotosíntesis?", que son legítimas          │
  │                                                                       │
  │   Separar RECUPERAR de DECIDIR es la solución del área [2]. En la     │
  │   literatura la segunda etapa es un cross-encoder afinado sobre pares │
  │   de preguntas duplicadas; aquí cumple esa función el modelo ya       │
  │   integrado, que ve las dos preguntas JUNTAS en vez de comparar dos   │
  │   vectores calculados por separado.                                   │
  └───────────────────────────────┬───────────────────────────────────────┘
       ▼
  ┌───────────────────────────────────────────────────────────────────────┐
  │ CAPA 3 · contra las preguntas de ESTE MISMO examen                    │
  │                                                                       │
  │   Las preguntas del lote NO están todavía en Qdrant: se indexan       │
  │   cuando el alumno termina. Así que la capa 2 no puede verlas.        │
  │   Dos reformulaciones generadas en la misma llamada pasaban ambas.    │
  │                                                                       │
  │   Y es el caso MÁS PROBABLE: al pedir cinco preguntas sobre un mismo  │
  │   fragmento, la tendencia natural del modelo es que dos se parezcan.  │
  │                                                                       │
  │   Se calculan los vectores al vuelo y se compara el coseno en memoria │
  │   con la misma escalera de dos etapas.                                │
  └───────────────────────────────┬───────────────────────────────────────┘
       ▼
                            PREGUNTA ACEPTADA
```

Cada capa deja su propia etiqueta en telemetría (`DUPLICADO_EXACTO`, `DUPLICADO_VECTORIAL`,
`DUPLICADO_CONFIRMADO`, `SIMILAR_PERO_DISTINTA`, `ACEPTADA`), así que puede reportarse
**cuántas atrapó cada una** en lugar de afirmar que no hay duplicados.

---

## 5. El comité de agentes y el derecho de veto

Es el flujo que decide en qué nivel está el alumno. **Aquí sí hay deliberación**: cada turno
lee lo que dijeron los anteriores.

```
   contexto del alumno (notas, fallos recientes, perfil)
            │
            ▼
   ┌─────────────────────┐
   │ TURNO 1 · Evaluador │  abre con su postura
   └──────────┬──────────┘
              │ ve el turno 1
   ┌──────────▼──────────────┐
   │ TURNO 2 · Psicopedagogo │  responde
   └──────────┬──────────────┘
              │ ve 1 y 2
   ┌──────────▼──────────────┐
   │ TURNO 3 · Adaptación    │  interviene
   └──────────┬──────────────┘
              │ ve 1, 2 y 3
   ┌──────────▼──────────────┐
   │ TURNO 4 · Psicopedagogo │  réplica
   └──────────┬──────────────┘
              ▼
   ┌─────────────────────────┐
   │ COORDINADOR             │  emite un CONSENSO
   │                         │  (una decisión, no otra postura)
   └──────────┬──────────────┘
              │  propone: AVANZADO
              ▼
  ╔═════════════════════════════════════════════════════════════════════╗
  ║  VERIFICADOR — SIN LLM. Aquí está el derecho de veto.                ║
  ║                                                                     ║
  ║   Regla 1  ¿salta dos niveles de golpe?                             ║
  ║            PRINCIPIANTE → AVANZADO   ✗ VETO, se aplica INTERMEDIO   ║
  ║                                                                     ║
  ║   Regla 2  ¿AVANZADO con rendimiento sostenido?                     ║
  ║            exige ≥ 16,0 en las DOS últimas evaluaciones formativas  ║
  ║            observado: 8,00 · 12,50  ✗ VETO                          ║
  ║                                                                     ║
  ║   Regla 3  ¿INTERMEDIO habiendo aprobado?                           ║
  ║            exige ≥ 11,0 en la última  ✗ VETO si no                  ║
  ║                                                                     ║
  ║   Cada veto guarda su MOTIVO en texto:                              ║
  ║   "AVANZADO requiere 16,0 o más en las últimas 2 evaluaciones;      ║
  ║    observado: 8,00, 12,50."                                         ║
  ╚═════════════════════════════════════════════════════════════════════╝
              ▼
        NIVEL APLICADO  +  todo el debate persistido en `debate_agentes`
                           (transcripción, posturas, veto, latencia)
```

**Por qué el verificador no usa el modelo.** Su valor es exactamente ser **auditable y
reproducible**. Ante la pregunta *"¿y si la IA se equivoca al clasificar a mi alumno?"*, la
respuesta no es que los modelos no fallan, sino que hay una regla determinista con derecho de
veto por encima de ellos, legible en 40 líneas y con constancia escrita de cada intervención.

**Matiz honesto para el informe:** hay literatura [4] que muestra que las discusiones
multiagente **empatan** con un solo agente cuando ambos tienen buenos ejemplos en el prompt, y
solo ganan cuando faltan. Conviene justificar el comité por **trazabilidad y veto**, no por
una superioridad automática.

---

## 6. Aria: la tutora socrática

```
   Aria pregunta
        │
        ▼
   el alumno responde (voz o texto)
        │
        ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │  ESCALÓN 1                                                       │
  │  ¿acertó?  ──SÍ──►  valida, añade un matiz  ──► [ACCION: AVANZAR]│
  │      │ no                                                        │
  │      └──►  señala el hueco SIN resolverlo                        │
  │           termina con una pregunta orientadora                   │
  │                                    ──► [ACCION: REPREGUNTA]      │
  └───────────────────────┬──────────────────────────────────────────┘
                          │  misma pregunta, un escalón más
                          ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │  ESCALÓN 2                                                       │
  │  plantea una SITUACIÓN HIPOTÉTICA o un ejemplo cotidiano donde   │
  │  el concepto se vea actuando, sin nombrarlo                      │
  │  "¿Qué le pasaría a una planta encerrada en un sótano?"          │
  │                                    ──► [ACCION: REPREGUNTA]      │
  └───────────────────────┬──────────────────────────────────────────┘
                          ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │  ESCALÓN 3 · AHORA SÍ EXPLICA                                    │
  │  y conecta con lo que el alumno SÍ acertó antes,                 │
  │  para que vea que no partió de cero  ──► [ACCION: AVANZAR]       │
  └──────────────────────────────────────────────────────────────────┘
```

**Lo que había antes.** El prompt decía literalmente *"PROHIBIDO hacer preguntas abiertas o
repreguntas al final"*. Esa línea sola convertía a Aria en un examen oral con comentarios. Y
generaba una pista (`pista_si_no_responde`) que el frontend guardaba y **nunca mostraba**.

**Además:** decir "no entiendo" disparaba puntuación 1 **y le entregaba la respuesta**. Se
castigaba al alumno por admitir confusión, justo en el momento en que debía recibir una pista.

**El escalón consumido se persiste** (`turno_tutor_socratico`). Deja de ser una función de
interfaz y pasa a ser una **variable de proceso medible**: si un alumno pasa de necesitar el
escalón 3 a resolver en el 1 sobre los mismos conceptos, eso es evidencia de aprendizaje.

Las etiquetas se parsean **en el servidor**, no se reciben del navegador: un dato que va a
sostener un resultado de tesis no puede depender de lo que reporte el cliente.

---

## 7. Trazado del conocimiento (BKT)

Cada respuesta actualiza una probabilidad de dominio por (alumno, concepto, nivel de Bloom).
El modelo es el de Corbett & Anderson [5].

```
   ┌─────────────────────────────────────────────────────────────────┐
   │  PARÁMETROS                                                     │
   │    P(dominio inicial)  = 0,30   antes de la primera observación │
   │    P(transición)       = 0,15   aprende entre pregunta y pregunta│
   │    P(desliz)           = 0,10   falla aunque domine             │
   │    P(adivinanza)       = 0,20   acierta sin dominar             │
   └─────────────────────────────────────────────────────────────────┘

   ACIERTA                                    FALLA
        │                                       │
        ▼                                       ▼
   P·(1-desliz)                            P·desliz
   ───────────────────────────      ───────────────────────────────
   P·(1-desliz) + (1-P)·adiv        P·desliz + (1-P)·(1-adivinanza)
        │                                       │
        └───────────────┬───────────────────────┘
                        ▼
              posterior + (1-posterior)·0,15
              (pudo aprender entre medias,
               acertara o no)
```

**Por qué esto y no un porcentaje de aciertos.** Un 3 de 5 y un 60 % sobre 40 preguntas se
pintaban igual, y un acierto aislado disparaba el indicador. El BKT no promedia: mantiene una
creencia que se actualiza, y distingue "acertó de casualidad" de "lo domina".

### El defecto que casi lo invalida: los conceptos venían en texto libre

```
   El modelo devolvía:  "Fotosíntesis"  ·  "La fotosíntesis"  ·  "fotosintesis "
                              │                  │                    │
                              ▼                  ▼                    ▼
                          fila 1             fila 2              fila 3
                          P=0,35             P=0,42              P=0,31
                              └──────────────┬───────────────────────┘
                            La evidencia repartida en tres trozos.
                            NINGUNO alcanza el umbral de dominio.
                            El mapa mostraba TRES puntos débiles
                            donde en realidad hay UNO.
```

**Solución — `NormalizadorConcepto`:** minúsculas, sin tildes, sin artículo inicial, espacios
colapsados. `concepto` guarda la **clave de agrupación**; `concepto_etiqueta` guarda la forma
legible, porque "fotosintesis" en un informe se lee como una falta de ortografía.

**Decisión deliberada:** es determinista, **no usa embeddings**. Agrupar por similitud
semántica uniría también "fotosíntesis" con "proceso fotosintético" — pero fusionar dos
conceptos que en realidad son distintos corrompe la medida **sin dejar rastro**. Una función
pura se prueba entera y se explica en una frase.

---

## 8. El mapa de calor

```
              Semana 1        Semana 2        Semana 3
        ┌───────────────────────────────────────────────────┐
        │  ░░░░░░░░        ▓▓▓▓▓▓▓▓        ████████         │
   temas│  ░ tema ░        ▓ tema ▓        █ tema █         │
        │  ░░░░░░░░        ▓▓▓▓▓▓▓▓        ████████         │
        │      ░░░░            ▓▓▓▓            ████         │
        │  ░ tema ░        ▓ tema ▓        █ tema █         │
        └───────────────────────────────────────────────────┘
          azul = dominado    verde/amarillo    rojo = repasar

   intensidad = 1 - probabilidad_dominio (del BKT)
```

**Dos defectos que solo se vieron mirando una captura, no en las pruebas:**

1. **Los halos se SUMABAN.** El centro salía rojo por acumulación, no porque esos temas
   estuvieran peor. El mapa coloreaba la aglomeración en vez del dominio: mandaba al alumno a
   repasar donde había *muchos temas*, no donde iba flojo. **Corregido con mezcla por máximo.**
2. **Se normalizaba por el valor más alto del propio mapa**, así que el peor tema salía rojo
   **siempre**, aunque el alumno lo llevara bien. **Corregido con escala absoluta**, de modo
   que el mapa puede decir "vas bien en todo" quedándose azul.

Se dibuja acumulando una gaussiana por tema sobre una rejilla de 120×68 (~8.000 celdas) que el
navegador escala con suavizado: el coste **no depende del tamaño en pantalla**, solo del número
de temas. Por eso funciona en un móvil.

---

## 9. Validación del juez contra docentes

Es la pieza que convierte "la IA califica" en un número defendible.

```
   ┌──────────────┐   ┌────────────────────────┐   ┌──────────────────┐
   │ 1. MUESTRA   │   │ 2. CALIFICACIÓN CIEGA  │   │ 3. CONCORDANCIA  │
   │              │   │                        │   │                  │
   │ respuestas   │──►│ el docente ve:         │──►│ kappa ponderada  │
   │ que la IA ya │   │   · la pregunta        │   │ acuerdo exacto   │
   │ calificó,    │   │   · la respuesta       │   │ sesgo con signo  │
   │ elegidas AL  │   │   · el criterio        │   │                  │
   │ AZAR         │   │                        │   │ + la lista de    │
   │              │   │ NO ve la nota de la IA │   │   DESACUERDOS    │
   └──────────────┘   └────────────────────────┘   └──────────────────┘
```

**El control experimental está en el paso 2.** El DTO que viaja al docente **no incluye** la
nota de la IA. No es un olvido: si la viera, tendería a confirmarla —efecto de anclaje— y la
concordancia mediría la sugestión, no el acuerdo.

**Por qué kappa y no "coincidieron el 80 %".** Si el 80 % de las respuestas son correctas, dos
calificadores que siempre dijeran "correcto" coincidirían el 80 % de las veces **sin leer
nada**. Kappa descuenta el acuerdo que el azar ya explica [6].

**Por qué ponderada cuadrática.** La escala es ordinal: confundir un 3 con un 4 no es lo mismo
que confundir un 1 con un 4, y la kappa simple los castiga igual.

```
   Mismo caso, 4 pares (dos aciertos exactos, dos fallos de un punto):

        kappa PONDERADA  = 0,800        kappa SIMPLE = 0,333
        ────────────────────────────────────────────────────
        Esa diferencia ES el argumento de por qué se eligió la ponderada.
```

Otras dos decisiones con consecuencias: el muestreo es **aleatorio** (elegir "los casos
dudosos" mediría algo que no es el funcionamiento normal), y cuando varios docentes califican
el mismo caso **cada par entra por separado** en vez de promediar primero las notas humanas —
promediarlas suavizaría el desacuerdo entre docentes y haría parecer que la IA concuerda más.

La muestra es una **fotografía**, no una referencia viva: se copian el enunciado, la respuesta
y la nota. Si leyera de las tablas originales, cualquier reprocesamiento cambiaría los datos
sobre los que ya calificó un docente y el resultado dejaría de ser reproducible.

---

## 10. Telemetría: qué queda registrado

**175 sentencias de log en 39 archivos**, más una tabla `evento_metrica_ia` para el análisis
posterior. Los logs son para observar mientras se prueba; la telemetría, para el informe.

```
  [SEGMENTADOR] 83 secciones detectadas por VENTANAS
  [RESUMEN]     83 secciones agrupadas en 30 para no pasar de 30 llamadas
  [DEDUP-LOTE]  Reformulación detectada dentro del mismo examen (score=0.87)
  [VERIFICADOR] Veto: propuesto=AVANZADO → aplicado=INTERMEDIO. Motivo: ...
  [RAGRetriever] Contexto recuperado en modo degradado (umbral 0.30)
  [TUTOR-METRICA] Turno registrado: escalón 2, cerrado false
  [SEGURIDAD]   El intento declaraba usuarioId=7 pero el token es del usuario 3
```

**Principio transversal:** la telemetría **nunca** interrumpe la operación. Se escribe en una
transacción propia (`REQUIRES_NEW`) y se traga sus errores. Un fallo de registro no vale una
sesión rota.

---

## 11. Decisiones que se repiten en todo el sistema

Cinco criterios aparecen una y otra vez. Merecen nombrarse porque son lo que da coherencia al
conjunto:

**1. Fallar ruidosamente antes que tener éxito en falso.** El PDF escaneado que devolvía
`exitoso=true` con cero vectores era peor que una excepción: nadie investiga un éxito.

**2. Degradar dejando constancia.** La escalera de umbrales del RAG y la cascada de
segmentación no fallan cuando no encuentran lo ideal: se conforman con menos **y lo registran**.
Eso convierte una limitación en un dato reportable.

**3. Lo que decide algo importante, que sea determinista.** El veto del comité y la
normalización de conceptos no usan el modelo. Son auditables, reproducibles y explicables en
una frase.

**4. No leer lo que no hace falta.** En las escrituras el `usuarioId` del cliente **se ignora**,
no se valida. Validar deja abierta la posibilidad de que el siguiente endpoint lo olvide;
ignorar la elimina. No se puede falsificar lo que no se lee.

**5. La analítica nunca rompe lo académico.** BKT, telemetría y registro de turnos se tragan
sus errores. Guardar el intento del alumno siempre gana.

---

## 12. Referencias: de dónde salió cada técnica

| # | Técnica en el sistema | Fuente | Dónde |
|---|---|---|---|
| **[1]** | Resúmenes jerárquicos por niveles (ETAPA 3.5) | **RAPTOR** — Sarthi, P. et al. (2024). *RAPTOR: Recursive Abstractive Processing for Tree-Organized Retrieval*. **ICLR 2024** | Conferencia de primer nivel en aprendizaje automático |
| **[2]** | Deduplicación en dos etapas; diversidad en selección | **Carbonell, J. & Goldstein, J. (1998).** *The use of MMR, diversity-based reranking...* **SIGIR '98**, 335-336 | Conferencia de referencia en recuperación de información. Fundacional: MMR está integrado hoy en LangChain y LlamaIndex |
| **[2b]** | Confirmación del duplicado en segunda etapa | Práctica de *cross-encoder reranking* — ver p. ej. *Diversity Enhances an LLM's Performance in RAG and Long-context Task* (arXiv:2502.09017, 2025) | Reciente, pero **preprint**: úsese como apoyo, no como afirmación central |
| **[3]** | Diversidad explícita en el prompt de generación | **EduAgentQG** (arXiv:2511.11635, 2025) | Preprint |
| **[4]** | Matiz sobre el valor real del comité | *Rethinking the Bounds of LLM Reasoning: Are Multi-Agent Discussions the Key?* (arXiv:2402.18272) | Preprint |
| **[5]** | Trazado bayesiano del conocimiento | **Corbett, A. & Anderson, J. (1995).** *Knowledge tracing: Modeling the acquisition of procedural knowledge*. **User Modeling and User-Adapted Interaction**, 4(4) | Revista, canónico del área |
| **[6]** | Kappa ponderada cuadrática | **Cohen, J. (1968).** *Weighted kappa*. **Psychological Bulletin**, 70(4) | Revista Q1, canónico |
| **[7]** | Niveles cognitivos de las preguntas | **Anderson, L. & Krathwohl, D. (2001).** *A Taxonomy for Learning, Teaching, and Assessing* | Libro de referencia |
| **[8]** | Ubicación cumulativa por niveles | **Guttman, L. (1944).** *A basis for scaling qualitative data*. **American Sociological Review**, 9(2) | Revista, canónico |
| **[9]** | Evaluación formativa como intervención | **Black, P. & Wiliam, D. (1998).** *Assessment and Classroom Learning*. **Assessment in Education**, 5(1) | Q1 |
| **[10]** | Modelo de retroalimentación | **Hattie, J. & Timperley, H. (2007).** *The Power of Feedback*. **Review of Educational Research**, 77(1) | Q1 |
| **[11]** | Eficacia de sistemas tutores | **VanLehn, K. (2011).** *The Relative Effectiveness of Human Tutoring, Intelligent Tutoring Systems...*. **Educational Psychologist**, 46(4) | Q1 |
| **[12]** | Diseño experimental de referencia | **Kestin, G. et al. (2025).** *AI tutoring outperforms in-class active learning*. **Scientific Reports**, 15, 17458 | Q1 |
| **[13]** | Tutoría socrática con agente conversacional | **Computers & Education**, 241, 105494 (2025). DOI 10.1016/j.compedu.2025.105494 | **Q1** — agente socrático vs. no socrático, n=94 |
| **[14]** | Eficacia de la evaluación con IA en secundaria | **Chen et al. (2025).** *A systematic review and meta-analysis of AI-enabled assessment in language learning*. **Journal of Computer Assisted Learning** | Q1 |
| **[15]** | Por qué NO se construyó un detector de IA | **Liang, W. et al. (2023).** *GPT detectors are biased against non-native English writers*. **Patterns** | Q1 — 61,3 % de falsos positivos en textos no nativos |
| **[16]** | Instrumento de estrategias de estudio | **Román, J. & Gallego, S. (1994).** *ACRA: Escalas de Estrategias de Aprendizaje* | Instrumento original |

### Nota sobre la antigüedad de algunas referencias

Que MMR sea de 1998 o el BKT de 1995 no las hace obsoletas: son **fundacionales**. Citar a
Carbonell para hablar de diversidad en recuperación es como citar a Bayes para hablar de
probabilidad — es el origen del método, no una versión superada. MMR sigue integrado de serie
en las bibliotecas actuales de RAG. Cuando se necesite respaldo reciente, se acompaña con
[2b] o [3], declarando que son preprints.

---

## 13. Lo que NO se ha observado funcionando

Se declara aquí porque en este proyecto la verificación visual ya encontró **cinco** defectos
que las pruebas automáticas no podían ver, y omitirlo daría una impresión falsa de solidez.

| Construido y probado por tipos… | …pero nunca ejecutado |
|---|---|
| Aria socrática | con un alumno real |
| Mapa de calor por semana | con datos reales de BKT |
| Vista del curso plegable | en un navegador con datos |
| Barra de progreso de la ingesta | con un documento grande |
| Ingesta asíncrona y resúmenes | con una obra real |
| Validación del juez | con una muestra calificada |
| Capa de prevención de duplicados | leída por un modelo real |

**Estado verificable:** 147 pruebas automáticas en el backend, 0 fallos. `tsc` limpio y
compilación de producción correcta en el frontend.
