# Generador de preguntas: marcos, revisión y configuración

Cómo se genera y se revisa cada lote de preguntas, y qué se puede ajustar sin tocar código.

## Marcos a los que está atado

| Marco | Qué decide | Dónde |
|---|---|---|
| Taxonomía revisada de Bloom (Anderson y Krathwohl, 2001) | Exigencia cognitiva de la pregunta | `PromptTemplateService` |
| Currículo Nacional (CNEB), Programa de Secundaria | Competencias del área y desempeños del grado | `MarcoCneb`, `resources/cneb/secundaria.json` |
| Directrices de Haladyna (2002) | Calidad de las alternativas | `PromptTemplateService`, `ReactivoOpcionMultipleGuard` |
| Índice de Szigriszt y escala INFLESZ | Legibilidad por ciclo | `PerfilAudiencia`, `ControlDeCalidadReactivos` |

Son tres ejes separados: el CNEB dice **qué se espera del grado**, Bloom **cuánta exigencia** tiene la pregunta y la audiencia **en qué lenguaje** se escribe. El contenido sale solo del material de la semana.

## Flujo

1. `AudienciaService` resuelve el grado del alumno y el área del curso, y arma el perfil con su marco curricular.
2. `PromptTemplateService` genera el prompt. "Crear" solo se pide en preguntas abiertas; en tipos cerrados se pide hasta "Evaluar".
3. El modelo genera las preguntas pedidas más 1 de reserva.
4. `ControlDeCalidadReactivos` hace en paralelo, con un tope de 5 segundos en total:
   - **Verificador** (una llamada por lote): comprueba la respuesta contra el material y estima el nivel de Bloom.
   - **Corrector de estilo**: solo en preguntas bajo el umbral de legibilidad de su ciclo o que delatan la respuesta por su longitud.
5. Se descarta lo que no se sostiene en el material y se recorta al número pedido.

Si se acaba el tiempo o falla la IA, el alumno recibe las preguntas igual. Si el verificador rechaza el lote entero, se conserva: suele ser un fallo del verificador.

El comité de tres críticos sigue en el código, apagado por su latencia.

## Variables de entorno

| Variable | Por defecto | Efecto |
|---|---|---|
| `VERIFICADOR_REACTIVOS` | `true` | Enciende la verificación final |
| `VERIFICADOR_REACTIVOS_MARGEN` | `1` | Preguntas de reserva para reponer las descartadas |
| `CONTROL_CALIDAD_TOPE_MS` | `5000` | Espera máxima por la revisión final |
| `CORRECTOR_ESTILO` | `true` | Enciende el corrector |
| `LEGIBILIDAD_UMBRAL` | `55` | Umbral cuando no se conoce el grado |
| `COMITE_PREGUNTAS` | `false` | Enciende el comité de tres críticos |

## Cómo saber si funciona en producción

- `[RNF-17]`: cuánto tardó cada generación, con la revisión incluida.
- `[VERIFICADOR]`: aparece cuando la verificación alcanzó a terminar. Indica cuántas preguntas se descartaron y cuántas tienen otro nivel estimado.
- Si `[VERIFICADOR]` casi nunca aparece, sube `CONTROL_CALIDAD_TOPE_MS` a 8000.

## Datos del CNEB

`cneb/secundaria.json` se generó del Programa Curricular de Educación Secundaria del Minedu, **versión de junio de 2016**. Falta contrastarlo con la versión aprobada por la R.M. 649-2016-MINEDU. El archivo se puede reemplazar sin cambiar código, con la misma estructura.

Cubre 15 competencias de secundaria que se pueden evaluar con preguntas escritas. Faltan los desempeños de 5.° de "Convive y participa democráticamente". Primaria no tiene marco: ese programa es otro documento.

## Límites conocidos

- El verificador es otro modelo opinando: detecta errores gruesos, pero **no valida** el nivel de Bloom. Para validarlo, un docente debe clasificar una muestra de 30 a 50 preguntas.
- INFLESZ mide sílabas y largo de oración, no si una palabra es conocida para esa edad. Falta una lista de frecuencia de palabras.
- La generación con Gemini real no se ha probado con estos cambios; las pruebas automáticas usan dobles.
