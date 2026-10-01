package com.example.tallerintegrador.service.spike;

import com.example.tallerintegrador.agents.preguntas.ComiteDePreguntasService;
import com.example.tallerintegrador.agents.preguntas.CorrectorEstiloAgent;
import com.example.tallerintegrador.agents.preguntas.ReactivoReescrito;
import com.example.tallerintegrador.service.metricas.MetricasEstandarizadasService;
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

    @Value("${app.comite-preguntas.habilitado:false}")
    private boolean comiteHabilitado;

    @Value("${app.comite-preguntas.max-lote:10}")
    private int maxLote;

    @Value("${app.corrector-estilo.habilitado:true}")
    private boolean correctorHabilitado;

    /** Bajo este indice de Szigriszt, el enunciado se manda a corregir aunque nadie lo objete. */
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
        return comiteHabilitado || correctorHabilitado;
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
        if (!comiteHabilitado) return pedidas;
        return Math.min(pedidas + margenGeneracion, maxLote);
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
    @SuppressWarnings("unchecked")
    public Resumen aplicar(List<Object> preguntas, String tipoPregunta, String contexto, String nivelBloom) {
        if (preguntas == null || preguntas.isEmpty()) {
            return new Resumen(0, 0, 0, 0, 0, 0, "sin datos");
        }
        int recibidos = preguntas.size();
        double legibilidadAntes = legibilidadMedia(preguntas);

        if (!comiteHabilitado) {
            if (correctorHabilitado) {
                return soloCorregirEstilo(preguntas, tipoPregunta, recibidos, legibilidadAntes);
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
            dictamenes = comite.revisarLote(aRevisar, contexto, nivelBloom, null);
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
                    < umbralLegibilidad;

            if (d.aprobada() && !legibilidadBaja) continue;

            if (d.aprobada() && legibilidadBaja) {
                // Aprobada: se queda con o sin correccion, asi que no entra en aDescartar.
                pendientes.add(new CorreccionPendiente(reactivo,
                        "El enunciado resulta dificil de leer para un estudiante de secundaria. "
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
            Mejora la REDACCION del enunciado para que un estudiante de secundaria de 12 a 17
            anos entienda sin esfuerzo QUE se le esta pidiendo.

            EL OBJETIVO NO ES SIMPLIFICAR, ES ACLARAR. No busques un enunciado mas corto ni mas
            facil: busca uno mejor escrito. Si hace falta ANADIR palabras para que la tarea quede
            inequivoca, anadelas; un enunciado mas largo y claro es mejor que uno breve y ambiguo.

            QUE SI DEBES ARREGLAR:
              - Sintaxis enredada, subordinadas encadenadas y orden confuso de las ideas.
              - Ambiguedad sobre que se pide exactamente.
              - Jerga innecesaria: si un termino tecnico NO es el contenido evaluado, sustituyelo
                por su equivalente comun.

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
     * DIFERENCIA CON EL MODO CON COMITE. Aqui nadie dictamina, asi que NO se descarta nada: si
     * la reescritura no pasa la guarda, se conserva el reactivo original. Sin un juicio sobre el
     * contenido no hay base para tirar una pregunta a la basura.
     */
    private Resumen soloCorregirEstilo(List<Object> preguntas, String tipoPregunta,
                                       int recibidos, double legibilidadAntes) {
        List<Map<String, Object>> reactivos = new ArrayList<>();
        for (Object p : preguntas) {
            if (p instanceof Map<?, ?> m) reactivos.add((Map<String, Object>) m);
        }

        // La instruccion depende del defecto: si la correcta se delata por su longitud, lo que
        // hay que arreglar son las alternativas, no el enunciado.
        List<CompletableFuture<Boolean>> tareas = reactivos.stream()
                .map(r -> CompletableFuture.supplyAsync(() -> intentarCorregir(r, tipoPregunta,
                        ReactivoOpcionMultipleGuard.respuestaSeDelataPorLongitud(r)
                                ? INSTRUCCION_LONGITUD
                                : INSTRUCCION_CLARIDAD)))
                .toList();

        int corregidos = 0;
        for (CompletableFuture<Boolean> tarea : tareas) {
            try {
                if (tarea.join()) corregidos++;
            } catch (Exception e) {
                log.warn("[CALIDAD] Una correccion de estilo no termino: {}", e.getMessage());
            }
        }

        double legibilidadDespues = legibilidadMedia(preguntas);
        log.info("[COBERTURA] {} conceptos distintos por reactivo entregado",
                String.format("%.2f", coberturaDeConceptos(preguntas)));
        log.info("[CALIDAD] Solo corrector · {} reactivos · {} reescritos · legibilidad {} -> {} ({})",
                recibidos, corregidos, legibilidadAntes, legibilidadDespues,
                metricas.nivelInflesz(legibilidadDespues));
        return new Resumen(recibidos, recibidos, corregidos, 0,
                legibilidadAntes, legibilidadDespues, metricas.nivelInflesz(legibilidadDespues));
    }

    private boolean intentarCorregir(Map<String, Object> reactivo, String tipoPregunta, String instruccion) {
        String enunciado = texto(reactivo, "enunciado");
        List<String> opciones = opciones(reactivo);
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

        reactivo.putAll(copia);
        reactivo.put("correccion_estilo", reescrito.cambios());
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
