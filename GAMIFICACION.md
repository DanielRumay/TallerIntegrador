# Gamificación de Semantika: qué se hizo y por qué

Respuesta a una observación del docente evaluador: la experiencia del alumno era "muy fría". Este documento resume cada mecánica, la regla que la protege y el fundamento que la respalda.

## Principios que se respetan en todo

1. **La gamificación motiva, no evalúa.** XP, monedas, liga y poderes nunca cambian el nivel de dominio ni la dificultad de las preguntas. Eso lo decide el desempeño (Bloom, comité de IA y verificador). Ver `ProgresoAlumnoService`.
2. **Se compite por esfuerzo, no por nota acumulada.** La liga se reinicia cada lunes.
3. **Privacidad de menores (Ley 29733).** Los compañeros ven apodos, nunca nombres. Los apodos salen de una lista cerrada (animal y adjetivo), así no hay que moderar textos de menores.
4. **Nada de pagar para ganar.** Lo que se compra con monedas es estético o ayuda a aprender. No se puede comprar nota, XP de la liga ni saltarse una evaluación. No hay dinero real.
5. **Los límites se aplican en el servidor**, no en la interfaz: topes, precios y claves únicas viven en el backend.
6. **Accesibilidad.** Se respeta "reducir movimiento" y la voz de Aria se puede silenciar.

## Mecánicas

| Mecánica | Qué hace | Regla clave | Dónde |
|---|---|---|---|
| Niveles | XP total → nivel (25·n·(n−1)); subida celebrada por Aria | Solo actividad, no dominio | `GamificacionService`, `BarraJugador`, `CelebracionNivel` |
| Monedas | +5 por práctica, +3 con 14 o más, +3/+1 con Aria; hitos de racha (7, 30, 100 días) | Tope diario (5 completas, 4 a mitad, luego 0) y esfuerzo mínimo (20 s) | `MonedasService` |
| Libro de movimientos | Premios únicos y gastos con clave idempotente | Restricción única (usuario, clave); compra con bloqueo de fila | `MovimientoMonedas` |
| Liga semanal | Ranking del curso por XP de la semana; podio con premio | Apodos; podio solo si practicaron 3 o más | `GamificacionService`, `LigaSemanal` |
| Tienda | Accesorios de Aria (con rareza) y protector de racha | El protector cubre solo mañana y no crea racha desde cero | `TiendaService`, `Tienda` |
| Contrarreloj | Tiempo por pregunta según palabras, Bloom y tipo; combo; bono | El tiempo agotado no es error; el bono está acotado (10, 5 al día) | `useContrarreloj`, `HudContrarreloj`, `PoderesService` |
| Poderes | +15 s, congelar, 50/50, pista de Aria | Máx. 3 por práctica; la pista sube de precio (10, 20, 40); con ayuda la pregunta vale la mitad | `PoderesService` |
| Pista de Aria | Aria entra caminando en cuatro patas, se sienta y dice la pista en voz alta | La pista nunca contiene la respuesta (se verifica) | `AriaCaminando`, `AriaPista` |
| Jefe de la semana | Batalla cooperativa: cada acierto del salón es un golpe | Vida según el tamaño del salón; cofre solo para quien aportó | `JefeSemanalService`, `JefeSemanal` |
| Estilos de Aria | Colorida (primaria), juvenil (gata tuxedo con skate y mochila, secundaria) y perrito | Gratis; se guarda en el servidor para que el perfil la muestre igual | `Aria`, `AriaCaminando`, `PerfilService` |
| Perfil de juego | Desde la liga se abre el perfil de un compañero: su Aria, nivel, rachas, medallas y colección | Solo compañeros de curso; apodo, nunca nombre ni notas; cada alumno puede ocultarlo; su Aria se duerme sin práctica, nunca se pone triste | `PerfilService`, `PerfilJugador` |
| Clase en vivo | El docente proyecta; los alumnos responden desde el celular | Solo alumnos matriculados; en memoria, sin nota | `ClaseEnVivoService`, `ClaseEnVivoDocente`, `ClaseEnVivoAlumno` |

## Fundamento

- **Teoría de la Autodeterminación** (Deci y Ryan): competencia (niveles y progreso), autonomía (elegir modo, poderes y accesorios), pertenencia (liga, jefe cooperativo y clase en vivo).
- **Octalysis** (Yu-kai Chou): se priorizan impulsos "positivos" (logro, creatividad, posesión, influencia social) y se limitan los "negativos" (miedo a perder la racha), por eso existe el protector.
- **Duolingo**: rachas, ligas semanales que se reinician y mascota con personalidad. Sus pruebas A/B muestran que destacar la racha mejora la retención.
- **Classcraft**: batallas contra un jefe como mecánica cooperativa; sus estudios destacan el trabajo en equipo.
- **Abuso de pistas** (Baker y otros): pedir ayuda sin leer es la forma más común de "jugar con el sistema". Por eso las pistas tienen precio creciente y tope.
- **Presión de tiempo**: la evidencia es mixta; por eso el contrarreloj es opcional y no castiga.

## Pruebas

| Prueba | Casos |
|---|---|
| `GamificacionServiceTest` | 9 |
| `PerfilServiceTest` | 5 |
| `MonedasServiceTest` | 9 |
| `MarcoCnebTest`, `PerfilAudienciaTest`, `PromptAudienciaTest` | ver `GENERADOR_PREGUNTAS.md` |
| `TiendaServiceTest` | 5 |
| `PoderesServiceTest` | 10 |
| `JefeSemanalServiceTest` | 5 |
| `ClaseEnVivoServiceTest` | 8 |

Todas en verde. Además se probó de punta a punta en local con datos ficticios: compra, poderes, pista, bono, jefe y una clase en vivo con un docente y dos alumnos.

## Seguridad de las mecánicas

Las respuestas y la nota viven en el servidor, en la tabla `ronda_custodiada` (`CustodiaRondaService`).

- Al alumno solo le llegan enunciado, alternativas, concepto e id de ronda. La solución de una pregunta viaja junto con su calificación.
- Rutas: `POST /rondas/{id}/responder`, `/comodin` y `/finalizar`. Ninguna acepta notas, aciertos ni contadores.
- Se retiraron `POST /intentos/guardar`, `POST /gamificacion/poderes` y `POST /gamificacion/partidas/{id}/bono`. Un alumno ya no puede usar `/agent-judge/evaluar-respuesta` ni `/archivos/generar-evaluacion`.
- Cada pregunta se califica una vez y cada ronda se cierra una vez. En preguntas cerradas decide la comparación con la alternativa correcta, no el modelo.
- El servidor elige qué alternativas quita el 50/50, aplica la mitad del puntaje y calcula el bono. Una respuesta cuenta como rápida con el tiempo medido en el servidor: límite de la pregunta, más lo que sumen los comodines, más 4 s de tolerancia.
- La cantidad queda entre 5 y 10 preguntas y el material debe ser de un curso del alumno.
- En el tutor, la pregunta, el escalón y la puntuación salen de la sesión del servidor. La nota se calcula sobre el total de preguntas, con un mínimo de 5.
- Las rondas vencen a las 48 horas y se borran a los 3 días.

Frontend y backend deben desplegarse juntos, porque el frontend anterior usa las rutas retiradas.

Lo que no se puede impedir desde el sistema: que el alumno consulte otra fuente mientras responde, o que abandone una práctica que le va mal. Una práctica abandonada no se registra.

## Apariencia

La animación de Aria, sus estilos y los gestos de cada comodín se explican en `src/components/gamificacion/README.md` del frontend.

## Despliegue

- Tablas y columnas nuevas (`movimiento_monedas`, `usuario.apodo`, `usuario.aria_equipado`, `usuario.aria_variante`, `usuario.perfil_visible`): las crea Hibernate al arrancar (`ddl-auto=update`).
- La clase en vivo vive en memoria: con un solo servidor funciona tal cual; si algún día hubiera varias réplicas, habría que moverla a Redis o a la base de datos.
- El frontend carga la fuente *Baloo 2* desde Google Fonts.

## Pendiente

- Decidir si la liga muestra apodos o el nombre con la inicial del apellido (con consentimiento).
- Interruptor del docente para apagar la liga en su curso.
- Temporadas por bimestre y artículos de tiempo limitado.
- Panel del docente con saldo del salón y alertas de ganancias anormales.
- Repaso espaciado diario y modos por grado (primaria y secundaria).
