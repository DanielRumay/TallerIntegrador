package com.example.tallerintegrador.service.spike;

import com.example.tallerintegrador.agents.preguntas.ComiteDePreguntasService;
import com.example.tallerintegrador.agents.preguntas.CorrectorEstiloAgent;
import com.example.tallerintegrador.agents.preguntas.ReactivoReescrito;
import com.example.tallerintegrador.agents.preguntas.VerificadorDeLote;
import com.example.tallerintegrador.service.metricas.MetricasEstandarizadasService;
import com.example.tallerintegrador.service.ia.PerfilAudiencia;
import com.example.tallerintegrador.service.util.CorreccionEstiloGuard;
import com.example.tallerintegrador.service.util.ReactivoDeteccionErroresGuard;
import com.example.tallerintegrador.service.util.ReactivoOpcionMultipleGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Control de calidad de los reactivos generados, entre la generacion y el alumno.
 *
 * QUE HACE, EN ORDEN:
 *   1. Revisa el lote con el comite de tres criticos, en una llamada por critico.
 *   2. Si un reactivo fue rechazado SOLO por forma, lo manda al corrector de estilo, verifica
 *      la reescritura con guardas deterministas y lo recupera si pasa.
 *   3. Descarta lo que sigue rechazado.
 *   4. Ordena lo que queda por calidad: primero la puntuacion del comite, despues la
 *      legibilidad.
 *
 * POR QUE ESTE ORDEN. El comite es la senal (dice que esta mal y como arreglarlo) y el
 * corrector es la reparacion. Al reves, el corrector adivinaria: pagaria por pulir reactivos
 * que nadie objeto y maquillaria reactivos que deben morir por contenido.
 *
 * DESACTIVADO POR DEFECTO. Conectar el comite anade llamadas al modelo, y hay dos requisitos en
 * juego: la primera pregunta en menos de diez segundos y la cuota de IA con un aula entera. Se
 * enciende con COMITE_PREGUNTAS=true y se mide antes de dejarlo fijo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ControlDeCalidadReactivos {

    private final ComiteDePreguntasService comite;
    private final CorrectorEstiloAgent correctorEstilo;
    private final MetricasEstandarizadasService metricas;
    private final VerificadorDeLote verificador;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.tallerintegrador.service.util.VocabularioFrecuente vocabulario;

    @Value("${app.comite-preguntas.habilitado:false}")
    private boolean comiteHabilitado;

    @Value("${app.comite-preguntas.max-lote:10}")
    private int maxLote;

    @Value("${app.corrector-estilo.habilitado:true}")
    private boolean correctorHabilitado;

    @Value("${app.verificador-reactivos.habilitado:false}")
    private boolean verificadorHabilitado;

    @Value("${app.verificador-reactivos.margen:1}")
    private int margenVerificador;

    /**
     * Lo maximo que el alumno espera por la revision final, en paralelo y sumando todo. Si se
     * acaba, recibe lo que haya: el verificador y el corrector mejoran, no condicionan. Sin este
     * tope, un modelo lento con sus reintentos podia retenerlo dos minutos.
     */
    @Value("${app.control-calidad.tope-ms:5000}")
    private long topeRevisionMs;

    /**
     * Bajo este indice de Szigriszt, el enunciado se manda a corregir aunque nadie lo objete.
     * Solo rige cuando no se conoce el grado: si se conoce, manda el umbral de su ciclo
     * (PerfilAudiencia.umbralInflesz).
     */
    @Value("${app.legibilidad.umbral:40}")
    private double umbralLegibilidad;

    /**
     * Si el control esta activo, el lote NO puede mostrarse al alumno mientras se genera.
     *
     * POR QUE LO NECESITA QUIEN LLAMA. El camino de practica emite las preguntas por streaming
     * conforme el modelo las escribe, y el alumno puede empezar a responder antes de que
     * termine. Pero este control descarta, reescribe y reordena el lote AL FINAL. Si las
     * preguntas ya estan en pantalla, reordenarlas mueve las respuestas ya dadas a otra
     * pregunta, porque el cliente las guarda por numero de diapositiva. Con el control activo,
     * quien emite debe callarse hasta tener el lote definitivo.
     */
    public boolean estaActivo() {
        // El corrector cuenta: aunque el comite este apagado, el reescribe los enunciados DESPUES
        // de generarlos. Si se emite incremental, el alumno ve la pregunta original y un segundo
        // despues le cambia el texto en pantalla. Cualquiera de los dos que este encendido obliga
        // a callarse hasta tener el lote definitivo.
        return comiteHabilitado || correctorHabilitado || verificadorHabilitado;
    }

    @Value("${app.comite-preguntas.margen:3}")
    private int margenGeneracion;

    /**
     * Cuantos reactivos pedirle al modelo para poder entregar los que el alumno pidio.
     *
     * POR QUE HACE FALTA UN MARGEN. El comite descarta, y con razon: en una medicion real, de
     * cinco reactivos visuales aprobo dos. Si se le piden al modelo exactamente cinco, el alumno
     * recibe dos, y ademas su nota pasa a calcularse sobre dos preguntas, con lo que solo puede
     * sacar 0, 10 o 20. Pidiendo de mas, el descarte se absorbe y la evaluacion conserva el
     * tamano que el docente eligio.
     *
     * Se recorta despues al numero pedido, y como la lista ya viene ordenada por calidad, lo que
     * sobra es siempre lo peor del lote.
     */
    public int cantidadAGenerar(int pedidas) {
        if (comiteHabilitado) return Math.min(pedidas + margenGeneracion, maxLote);
        return verificadorHabilitado ? pedidas + margenVerificador : pedidas;
    }

    /**
     * Cuantos conceptos DISTINTOS cubre el lote, sobre el total de reactivos.
     *
     * POR QUE SE MIDE. El prompt exige que cada reactivo evalue un subtema distinto, pero eso
     * es una instruccion, no una garantia. Si cinco reactivos comparten concepto, la evaluacion
     * mide una sola cosa cinco veces y el alumno puede aprobar o suspender por un unico subtema.
     * Aqui no se descarta nada: quitar reactivos encogeria la evaluacion y la nota perderia
     * granularidad. Se mide, se registra y queda como indicador.
     *
     * Normaliza a minusculas y sin acentos para que "Signo linguistico" y "signo lingüístico"
     * cuenten como el mismo concepto, que es justo la repeticion disfrazada que interesa cazar.
     */
    public double coberturaDeConceptos(List<Object> preguntas) {
        if (preguntas == null || preguntas.isEmpty()) return 0.0;
        java.util.Set<String> distintos = new java.util.LinkedHashSet<>();
        int conConcepto = 0;
        for (Object p : preguntas) {
            if (!(p instanceof Map<?, ?> m)) continue;
            String c = texto((Map<String, Object>) m, "concepto");
            if (c == null || c.isBlank()) continue;
            conConcepto++;
            String norma = java.text.Normalizer.normalize(c.trim().toLowerCase(), java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "").replaceAll("\\s+", " ");
            distintos.add(norma);
        }
        return conConcepto == 0 ? 0.0 : (double) distintos.size() / conConcepto;
    }

    /** Una correccion por hacer: el reactivo, la instruccion y que pasa si el corrector falla. */
    private record CorreccionPendiente(Map<String, Object> reactivo, String instruccion,
                                       boolean descartarSiFalla) {}

    /** Resultado del control, para telemetria y para las metricas del panel. */
    public record Resumen(
            int recibidos,
            int aprobados,
            int corregidos,
            int descartados,
            double legibilidadAntes,
            double legibilidadDespues,
            String nivelInfleszDespues
    ) {}

    /**
     * Aplica el control sobre la lista de preguntas, modificandola en el sitio.
     *
     * @param preguntas   lista de mapas tal como los devuelve el generador; se modifica
     * @param tipoPregunta tipo del reactivo (para elegir la guarda determinista)
     * @param contexto     material de la semana; si viene vacio se omite el critico de contenido
     * @param nivelBloom   nivel solicitado al generador
     */
    public Resumen aplicar(List<Object> preguntas, String tipoPregunta, String contexto, String nivelBloom) {
        return aplicar(preguntas, tipoPregunta, contexto, nivelBloom, PerfilAudiencia.GENERAL);
    }

    /**
     * @param audiencia para quien se escribe: fija el umbral de legibilidad y el lenguaje que
     *                  pide el corrector. Ver PerfilAudiencia.
     */
    @SuppressWarnings("unchecked")
    public Resumen aplicar(List<Object> preguntas, String tipoPregunta, String contexto, String nivelBloom,
                           PerfilAudiencia audiencia) {
        PerfilAudiencia a = audiencia == null ? PerfilAudiencia.GENERAL : audiencia;
        double umbral = a.umbralInfleszO(umbralLegibilidad);
        if (preguntas == null || preguntas.isEmpty()) {
            return new Resumen(0, 0, 0, 0, 0, 0, "sin datos");
        }
        int recibidos = preguntas.size();
        double legibilidadAntes = legibilidadMedia(preguntas);

        if (!comiteHabilitado) {
            if (correctorHabilitado || verificadorHabilitado) {
                return sinComite(preguntas, tipoPregunta, contexto, nivelBloom, recibidos, legibilidadAntes, a, umbral);
            }
            log.debug("[CALIDAD] Comite y corrector desactivados");
            return new Resumen(recibidos, recibidos, 0, 0, legibilidadAntes, legibilidadAntes,
                    metricas.nivelInflesz(legibilidadAntes));
        }
        if (recibidos > maxLote) {
            log.warn("[CALIDAD] Lote de {} reactivos supera el maximo de {}: se revisan los primeros",
                    recibidos, maxLote);
        }

        List<Map<String, Object>> mapas = new ArrayList<>();
        for (Object p : preguntas) {
            if (p instanceof Map<?, ?> m) mapas.add((Map<String, Object>) m);
        }
        List<Map<String, Object>> lote = mapas.subList(0, Math.min(mapas.size(), maxLote));

        List<ComiteDePreguntasService.ReactivoARevisar> aRevisar = lote.stream()
                .map(m -> new ComiteDePreguntasService.ReactivoARevisar(
                        texto(m, "enunciado"), texto(m, "respuesta_correcta"), opciones(m),
                        texto(m, "prompt_imagen")))
                .toList();

        List<ComiteDePreguntasService.Dictamen> dictamenes;
        try {
            dictamenes = comite.revisarLote(aRevisar, contexto, nivelBloom, a.instruccion(), null);
        } catch (Exception e) {
            // El control de calidad nunca deja al alumno sin evaluacion: si el comite falla
            // entero, las preguntas siguen su camino como antes de conectarlo.
            log.warn("[CALIDAD] El comite fallo; se entregan las preguntas sin revisar: {}", e.getMessage());
            return new Resumen(recibidos, recibidos, 0, 0, legibilidadAntes, legibilidadAntes,
                    metricas.nivelInflesz(legibilidadAntes));
        }

        // PRIMERA PASADA: decidir. No se llama al corrector todavia.
        //
        // POR QUE EN DOS PASADAS. Antes esto era un solo bucle que corregia sobre la marcha, y
        // cada correccion es una llamada al modelo. Con tres reactivos a corregir eran tres
        // viajes EN FILA: una medicion real dio 70.8 s de total contra 4.8 s de generacion.
        // Ahora se decide primero, se corrige todo en paralelo y se aplica despues.
        List<Map<String, Object>> aDescartar = new ArrayList<>();
        List<CorreccionPendiente> pendientes = new ArrayList<>();

        for (int i = 0; i < dictamenes.size() && i < lote.size(); i++) {
            ComiteDePreguntasService.Dictamen d = dictamenes.get(i);
            Map<String, Object> reactivo = lote.get(i);
            reactivo.put("puntuacion_comite", promedio(d.puntuaciones()));

            boolean legibilidadBaja = metricas.calcularPerspicuidadSzigriszt(texto(reactivo, "enunciado"))
                    < umbral;

            if (d.aprobada() && !legibilidadBaja) continue;

            if (d.aprobada() && legibilidadBaja) {
                // Aprobada: se queda con o sin correccion, asi que no entra en aDescartar.
                pendientes.add(new CorreccionPendiente(reactivo,
                        "El enunciado resulta dificil de leer para " + a.descripcion() + ". "
                        + "Simplificalo sin cambiar lo que mide.", false));
                continue;
            }

            if (soloEsDeForma(d) && correctorHabilitado) {
                // Rechazada solo por forma: si la correccion falla, se descarta.
                pendientes.add(new CorreccionPendiente(reactivo, d.instruccionesDeCorreccion(), true));
                continue;
            }
            aDescartar.add(reactivo);
        }

        // SEGUNDA PASADA: corregir en paralelo y aplicar.
        int corregidos = 0;
        if (!pendientes.isEmpty()) {
            List<CompletableFuture<Boolean>> tareas = pendientes.stream()
                    .map(pc -> CompletableFuture.supplyAsync(
                            () -> intentarCorregir(pc.reactivo(), tipoPregunta, pc.instruccion())))
                    .toList();

            for (int i = 0; i < pendientes.size(); i++) {
                CorreccionPendiente pc = pendientes.get(i);
                boolean ok;
                try {
                    ok = tareas.get(i).join();
                } catch (Exception e) {
                    log.warn("[CALIDAD] La correccion no termino: {}", e.getMessage());
                    ok = false;
                }
                if (ok) {
                    corregidos++;
                } else if (pc.descartarSiFalla()) {
                    aDescartar.add(pc.reactivo());
                }
            }
        }

        preguntas.removeIf(p -> p instanceof Map<?, ?> m && aDescartar.contains(m));
        ordenarPorCalidad(preguntas);

        double legibilidadDespues = legibilidadMedia(preguntas);
        log.info("[COBERTURA] {} conceptos distintos por reactivo entregado",
                String.format("%.2f", coberturaDeConceptos(preguntas)));
        Resumen resumen = new Resumen(recibidos, preguntas.size(), corregidos, aDescartar.size(),
                legibilidadAntes, legibilidadDespues, metricas.nivelInflesz(legibilidadDespues));
        log.info("[CALIDAD] {} recibidos · {} entregados · {} corregidos · {} descartados · "
                        + "legibilidad {} -> {} ({})",
                resumen.recibidos(), resumen.aprobados(), resumen.corregidos(), resumen.descartados(),
                resumen.legibilidadAntes(), resumen.legibilidadDespues(), resumen.nivelInfleszDespues());
        return resumen;
    }

    /**
     * Un rechazo es "solo de forma" cuando el critico de forma puntuo por debajo del minimo y
     * los otros dos aprobaron. Si contenido o pedagogico objetaron, se regenera: reescribir la
     * redaccion de un reactivo cuya respuesta esta mal es maquillarlo.
     */
    private boolean soloEsDeForma(ComiteDePreguntasService.Dictamen d) {
        Map<String, Integer> p = d.puntuaciones();
        if (p.containsKey("estructura")) return true; // rechazo determinista por referencia
        Integer forma = p.get("forma");
        if (forma == null || forma >= 3) return false;
        for (Map.Entry<String, Integer> e : p.entrySet()) {
            if (e.getKey().equals("forma")) continue;
            if (e.getValue() != null && e.getValue() >= 0 && e.getValue() < 3) return false;
        }
        return true;
    }

    /** Pide la reescritura, la verifica y solo entonces la aplica. */
    /** Instruccion fija para el modo sin comite: simplificar sin tocar lo que el reactivo mide. */
    /** Cuando la correcta se delata por larga: no se toca el contenido, se emparejan. */
    private static final String INSTRUCCION_LONGITUD = """
            La alternativa correcta es bastante mas larga que las demas y eso la delata: el
            alumno puede acertar eligiendo la mas extensa sin conocer el tema.

            EMPAREJA LA EXTENSION DE LAS CUATRO ALTERNATIVAS. No cambies cual es la correcta ni
            lo que dice cada una: desarrolla los distractores con el mismo grado de detalle y
            precision que la correcta, o condensa la correcta sin perder lo que la hace correcta.
            Los distractores deben seguir siendo claramente incorrectos para quien domina el
            tema, pero plausibles para quien no.

            El enunciado no se toca salvo que tenga un problema de redaccion evidente.""";

    private static final String INSTRUCCION_CLARIDAD = """
            Mejora la REDACCION del enunciado para que %s
            entienda sin esfuerzo QUE se le esta pidiendo.

            EL OBJETIVO NO ES SIMPLIFICAR, ES ACLARAR. No busques un enunciado mas corto ni mas
            facil: busca uno mejor escrito. Si hace falta ANADIR palabras para que la tarea quede
            inequivoca, anadelas; un enunciado mas largo y claro es mejor que uno breve y ambiguo.

            QUE SI DEBES ARREGLAR:
              - Sintaxis enredada, subordinadas encadenadas y orden confuso de las ideas.
              - Ambiguedad sobre que se pide exactamente.
              - Jerga innecesaria: si un termino tecnico NO es el contenido evaluado, sustituyelo
                por su equivalente comun.
              - Palabras poco frecuentes para esa edad: si NO son el contenido evaluado, cambialas
                por otras de uso comun.

            QUE NO PUEDES TOCAR:
              - El VERBO COGNITIVO y el marco de la pregunta. Si pide analizar, comparar,
                justificar, explicar por que o plantear una hipotesis, la version reescrita debe
                seguir pidiendo exactamente eso, con esa misma exigencia.
              - La terminologia que ES el contenido evaluado. Si el reactivo mide si el alumno
                conoce 'participio' o 'perifrasis verbal', esas palabras se quedan: cambiarlas
                por un sinonimo coloquial destruye lo que la pregunta mide.
              - La respuesta correcta, las alternativas, las negaciones, los cuantificadores y
                las cifras.

            Convertir '¿que hipotesis explica mejor...?' en '¿para que sirve...?' NO es aclarar:
            es rebajar la pregunta de Analizar a Comprender, y esta terminantemente prohibido.

            Si el enunciado ya esta bien redactado, devuelvelo TAL CUAL sin cambios.""";

    /**
     * Simplificacion de todos los reactivos, sin comite que los juzgue.
     *
     * QUE ES ESTO. Es simplificacion automatica de texto (automatic text simplification) con
     * preservacion de significado: se reescribe para que se lea mejor y una guarda determinista
     * verifica que no se haya alterado lo que el reactivo mide. La bibliografia reciente sobre
     * simplificacion con modelos de lenguaje evalua justo ese par, legibilidad y fidelidad, y
     * recomienda revision humana posterior; aqui la guarda cubre la fidelidad y el docente la
     * revision.
     *
     * SOLO LO QUE LO NECESITA. Se corrige lo que queda bajo el umbral de legibilidad de su ciclo
     * o lo que delata la respuesta por su longitud: una llamada por pregunta ya legible era
     * tiempo de espera a cambio de nada.
     *
     * DIFERENCIA CON EL MODO CON COMITE. El estilo nunca descarta: si la reescritura no pasa la
     * guarda, se conserva el original. Solo descarta el verificador, y solo si la respuesta
     * marcada no se sostiene en el material. Corre en paralelo con las correcciones.
     */
    private Resumen sinComite(List<Object> preguntas, String tipoPregunta, String contexto, String nivelBloom,
                              int recibidos, double legibilidadAntes,
                              PerfilAudiencia audiencia, double umbral) {
        String instruccionClaridad = INSTRUCCION_CLARIDAD.formatted(audiencia.descripcion());
        long bajoUmbralAntes = bajoUmbral(preguntas, umbral);
        List<Map<String, Object>> reactivos = new ArrayList<>();
        for (Object p : preguntas) {
            if (p instanceof Map<?, ?> m) reactivos.add((Map<String, Object>) m);
        }

        CompletableFuture<Map<Integer, VerificadorDeLote.VerificacionDeReactivo>> verificacion =
                verificadorHabilitado && contexto != null && !contexto.isBlank()
                        ? conTope(CompletableFuture.supplyAsync(() -> verificar(reactivos, contexto)), Map.of())
                        : CompletableFuture.completedFuture(Map.of());
        AtomicBoolean revisionCerrada = new AtomicBoolean(false);

        // La instruccion depende del defecto: si la correcta se delata por su longitud, lo que
        // hay que arreglar son las alternativas, no el enunciado.
        List<CompletableFuture<Boolean>> tareas = reactivos.stream()
                .filter(r -> correctorHabilitado && necesitaCorreccion(r, umbral, audiencia, contexto))
                .map(r -> conTope(CompletableFuture.supplyAsync(() -> intentarCorregir(r, tipoPregunta,
                        ReactivoOpcionMultipleGuard.respuestaSeDelataPorLongitud(r)
                                ? INSTRUCCION_LONGITUD
                                : conVocabulario(instruccionClaridad, r, audiencia, contexto), revisionCerrada)), Boolean.FALSE))
                .toList();

        int corregidos = 0;
        for (CompletableFuture<Boolean> tarea : tareas) {
            try {
                if (tarea.join()) corregidos++;
            } catch (Exception e) {
                log.warn("[CALIDAD] Una correccion de estilo no termino: {}", e.getMessage());
            }
        }

        Map<Integer, VerificadorDeLote.VerificacionDeReactivo> verificaciones = verificacion.join();
        // Una correccion que llegue despues del tope ya no puede tocar lo que se esta entregando.
        synchronized (revisionCerrada) {
            revisionCerrada.set(true);
        }
        int descartados = aplicarVerificacion(preguntas, reactivos, verificaciones, nivelBloom);

        double legibilidadDespues = legibilidadMedia(preguntas);
        log.info("[COBERTURA] {} conceptos distintos por reactivo entregado",
                String.format("%.2f", coberturaDeConceptos(preguntas)));
        log.info("[CALIDAD] Solo corrector · {} reactivos · {} reescritos · legibilidad {} -> {} ({}) · "
                        + "ciclo {} umbral {}: {} -> {} bajo el umbral",
                recibidos, corregidos, legibilidadAntes, legibilidadDespues,
                metricas.nivelInflesz(legibilidadDespues), audiencia.ciclo() == null ? "?" : audiencia.ciclo(),
                umbral, bajoUmbralAntes, bajoUmbral(preguntas, umbral));
        return new Resumen(recibidos, recibidos - descartados, corregidos, descartados,
                legibilidadAntes, legibilidadDespues, metricas.nivelInflesz(legibilidadDespues));
    }

    private <V> CompletableFuture<V> conTope(CompletableFuture<V> tarea, V siSeAcaba) {
        return topeRevisionMs > 0 ? tarea.completeOnTimeout(siSeAcaba, topeRevisionMs, TimeUnit.MILLISECONDS) : tarea;
    }

    private boolean necesitaCorreccion(Map<String, Object> reactivo, double umbral, PerfilAudiencia audiencia, String contexto) {
        return ReactivoOpcionMultipleGuard.respuestaSeDelataPorLongitud(reactivo)
                || metricas.calcularPerspicuidadSzigriszt(texto(reactivo, "enunciado")) < umbral
                || alternativasDificiles(reactivo, umbral)
                || rarasParaLaEdad(reactivo, audiencia, contexto).size() >= (audiencia.secundaria() ? 2 : 1);
    }

    /** Con pocas palabras el indice no dice nada: solo se miden alternativas con texto suficiente. */
    private boolean alternativasDificiles(Map<String, Object> reactivo, double umbral) {
        if (esRubrica(reactivo, null)) return false;
        String unidas = String.join(". ", opciones(reactivo));
        return unidas.split("\\s+").length >= PALABRAS_MINIMAS_ALTERNATIVAS
                && metricas.calcularPerspicuidadSzigriszt(unidas) < umbral;
    }

    private List<String> rarasParaLaEdad(Map<String, Object> reactivo, PerfilAudiencia audiencia, String contexto) {
        if (vocabulario == null) return List.of();
        String alternativas = esRubrica(reactivo, null) ? "" : " " + String.join(". ", opciones(reactivo));
        return vocabulario.rarasPara(texto(reactivo, "enunciado") + alternativas, audiencia.ciclo(), contexto);
    }

    private String conVocabulario(String instruccion, Map<String, Object> reactivo, PerfilAudiencia audiencia, String contexto) {
        List<String> raras = rarasParaLaEdad(reactivo, audiencia, contexto);
        return raras.isEmpty() ? instruccion : instruccion
                + "\n\nEstas palabras pueden ser dificiles para esa edad: " + String.join(", ", raras)
                + ". Cambialas por otras de uso comun sin alterar lo que la pregunta mide.";
    }

    /** En una pregunta abierta este campo es el criterio de calificacion, no alternativas. */
    private boolean esRubrica(Map<String, Object> reactivo, String tipoPregunta) {
        if ("ABIERTA".equalsIgnoreCase(tipoPregunta)) return true;
        List<String> lista = opciones(reactivo);
        return lista.size() == 1 || (!lista.isEmpty() && lista.get(0).trim().matches("(?iu)^r[uú]brica\\s*:.*"));
    }

    /** Material que recibe el verificador: alcanza los fragmentos recuperados para la generacion. */
    private static final int MAX_MATERIAL_VERIFICADOR = 20_000;
    private static final int PALABRAS_MINIMAS_ALTERNATIVAS = 12;

    /** Si el verificador falla, se entrega sin verificar: es mejor que dejar al alumno sin evaluacion. */
    private Map<Integer, VerificadorDeLote.VerificacionDeReactivo> verificar(List<Map<String, Object>> reactivos, String contexto) {
        StringBuilder listado = new StringBuilder("MATERIAL:\n")
                .append(contexto, 0, Math.min(contexto.length(), MAX_MATERIAL_VERIFICADOR)).append("\n\nREACTIVOS:\n");
        for (int i = 0; i < reactivos.size(); i++) {
            Map<String, Object> r = reactivos.get(i);
            listado.append("[id ").append(i + 1).append("] ").append(texto(r, "enunciado"))
                    .append("\nALTERNATIVAS: ").append(String.join(" | ", opciones(r)))
                    .append("\nRESPUESTA MARCADA: ").append(texto(r, "respuesta_correcta")).append("\n\n");
        }
        try {
            Map<Integer, VerificadorDeLote.VerificacionDeReactivo> porId = new LinkedHashMap<>();
            for (VerificadorDeLote.VerificacionDeReactivo v : verificador.verificar(listado.toString()).content().verificaciones()) {
                porId.put(v.id(), v);
            }
            return porId;
        } catch (Exception e) {
            log.warn("[VERIFICADOR] No respondio; el lote se entrega sin verificar: {}", e.getMessage());
            return Map.of();
        }
    }

    private int aplicarVerificacion(List<Object> preguntas, List<Map<String, Object>> reactivos,
                                    Map<Integer, VerificadorDeLote.VerificacionDeReactivo> verificaciones, String nivelBloom) {
        if (verificaciones.isEmpty()) return 0;
        List<Map<String, Object>> sinSustento = new ArrayList<>();
        int nivelDistinto = 0;
        for (int i = 0; i < reactivos.size(); i++) {
            VerificadorDeLote.VerificacionDeReactivo v = verificaciones.get(i + 1);
            if (v == null) continue;
            reactivos.get(i).put("nivel_bloom_estimado", v.nivelBloomEstimado());
            if (nivelBloom != null && !nivelBloom.equalsIgnoreCase(String.valueOf(v.nivelBloomEstimado()))) nivelDistinto++;
            if (!v.respuestaSustentada()) {
                log.info("[VERIFICADOR] Reactivo descartado: la respuesta no se sostiene en el material. Evidencia: {}", v.evidencia());
                sinSustento.add(reactivos.get(i));
            }
        }
        log.info("[VERIFICADOR] {} reactivos · nivel solicitado {} · {} con otro nivel estimado · {} sin sustento",
                reactivos.size(), nivelBloom, nivelDistinto, sinSustento.size());
        // Rechazar el lote entero suele ser un fallo del verificador, no de todas las preguntas.
        if (sinSustento.size() >= preguntas.size()) {
            log.warn("[VERIFICADOR] Rechazo todo el lote: se conserva para no dejar al alumno sin evaluacion");
            return 0;
        }
        preguntas.removeAll(sinSustento);
        return sinSustento.size();
    }

    private boolean intentarCorregir(Map<String, Object> reactivo, String tipoPregunta, String instruccion) {
        return intentarCorregir(reactivo, tipoPregunta, instruccion, new AtomicBoolean(false));
    }

    private boolean intentarCorregir(Map<String, Object> reactivo, String tipoPregunta, String instruccion,
                                     AtomicBoolean revisionCerrada) {
        String enunciado = texto(reactivo, "enunciado");
        // El corrector no debe ver la rubrica: reescribirla cambiaria el criterio de la nota.
        List<String> opciones = esRubrica(reactivo, tipoPregunta) ? List.of() : opciones(reactivo);
        String respuesta = texto(reactivo, "respuesta_correcta");

        ReactivoReescrito reescrito;
        try {
            reescrito = correctorEstilo.corregir(
                    "INSTRUCCION DE CORRECCION:\n" + (instruccion == null ? "Mejora la redaccion." : instruccion)
                    + "\n\nENUNCIADO:\n" + enunciado
                    + "\n\nALTERNATIVAS:\n" + (opciones.isEmpty() ? "(sin alternativas)" : String.join(" | ", opciones))
                    + "\n\nRESPUESTA CORRECTA (no la cambies):\n" + respuesta).content();
        } catch (Exception e) {
            log.warn("[CALIDAD] El corrector de estilo no respondio: {}", e.getMessage());
            return false;
        }

        CorreccionEstiloGuard.Veredicto veredicto = CorreccionEstiloGuard.revisar(
                enunciado, opciones, respuesta, reescrito.enunciado(), reescrito.opciones());
        if (!veredicto.valido()) {
            log.info("[CALIDAD] Correccion descartada: {}", veredicto.motivo());
            return false;
        }

        Map<String, Object> copia = new LinkedHashMap<>(reactivo);
        copia.put("enunciado", reescrito.enunciado());
        if (!opciones.isEmpty() && reescrito.opciones() != null && !reescrito.opciones().isEmpty()) {
            copia.put("opciones_o_respuesta", reescrito.opciones());
        }
        if (!pasaGuardasDeTipo(copia, tipoPregunta)) {
            log.info("[CALIDAD] Correccion descartada: no pasa la guarda de {}", tipoPregunta);
            return false;
        }

        synchronized (revisionCerrada) {
            if (revisionCerrada.get()) return false;
            reactivo.putAll(copia);
            reactivo.put("correccion_estilo", reescrito.cambios());
        }
        log.info("[CALIDAD] Reactivo recuperado por el corrector: {}", reescrito.cambios());
        return true;
    }

    private boolean pasaGuardasDeTipo(Map<String, Object> reactivo, String tipoPregunta) {
        if ("DETECCION_ERRORES".equalsIgnoreCase(tipoPregunta)) {
            return ReactivoDeteccionErroresGuard.revisar(reactivo).valido();
        }
        if ("OPCION_MULTIPLE".equalsIgnoreCase(tipoPregunta)) {
            return ReactivoOpcionMultipleGuard.revisar(reactivo).valido();
        }
        return true;
    }

    /** Mejor puntuacion del comite primero; a igualdad, el enunciado mas legible. */
    @SuppressWarnings("unchecked")
    private void ordenarPorCalidad(List<Object> preguntas) {
        List<Object> ordenadas = preguntas.stream()
                .sorted(Comparator
                        .comparingDouble((Object p) -> p instanceof Map<?, ?> m
                                ? -numero(((Map<String, Object>) m).get("puntuacion_comite"))
                                : 0)
                        .thenComparingDouble(p -> p instanceof Map<?, ?> m
                                ? -metricas.calcularPerspicuidadSzigriszt(texto((Map<String, Object>) m, "enunciado"))
                                : 0))
                .toList();
        preguntas.clear();
        preguntas.addAll(ordenadas);
    }

    @SuppressWarnings("unchecked")
    private long bajoUmbral(List<Object> preguntas, double umbral) {
        return preguntas.stream()
                .filter(p -> p instanceof Map<?, ?>)
                .filter(p -> metricas.calcularPerspicuidadSzigriszt(texto((Map<String, Object>) p, "enunciado")) < umbral)
                .count();
    }

    @SuppressWarnings("unchecked")
    private double legibilidadMedia(List<Object> preguntas) {
        return preguntas.stream()
                .filter(p -> p instanceof Map<?, ?>)
                .mapToDouble(p -> metricas.calcularPerspicuidadSzigriszt(texto((Map<String, Object>) p, "enunciado")))
                .average().orElse(0.0);
    }

    private double promedio(Map<String, Integer> puntuaciones) {
        return puntuaciones.values().stream()
                .filter(v -> v != null && v >= 0)
                .mapToInt(Integer::intValue)
                .average().orElse(0.0);
    }

    private double numero(Object valor) {
        return valor instanceof Number n ? n.doubleValue() : 0.0;
    }

    private String texto(Map<String, Object> reactivo, String clave) {
        Object valor = reactivo.get(clave);
        return valor == null ? "" : String.valueOf(valor);
    }

    @SuppressWarnings("unchecked")
    private List<String> opciones(Map<String, Object> reactivo) {
        Object valor = reactivo.get("opciones_o_respuesta");
        if (valor instanceof List<?> lista) {
            return lista.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
