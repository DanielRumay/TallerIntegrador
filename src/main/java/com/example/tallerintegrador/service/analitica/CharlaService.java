package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.agents.EvaluationOrchestratorAgent;
import com.example.tallerintegrador.entidades.postgres.DominioConceptoAlumno;
import com.example.tallerintegrador.entidades.postgres.Material;
import com.example.tallerintegrador.entidades.postgres.RondaCustodiada;
import com.example.tallerintegrador.entidades.postgres.Semana;
import com.example.tallerintegrador.exception.IaNoDisponibleException;
import com.example.tallerintegrador.repository.DominioConceptoAlumnoRepository;
import com.example.tallerintegrador.repository.MaterialRepository;
import com.example.tallerintegrador.repository.RondaCustodiadaRepository;
import com.example.tallerintegrador.repository.SemanaRepository;
import com.example.tallerintegrador.seguridad.ContextoAsincrono;
import com.example.tallerintegrador.service.academico.VocabularioConceptosService;
import com.example.tallerintegrador.service.ia.AudienciaService;
import com.example.tallerintegrador.service.ia.PerfilAudiencia;
import com.example.tallerintegrador.service.rag.AlcanceMaterialesService;
import com.example.tallerintegrador.service.util.NormalizadorConcepto;
import com.example.tallerintegrador.service.util.ReactivoOpcionMultipleGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Prepara cada charla con Aria y resume el avance por concepto. No hay prueba de entrada: la
 * primera charla mezcla niveles y lo que el alumno responde ajusta las siguientes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharlaService {

    static final int MINIMO_PREGUNTAS = 3;
    static final int CHARLAS_POR_DIA = 15;
    static final int CHARLAS_PARA_DESAFIO = 2;
    static final double DOMINIO_PARA_DESAFIO = 0.6;
    static final double UMBRAL_DOMINADO = 0.85;
    static final int OBSERVACIONES_DOMINADO = 4;
    static final int DIAS_PARA_REPASO = 3;
    private static final Set<String> NIVELES_ALTOS = Set.of("Analizar", "Evaluar", "Crear");

    private final CustodiaRondaService custodia;
    private final EvaluationOrchestratorAgent orquestador;
    private final AudienciaService audiencia;
    private final DominioConceptoAlumnoRepository dominios;
    private final RondaCustodiadaRepository rondas;
    private final SemanaRepository semanas;
    private final MaterialRepository materiales;
    private final AlcanceMaterialesService alcance;
    private final VocabularioConceptosService vocabulario;
    private final com.example.tallerintegrador.repository.SesionCharlaRepository sesiones;
    private final com.example.tallerintegrador.service.metricas.TelemetriaIAService telemetria;

    private record Lote(PlanDeCharla.Paso paso, String archivos, String tema, int cantidad) {}

    /** El tope diario se cuenta al terminar de generar: sin esto, pedidos simultáneos lo saltarían. */
    private final Set<Long> preparando = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public Map<String, Object> iniciar(String correo, String mongoId, boolean desafio) {
        CustodiaRondaService.AccesoAlumno acceso = exigirAlumno(correo, mongoId);
        if (!preparando.add(acceso.usuarioId())) {
            throw new IllegalStateException("Aria ya está preparando tu charla. Espera un momento.");
        }
        try {
            return preparar(acceso, correo, mongoId, desafio);
        } finally {
            preparando.remove(acceso.usuarioId());
        }
    }

    private Map<String, Object> preparar(CustodiaRondaService.AccesoAlumno acceso, String correo, String mongoId, boolean desafio) {
        LocalDateTime inicioDelDia = LocalDate.now().atStartOfDay();
        if (rondas.countByUsuarioIdAndCreadaAfterAndClaseIn(acceso.usuarioId(), inicioDelDia,
                List.of(RondaCustodiada.Clase.CHARLA, RondaCustodiada.Clase.DESAFIO)) >= CHARLAS_POR_DIA) {
            throw new IllegalStateException("Aria ya charló mucho hoy. Vuelve mañana con más energía.");
        }

        PerfilAudiencia perfil = audiencia.resolver(correo, mongoId);
        boolean primaria = perfil.esConocido() && !perfil.secundaria();
        List<DominioConceptoAlumno> registros = dominios.deSemana(acceso.usuarioId(), acceso.semanaId());
        if (desafio && !desafioDisponible(acceso, registros)) {
            throw new IllegalStateException("Primero charla un poco más con Aria para abrir el desafío.");
        }

        List<PlanDeCharla.Paso> plan = desafio
                ? PlanDeCharla.desafio(primaria)
                : PlanDeCharla.armar(primaria, dominioMedio(registros), new Random());
        String archivos = alcance.ampliarASemana(mongoId);
        String tema = temaPara(acceso.semanaId(), registros);

        List<Map<String, Object>> preguntas = generar(plan, archivos, tema, correo, acceso.vuelta());
        if (!desafio) agregarRepaso(preguntas, acceso, correo);
        if (preguntas.size() < MINIMO_PREGUNTAS) {
            throw new IllegalStateException("Aria no pudo preparar la charla. Inténtalo de nuevo.");
        }

        Map<String, Object> salida = custodia.protegerCharla(acceso, desafio, primaria, preguntas);
        salida.put("etapa", primaria ? "PRIMARIA" : "SECUNDARIA");
        salida.put("ciclo", perfil.ciclo());
        salida.put("desafio", desafio);
        return salida;
    }

    public Map<String, Object> estado(String correo, String mongoId) {
        CustodiaRondaService.AccesoAlumno acceso = exigirAlumno(correo, mongoId);
        PerfilAudiencia perfil = audiencia.resolver(correo, mongoId);
        List<DominioConceptoAlumno> registros = dominios.deSemana(acceso.usuarioId(), acceso.semanaId());
        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("etapa", perfil.esConocido() && !perfil.secundaria() ? "PRIMARIA" : "SECUNDARIA");
        salida.put("ciclo", perfil.ciclo());
        salida.put("charlas", charlasHechas(acceso));
        salida.put("desafioDisponible", desafioDisponible(acceso, registros));
        salida.put("conceptos", conceptos(acceso.semanaId(), registros));
        return salida;
    }

    private CustodiaRondaService.AccesoAlumno exigirAlumno(String correo, String mongoId) {
        return custodia.accesoDe(correo, mongoId)
                .orElseThrow(() -> new SecurityException("La charla con Aria es para estudiantes."));
    }

    private long charlasHechas(CustodiaRondaService.AccesoAlumno acceso) {
        // De la constancia permanente: las rondas se borran a los pocos días y el desafío se cerraría.
        return sesiones.countByUsuarioIdAndSemanaIdAndDesafioFalseAndTerminadaIsNotNull(acceso.usuarioId(), acceso.semanaId());
    }

    private boolean desafioDisponible(CustodiaRondaService.AccesoAlumno acceso, List<DominioConceptoAlumno> registros) {
        Double medio = dominioMedio(registros);
        return charlasHechas(acceso) >= CHARLAS_PARA_DESAFIO || (medio != null && medio >= DOMINIO_PARA_DESAFIO);
    }

    static Double dominioMedio(List<DominioConceptoAlumno> registros) {
        if (registros.isEmpty()) return null;
        return registros.stream().mapToDouble(DominioConceptoAlumno::getProbabilidadDominio).average().orElse(0);
    }

    /** Los conceptos más flojos van primero en la búsqueda, para que Aria insista en ellos. */
    private String temaPara(Long semanaId, List<DominioConceptoAlumno> registros) {
        List<String> debiles = registros.stream()
                .sorted(Comparator.comparingDouble(DominioConceptoAlumno::getProbabilidadDominio))
                .map(CharlaService::etiqueta).distinct().limit(2).toList();
        List<String> curados = vocabulario.deSemana(semanaId);
        LinkedHashSet<String> tema = new LinkedHashSet<>(debiles);
        tema.addAll(curados.subList(0, Math.min(5 - tema.size(), curados.size())));
        if (!tema.isEmpty()) return String.join(", ", tema);
        return semanas.findById(semanaId).map(Semana::getNombreTema).filter(t -> t != null && !t.isBlank())
                .orElse("conceptos principales");
    }

    private List<Map<String, Object>> generar(List<PlanDeCharla.Paso> plan, String archivos, String tema,
                                              String correo, int vuelta) {
        Map<PlanDeCharla.Paso, Integer> grupos = new LinkedHashMap<>();
        plan.forEach(p -> grupos.merge(p, 1, Integer::sum));

        Map<PlanDeCharla.Paso, CompletableFuture<List<Map<String, Object>>>> tareas = new LinkedHashMap<>();
        grupos.forEach((paso, cantidad) ->
                tareas.put(paso, enParalelo(new Lote(paso, archivos, tema, cantidad), correo, vuelta)));

        Map<PlanDeCharla.Paso, Deque<Map<String, Object>>> listos = new HashMap<>();
        tareas.forEach((paso, tarea) -> listos.put(paso, new ArrayDeque<>(tarea.join())));

        List<Map<String, Object>> ordenadas = new ArrayList<>();
        for (PlanDeCharla.Paso paso : plan) {
            Map<String, Object> p = listos.get(paso).poll();
            if (p != null) ordenadas.add(p);
        }
        return ordenadas;
    }

    private CompletableFuture<List<Map<String, Object>>> enParalelo(Lote lote, String correo, int vuelta) {
        CompletableFuture<List<Map<String, Object>>> futuro = new CompletableFuture<>();
        ContextoAsincrono.runAsync(() -> {
            try {
                futuro.complete(generarLote(lote, correo, vuelta));
            } catch (IaNoDisponibleException e) {
                futuro.completeExceptionally(e);
            } catch (RuntimeException e) {
                log.warn("[CHARLA] No se pudo generar {}: {}", lote.paso(), e.getMessage());
                futuro.complete(List.of());
            }
        });
        return futuro;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> generarLote(Lote lote, String correo, int vuelta) {
        PlanDeCharla.Paso paso = lote.paso();
        Map<String, Object> generado = orquestador.generarEvaluacion(lote.tema(), lote.archivos(), paso.tipo(),
                paso.bloom(), "STRUCTURED_OUTPUT", lote.cantidad(), correo, vuelta);
        anotarConsumo(generado, paso);
        Object json = generado.get("preguntas_json");
        Object lista = json instanceof Map<?, ?> m ? m.get("preguntas") : json;
        List<Map<String, Object>> salida = new ArrayList<>();
        if (!(lista instanceof List<?> items)) return salida;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> crudo)) continue;
            Map<String, Object> p = new LinkedHashMap<>((Map<String, Object>) crudo);
            p.put("tipo_pregunta", paso.tipo());
            p.put("nivel_bloom", paso.bloom());
            if (PlanDeCharla.OM.equals(paso.tipo()) && !ReactivoOpcionMultipleGuard.revisar(p).valido()) continue;
            salida.add(p);
        }
        return salida;
    }

    private void anotarConsumo(Map<String, Object> generado, PlanDeCharla.Paso paso) {
        if (!(generado.get("metricas_rendimiento") instanceof Map<?, ?> m)) return;
        telemetria.registrarConsumo(com.example.tallerintegrador.entidades.postgres.TipoEventoIA.GENERACION_CHARLA, paso.tipo(),
                entero(m.get("input_tokens")), entero(m.get("output_tokens")),
                m.get("latencia_segundos") instanceof Number s ? Math.round(s.doubleValue() * 1000) : 0L, null);
    }

    private static int entero(Object valor) {
        return valor instanceof Number n ? n.intValue() : 0;
    }

    /** Trae una pregunta de un concepto de otra semana que no se practica hace días. */
    private void agregarRepaso(List<Map<String, Object>> preguntas, CustodiaRondaService.AccesoAlumno acceso, String correo) {
        if (preguntas.size() < PlanDeCharla.TOTAL) return;
        List<Object[]> candidatos = dominios.paraRepaso(acceso.usuarioId(), acceso.semanaId(),
                LocalDateTime.now().minusDays(DIAS_PARA_REPASO), PageRequest.of(0, 1));
        if (candidatos.isEmpty()) return;
        Long semanaAntigua = (Long) candidatos.get(0)[0];
        String concepto = String.valueOf(candidatos.get(0)[1]);
        String archivos = materiales.findBySemanaId(semanaAntigua).stream().filter(Material::isVisible)
                .map(Material::getMongoId).filter(id -> id != null && !id.isBlank()).distinct()
                .collect(Collectors.joining(","));
        if (archivos.isBlank()) return;
        try {
            List<Map<String, Object>> repaso = generarLote(new Lote(new PlanDeCharla.Paso(PlanDeCharla.OM, "Comprender"),
                    archivos, concepto, 1), correo, 0);
            if (!repaso.isEmpty()) {
                repaso.get(0).put("repaso", true);
                preguntas.set(preguntas.size() - 1, repaso.get(0));
            }
        } catch (RuntimeException e) {
            log.warn("[CHARLA] Sin pregunta de repaso: {}", e.getMessage());
        }
    }

    /**
     * Un concepto cuenta como dominado con evidencia suficiente en niveles altos. Hasta entonces
     * el avance visible se queda por debajo de 85 %: dos aciertos no bastan para dominar algo.
     */
    private List<Map<String, Object>> conceptos(Long semanaId, List<DominioConceptoAlumno> registros) {
        Map<String, List<DominioConceptoAlumno>> porConcepto = registros.stream()
                .collect(Collectors.groupingBy(DominioConceptoAlumno::getConcepto, LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> salida = new ArrayList<>();
        porConcepto.values().forEach(lista -> salida.add(filaConcepto(lista)));
        for (String curado : vocabulario.deSemana(semanaId)) {
            if (!porConcepto.containsKey(NormalizadorConcepto.canonizar(curado))) {
                Map<String, Object> fila = new LinkedHashMap<>();
                fila.put("concepto", NormalizadorConcepto.paraMostrar(curado));
                fila.put("porcentaje", 0);
                fila.put("estado", "SIN_EMPEZAR");
                salida.add(fila);
            }
        }
        return salida;
    }

    static Map<String, Object> filaConcepto(List<DominioConceptoAlumno> lista) {
        boolean evidenciaAlta = lista.stream().anyMatch(d -> NIVELES_ALTOS.contains(d.getNivelBloom())
                && d.getProbabilidadDominio() >= UMBRAL_DOMINADO);
        int observaciones = lista.stream().mapToInt(DominioConceptoAlumno::getObservaciones).sum();
        boolean dominado = evidenciaAlta && observaciones >= OBSERVACIONES_DOMINADO;
        double medio = lista.stream().mapToDouble(DominioConceptoAlumno::getProbabilidadDominio).average().orElse(0);
        int porcentaje = dominado ? 100 : Math.min(84, (int) Math.round(medio * 100));
        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("concepto", etiqueta(lista.get(0)));
        fila.put("porcentaje", porcentaje);
        fila.put("estado", dominado ? "DOMINADO" : porcentaje >= 60 ? "CASI" : "EN_CAMINO");
        return fila;
    }

    private static String etiqueta(DominioConceptoAlumno d) {
        return d.getConceptoEtiqueta() != null && !d.getConceptoEtiqueta().isBlank()
                ? d.getConceptoEtiqueta() : NormalizadorConcepto.paraMostrar(d.getConcepto());
    }
}
