package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.DTO.GuardarIntentoRequest;
import com.example.tallerintegrador.DTO.GuardarIntentoRequest.RespuestaDetalle;
import com.example.tallerintegrador.agents.AgentJudgeAgent;
import com.example.tallerintegrador.agents.TutorConversacionalAgent.VeredictoTurno;
import com.example.tallerintegrador.entidades.postgres.Material;
import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.entidades.postgres.RondaCustodiada;
import com.example.tallerintegrador.entidades.postgres.RondaCustodiada.Clase;
import com.example.tallerintegrador.entidades.postgres.Semana;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.MaterialRepository;
import com.example.tallerintegrador.repository.MatriculaRepository;
import com.example.tallerintegrador.repository.RondaCustodiadaRepository;
import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.service.academico.IntentoService;
import com.example.tallerintegrador.service.ia.AudienciaService;
import com.example.tallerintegrador.service.util.IdHasher;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;

/**
 * Custodia en el servidor de las preguntas que resuelve un alumno.
 *
 * Reglas que garantiza:
 *  - el navegador nunca recibe la respuesta de una pregunta que aún no contestó;
 *  - la calificación, la penalización por ayudas, la nota y el bono se calculan aquí;
 *  - una pregunta se califica una sola vez y una ronda se cierra una sola vez.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustodiaRondaService {

    public static final int CANTIDAD_MINIMA = 5;
    public static final int CANTIDAD_MAXIMA = 10;
    static final int HORAS_VIGENCIA = 48;
    static final int DIAS_RETENCION = 3;
    static final int LARGO_MAXIMO_RESPUESTA = 6000;
    static final int ESCALON_MAXIMO = 3;
    /** Turnos de reserva del tutor, por si una pregunta se generó y no llegó al navegador. */
    static final int TURNOS_DE_RESERVA = 2;

    /** Tolerancia de red al medir si una respuesta llegó a tiempo. */
    static final int MARGEN_SEGUNDOS = 4;
    /** Duración de la escena de Aria: mientras dura, el reloj del alumno está detenido. */
    static final int ESCENA_SEGUNDOS = 3;
    static final int EXTRA_TIEMPO = 15;
    static final int EXTRA_CONGELAR = 10;
    static final int EXTRA_PISTA = 20;

    private static final Set<String> TIPOS_BINARIOS =
            Set.of("OPCION_MULTIPLE", "VERDADERO_FALSO", "VISUAL_QUIZ", "VIDEO_EXPLICATIVO");
    private static final Set<String> TIPOS_PERMITIDOS = Set.of(
            "OPCION_MULTIPLE", "VERDADERO_FALSO", "VISUAL_QUIZ", "VIDEO_EXPLICATIVO", "ABIERTA", "DETECCION_ERRORES");
    private static final List<String> VERDADERO_FALSO = List.of("VERDADERO", "FALSO");

    /** Lo único de una pregunta que puede salir antes de contestarla. */
    private static final List<String> CAMPOS_VISIBLES =
            List.of("id", "enunciado", "concepto", "prompt_imagen", "base64_imagen", "tipo_pregunta", "nivel_bloom");
    /** Datos del lote que la pantalla necesita. El resto (métricas, tokens) no le sirve al alumno. */
    private static final List<String> LOTE_VISIBLE = List.of(
            "tecnica", "tipo_pregunta", "nivel_bloom", "nivel_bloom_orden", "nivel_bloom_obj", "es_hots", "leccion",
            "tipo_evaluacion", "origen", "nivel_conocimiento_aplicado", "nivel_origen", "mensaje_alumno");

    private final RondaCustodiadaRepository rondas;
    private final UserRepository usuarios;
    private final MaterialRepository materiales;
    private final MatriculaRepository matriculas;
    private final com.example.tallerintegrador.repository.SemanaRepository semanas;
    private final AgentJudgeAgent juez;
    private final PoderesService poderes;
    private final IntentoService intentos;
    private final AudienciaService audiencia;
    private final IdHasher idHasher;
    private final PlatformTransactionManager transacciones;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Alumno matriculado en la semana del material. {@code vuelta}: rondas que ya hizo en ella. */
    public record AccesoAlumno(Long usuarioId, Long semanaId, int vuelta) {}

    public record RespuestaEnviada(String respuestaEstudiante) {}

    public record Adaptativa(String semanaHash, int segundos, List<RespuestaDetalle> respuestas) {}

    public record TurnoTutor(String enunciado, int escalon) {}

    /** Lo que el servidor sabe de cada pregunta. Se guarda como JSON dentro de la ronda. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Estado {
        private String respuesta;
        private Map<String, Object> evaluacion;
        private boolean ayuda;
        private boolean mitadUsada;
        private List<String> ocultas = new ArrayList<>();
        private int extraSegundos;
        private boolean aTiempo;
        private int repreguntas;
        private boolean turnoCerrado;
        private Integer puntuacion;
    }

    // ── Apertura ────────────────────────────────────────────────────────

    /**
     * Devuelve el acceso si el correo es de un alumno y vacío si es de un docente, que sí puede ver
     * las respuestas. Se llama antes de gastar en generar: un alumno ajeno al curso no pasa de aquí.
     */
    @Transactional(readOnly = true)
    public Optional<AccesoAlumno> accesoDe(String correo, String mongoId) {
        Usuario alumno = correo == null ? null : usuarios.findByCorreo(correo).orElse(null);
        if (alumno == null || alumno.getRol() != Rol.STUDENT) return Optional.empty();
        Semana semana = materiales.findByMongoId(mongoId == null ? "" : mongoId).map(Material::getSemana).orElse(null);
        if (semana == null || semana.getCurso() == null
                || matriculas.findByCursoIdAndUsuarioId(semana.getCurso().getId(), alumno.getId()).isEmpty()) {
            throw new SecurityException("Este material no pertenece a tus cursos.");
        }
        return Optional.of(new AccesoAlumno(alumno.getId(), semana.getId(),
                (int) rondas.countByUsuarioIdAndSemanaId(alumno.getId(), semana.getId())));
    }

    /** Id del alumno con ese correo; vacío si es docente. */
    @Transactional(readOnly = true)
    public Optional<Long> idDeAlumno(String correo) {
        return usuarios.findByCorreo(correo == null ? "" : correo)
                .filter(u -> u.getRol() == Rol.STUDENT).map(Usuario::getId);
    }

    @Transactional(readOnly = true)
    public void exigirMatricula(Long usuarioId, Long semanaId) {
        Semana semana = semanas.findById(semanaId).orElse(null);
        if (semana == null || semana.getCurso() == null
                || matriculas.findByCursoIdAndUsuarioId(semana.getCurso().getId(), usuarioId).isEmpty()) {
            throw new SecurityException("Esta semana no pertenece a tus cursos.");
        }
    }

    public static void validarTipo(String tipo) {
        if (!TIPOS_PERMITIDOS.contains(tipo)) throw new IllegalArgumentException("Tipo de pregunta no válido.");
    }

    /** La nota siempre es sobre 20: sin este límite, una ronda de una sola pregunta valdría 20. */
    public static int cantidadPermitida(int pedida) {
        return Math.max(CANTIDAD_MINIMA, Math.min(CANTIDAD_MAXIMA, pedida));
    }

    /** Guarda el lote completo y devuelve la versión para el alumno, sin respuestas. */
    public Map<String, Object> protegerGenerada(AccesoAlumno acceso, String tipo, Map<String, Object> resultado) {
        List<Map<String, Object>> preguntas = listaDeMapas(resultado.get("preguntas"));
        Clase clase = "VIDEO_EXPLICATIVO".equals(tipo) ? Clase.VIDEO : Clase.PRACTICA;
        Object bloom = resultado.get("nivel_bloom") != null ? resultado.get("nivel_bloom") : resultado.get("nivel_bloom_obj");
        String id = crear(acceso.usuarioId(), acceso.semanaId(), clase, tipo, texto(bloom), preguntas, null);

        Map<String, Object> salida = loteVisible(resultado);
        salida.put("preguntas", paraAlumno(preguntas, tipo));
        salida.put("ronda_id", id);
        return salida;
    }

    /** Igual que {@link #protegerGenerada}, para la evaluación adaptativa y la de ubicación. */
    public Map<String, Object> protegerAdaptativa(Long usuarioId, Long semanaId, Map<String, Object> resultado) {
        boolean ubicacion = "UBICACION".equals(resultado.get("tipo_evaluacion"));
        String tipoLote = texto(resultado.get("tipo_pregunta"));
        List<Map<String, Object>> preguntas = preguntasAdaptativas(resultado.get("preguntas_json"), tipoLote);
        String id = crear(usuarioId, semanaId, ubicacion ? Clase.UBICACION : Clase.ADAPTATIVA, tipoLote,
                texto(resultado.get("nivel_bloom")), preguntas, null);

        Map<String, Object> salida = loteVisible(resultado);
        List<Map<String, Object>> visibles = new ArrayList<>();
        for (Map<String, Object> p : preguntas) visibles.addAll(paraAlumno(List.of(p), texto(p.get("tipo_pregunta"))));
        salida.put("preguntas_json", Map.of("preguntas", visibles));
        salida.put("ronda_id", id);
        return salida;
    }

    private Map<String, Object> loteVisible(Map<String, Object> resultado) {
        Map<String, Object> salida = new LinkedHashMap<>();
        for (String campo : LOTE_VISIBLE) {
            if (resultado.containsKey(campo)) salida.put(campo, resultado.get(campo));
        }
        return salida;
    }

    static List<Map<String, Object>> paraAlumno(List<Map<String, Object>> preguntas, String tipo) {
        List<Map<String, Object>> visibles = new ArrayList<>();
        for (Map<String, Object> p : preguntas) {
            Map<String, Object> v = new LinkedHashMap<>();
            for (String campo : CAMPOS_VISIBLES) {
                if (p.get(campo) != null) v.put(campo, p.get(campo));
            }
            // En una pregunta abierta este campo es la rúbrica de corrección.
            if (!esAbierta(tipo, p)) v.put("opciones_o_respuesta", p.get("opciones_o_respuesta"));
            visibles.add(v);
        }
        return visibles;
    }

    /** El banco estático trae alternativas con su marca de correcta: se llevan al formato común. */
    private List<Map<String, Object>> preguntasAdaptativas(Object json, String tipoLote) {
        Object lista = json instanceof Map<?, ?> m ? m.get("preguntas") : json;
        List<Map<String, Object>> normalizadas = new ArrayList<>();
        for (Map<String, Object> original : listaDeMapas(lista)) {
            Map<String, Object> p = new LinkedHashMap<>(original);
            if (p.get("opciones") instanceof List<?> opciones) {
                List<String> textos = new ArrayList<>();
                for (Object o : opciones) {
                    if (o instanceof Map<?, ?> op) {
                        textos.add(texto(op.get("texto")));
                        if (Boolean.TRUE.equals(op.get("esCorrecta"))) p.put("respuesta_correcta", texto(op.get("texto")));
                    }
                }
                p.remove("opciones");
                p.put("opciones_o_respuesta", textos);
            }
            String declarado = texto(p.get("tipo_pregunta"));
            String tipo = declarado.isBlank() ? tipoLote
                    : "Responder".equalsIgnoreCase(declarado) ? "ABIERTA"
                    : "Opcion_Multiple".equalsIgnoreCase(declarado) ? "OPCION_MULTIPLE" : declarado;
            p.put("tipo_pregunta", esAbierta(tipo, p) ? "ABIERTA" : tipo);
            normalizadas.add(p);
        }
        return normalizadas;
    }

    private String crear(Long usuarioId, Long semanaId, Clase clase, String tipo, String bloom,
                         List<Map<String, Object>> preguntas, Integer totalTurnos) {
        if (clase != Clase.AVATAR && preguntas.isEmpty()) throw new IllegalStateException("No se generaron preguntas.");
        LocalDateTime ahora = LocalDateTime.now();
        RondaCustodiada r = new RondaCustodiada();
        r.setId(UUID.randomUUID().toString());
        r.setUsuarioId(usuarioId);
        r.setSemanaId(semanaId);
        r.setClase(clase);
        r.setTipo(recortar(tipo, 40));
        r.setNivelBloom(recortar(bloom, 40));
        r.setCreada(ahora);
        r.setUltimaMarca(ahora);
        r.setTotalTurnos(totalTurnos);
        escribir(r, preguntas, estadosNuevos(preguntas.size()));
        rondas.save(r);
        return r.getId();
    }

    // ── Responder ───────────────────────────────────────────────────────

    public Map<String, Object> responder(Long usuarioId, String rondaId, int indice, String respuesta) {
        RondaCustodiada r = abierta(usuarioId, rondaId, Clase.PRACTICA, Clase.VIDEO);
        List<Map<String, Object>> preguntas = preguntas(r);
        Map<String, Object> p = enIndice(preguntas, indice);
        Estado previo = estados(r).get(indice);
        if (previo.getEvaluacion() != null) return salida(r.getTipo(), p, previo);
        if (respuesta == null || respuesta.isBlank()) throw new IllegalArgumentException("Primero escribe o elige tu respuesta.");

        String enviada = recortar(respuesta, LARGO_MAXIMO_RESPUESTA);
        LocalDateTime llegada = LocalDateTime.now();
        Map<String, Object> evaluacion = calificar(r.getTipo(), p, enviada, preguntas.size());
        if (Boolean.TRUE.equals(evaluacion.get("fallo"))) {
            // Si la IA no respondió, no se guarda nada y el alumno puede reintentar.
            return new LinkedHashMap<>(Map.of("evaluacion", evaluacion));
        }

        return enTransaccion(() -> {
            RondaCustodiada bloqueada = rondas.bloquear(rondaId).orElseThrow();
            exigirSinCerrar(bloqueada);
            List<Estado> estados = estados(bloqueada);
            Estado e = estados.get(indice);
            if (e.getEvaluacion() != null) return salida(bloqueada.getTipo(), p, e);

            e.setRespuesta(enviada);
            e.setEvaluacion(e.isAyuda() ? conAyuda(evaluacion) : evaluacion);
            long tardanza = Duration.between(bloqueada.getUltimaMarca(), llegada).toSeconds();
            int limite = segundosPara(p, bloqueada.getTipo(), bloqueada.getNivelBloom()) + e.getExtraSegundos() + MARGEN_SEGUNDOS;
            e.setATiempo(tardanza <= limite);
            bloqueada.setUltimaMarca(LocalDateTime.now());
            escribirEstados(bloqueada, estados);
            return salida(bloqueada.getTipo(), p, e);
        });
    }

    private Map<String, Object> calificar(String tipo, Map<String, Object> p, String respuesta, int total) {
        Map<String, Object> juicio = juez.evaluarRespuestaUnitaria(texto(p.get("enunciado")), esperada(tipo, p),
                respuesta, total, tipo, audiencia.delUsuarioActual());
        Map<String, Object> evaluacion = new LinkedHashMap<>(mapa(juicio.get("evaluacion")));
        if (!TIPOS_BINARIOS.contains(tipo)) return evaluacion;

        List<String> opciones = opcionesDe(tipo, p);
        int correcta = indiceDe(opciones, texto(p.get("respuesta_correcta")));
        if (correcta < 0) return evaluacion;

        // En una pregunta cerrada decide la comparación, no el modelo.
        boolean acerto = correcta == indiceDe(opciones, respuesta);
        double peso = Math.round((20.0 / total) * 100.0) / 100.0;
        boolean coincide = !Boolean.TRUE.equals(evaluacion.get("fallo")) && Boolean.valueOf(acerto).equals(evaluacion.get("esCorrecta"));
        if (!coincide) evaluacion.put("explicacion", texto(p.get("justificacion_pregunta")));
        evaluacion.remove("fallo");
        evaluacion.put("esCorrecta", acerto);
        evaluacion.put("puntaje_porcentaje", acerto ? 100 : 0);
        evaluacion.put("puntaje", acerto ? peso : 0.0);
        evaluacion.put("puntaje_maximo", peso);
        return evaluacion;
    }

    static Map<String, Object> conAyuda(Map<String, Object> evaluacion) {
        Map<String, Object> copia = new LinkedHashMap<>(evaluacion);
        double puntaje = copia.get("puntaje") instanceof Number n ? n.doubleValue() : 0.0;
        copia.put("puntaje", Math.round(puntaje * 50.0) / 100.0);
        copia.put("explicacion", texto(copia.get("explicacion")) + " (Usaste una ayuda en esta pregunta: vale la mitad.)");
        return copia;
    }

    /** La respuesta correcta solo viaja junto con la calificación de esa misma pregunta. */
    private Map<String, Object> salida(String tipo, Map<String, Object> p, Estado e) {
        Map<String, Object> revelado = new LinkedHashMap<>();
        revelado.put("respuesta_correcta", p.get("respuesta_correcta"));
        revelado.put("justificacion_pregunta", p.get("justificacion_pregunta"));
        if (esAbierta(tipo, p)) revelado.put("opciones_o_respuesta", p.get("opciones_o_respuesta"));
        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("pregunta_evaluada", p.get("enunciado"));
        salida.put("evaluacion", e.getEvaluacion());
        salida.put("revelado", revelado);
        return salida;
    }

    // ── Comodines ───────────────────────────────────────────────────────

    public Map<String, Object> comodin(Long usuarioId, String rondaId, int indice, PoderesService.Poder poder) {
        if (poder == null) throw new IllegalArgumentException("Comodín no válido.");
        RondaCustodiada r = abierta(usuarioId, rondaId, Clase.PRACTICA);
        Map<String, Object> p = enIndice(preguntas(r), indice);
        List<String> ocultas = poder == PoderesService.Poder.MITAD ? reservarMitad(rondaId, indice, p) : List.of();
        if (poder != PoderesService.Poder.MITAD && estados(r).get(indice).getEvaluacion() != null) {
            throw new IllegalStateException("Esa pregunta ya está respondida.");
        }

        boolean sinAlternativas = esAbierta(r.getTipo(), p);
        Map<String, Object> salida;
        try {
            salida = new LinkedHashMap<>(poderes.usar(usuarioId, rondaId, poder, new PoderesService.Pregunta(
                    texto(p.get("enunciado")), sinAlternativas ? null : opcionesDe(r.getTipo(), p), esperada(r.getTipo(), p),
                    texto(p.get("concepto")))));
        } catch (RuntimeException ex) {
            if (poder == PoderesService.Poder.MITAD) liberarMitad(rondaId, indice);
            throw ex;
        }

        enTransaccion(() -> {
            RondaCustodiada bloqueada = rondas.bloquear(rondaId).orElseThrow();
            List<Estado> estados = estados(bloqueada);
            Estado e = estados.get(indice);
            switch (poder) {
                case TIEMPO -> e.setExtraSegundos(e.getExtraSegundos() + EXTRA_TIEMPO + ESCENA_SEGUNDOS);
                case CONGELAR -> e.setExtraSegundos(e.getExtraSegundos() + EXTRA_CONGELAR + ESCENA_SEGUNDOS);
                case MITAD -> { e.setAyuda(true); e.setExtraSegundos(e.getExtraSegundos() + ESCENA_SEGUNDOS); }
                case PISTA -> { e.setAyuda(true); e.setExtraSegundos(e.getExtraSegundos() + EXTRA_PISTA); }
            }
            escribirEstados(bloqueada, estados);
            return null;
        });
        if (poder == PoderesService.Poder.MITAD) salida.put("ocultas", ocultas);
        return salida;
    }

    /** Se marca antes de cobrar: dos 50/50 simultáneos sobre la misma pregunta dejarían solo la correcta. */
    private List<String> reservarMitad(String rondaId, int indice, Map<String, Object> p) {
        return enTransaccion(() -> {
            RondaCustodiada bloqueada = rondas.bloquear(rondaId).orElseThrow();
            List<Estado> estados = estados(bloqueada);
            Estado e = estados.get(indice);
            if (e.getEvaluacion() != null) throw new IllegalStateException("Esa pregunta ya está respondida.");
            if (e.isMitadUsada()) throw new IllegalStateException("Ya usaste el 50/50 en esta pregunta.");
            List<String> ocultas = elegirOcultas(bloqueada.getTipo(), p);
            e.setMitadUsada(true);
            e.setOcultas(ocultas);
            escribirEstados(bloqueada, estados);
            return ocultas;
        });
    }

    private void liberarMitad(String rondaId, int indice) {
        enTransaccion(() -> {
            RondaCustodiada bloqueada = rondas.bloquear(rondaId).orElseThrow();
            List<Estado> estados = estados(bloqueada);
            estados.get(indice).setMitadUsada(false);
            estados.get(indice).setOcultas(new ArrayList<>());
            escribirEstados(bloqueada, estados);
            return null;
        });
    }

    /** Con 4 alternativas se quitan 2 y con 3 se quita 1: siempre quedan al menos dos. */
    static List<String> elegirOcultas(String tipo, Map<String, Object> p) {
        List<String> opciones = opcionesDe(tipo, p);
        int correcta = indiceDe(opciones, texto(p.get("respuesta_correcta")));
        if ("VERDADERO_FALSO".equals(tipo) || esAbierta(tipo, p) || "DETECCION_ERRORES".equals(tipo)
                || opciones.size() < 3 || correcta < 0) {
            throw new IllegalStateException("El 50/50 no está disponible en esta pregunta.");
        }
        List<String> incorrectas = new ArrayList<>(opciones);
        incorrectas.remove(correcta);
        Collections.shuffle(incorrectas);
        return new ArrayList<>(incorrectas.subList(0, Math.min(2, opciones.size() - 2)));
    }

    // ── Finalizar ───────────────────────────────────────────────────────

    public Map<String, Object> finalizar(Long usuarioId, String rondaId, boolean contrarreloj) {
        RondaCustodiada r = deAlumno(usuarioId, rondaId);
        if (r.getClase() == Clase.AVATAR) return finalizarTutor(usuarioId, rondaId);
        exigirClase(r, Clase.PRACTICA, Clase.VIDEO);

        boolean[] yaCerrada = {false};
        RondaCustodiada cerrada = enTransaccion(() -> {
            RondaCustodiada b = rondas.bloquear(rondaId).orElseThrow();
            if (b.getCerrada() != null) { yaCerrada[0] = true; return b; }
            exigirVigente(b);
            List<Estado> estados = estados(b);
            if (estados.stream().anyMatch(e -> e.getEvaluacion() == null)) {
                throw new IllegalStateException("Aún faltan preguntas por responder.");
            }
            double suma = estados.stream().mapToDouble(CustodiaRondaService::puntajeDe).sum();
            b.setNotaFinal(notaAcotada(suma));
            b.setCerrada(LocalDateTime.now());
            return rondas.save(b);
        });
        if (yaCerrada[0]) return resumen(cerrada);

        List<Map<String, Object>> preguntas = preguntas(cerrada);
        List<Estado> estados = estados(cerrada);
        List<RespuestaDetalle> detalle = new ArrayList<>();
        for (int i = 0; i < preguntas.size(); i++) {
            Map<String, Object> p = preguntas.get(i);
            Estado e = estados.get(i);
            detalle.add(new RespuestaDetalle(texto(p.get("enunciado")), cerrada.getTipo(), e.getRespuesta(),
                    Boolean.TRUE.equals(e.getEvaluacion().get("esCorrecta")), cerrada.getNivelBloom(),
                    texto(p.get("concepto")), esperada(cerrada.getTipo(), p), texto(e.getEvaluacion().get("explicacion"))));
        }
        String tecnica = cerrada.getClase() == Clase.VIDEO ? "video" : cerrada.getTipo();
        guardarOReabrir(cerrada, tecnica, detalle);

        int bono = 0;
        if (contrarreloj && cerrada.getClase() == Clase.PRACTICA) bono = cobrarBono(usuarioId, rondaId, estados);
        cerrada.setBono(bono);
        rondas.save(cerrada);
        return resumen(cerrada);
    }

    private int cobrarBono(Long usuarioId, String rondaId, List<Estado> estados) {
        int rapidas = 0, combo = 0, comboMaximo = 0;
        for (Estado e : estados) {
            if (Boolean.TRUE.equals(e.getEvaluacion().get("esCorrecta"))) {
                combo++;
                comboMaximo = Math.max(comboMaximo, combo);
                if (e.isATiempo()) rapidas++;
            } else {
                combo = 0;
            }
        }
        try {
            return poderes.cobrarBono(usuarioId, rondaId, rapidas, comboMaximo);
        } catch (RuntimeException ex) {
            log.warn("[RONDA] No se pudo cobrar el bono: {}", ex.getMessage());
            return 0;
        }
    }

    private void guardarOReabrir(RondaCustodiada cerrada, String tecnica, List<RespuestaDetalle> detalle) {
        int segundos = (int) Duration.between(cerrada.getCreada(), LocalDateTime.now()).toSeconds();
        try {
            intentos.guardarIntentoCompleto(new GuardarIntentoRequest(cerrada.getUsuarioId(),
                    idHasher.encode(cerrada.getSemanaId()), cerrada.getNotaFinal(), tecnica, detalle), segundos);
        } catch (RuntimeException ex) {
            cerrada.setCerrada(null);
            cerrada.setNotaFinal(null);
            rondas.save(cerrada);
            throw ex;
        }
    }

    private Map<String, Object> resumen(RondaCustodiada r) {
        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("notaFinal", r.getNotaFinal());
        salida.put("bono", r.getBono() == null ? 0 : r.getBono());
        return salida;
    }

    private static double puntajeDe(Estado e) {
        return e.getEvaluacion().get("puntaje") instanceof Number n ? n.doubleValue() : 0.0;
    }

    static double notaAcotada(double suma) {
        return Math.round(Math.min(20.0, Math.max(0.0, suma)) * 100.0) / 100.0;
    }

    // ── Tutor conversacional ────────────────────────────────────────────

    /** Registra la pregunta que el servidor acaba de generar. Devuelve el id de ronda y el turno. */
    public Map<String, Object> registrarPreguntaTutor(AccesoAlumno acceso, String rondaId, int totalDeclarado, String enunciado) {
        int total = cantidadPermitida(totalDeclarado);
        String id = rondaId == null || rondaId.isBlank()
                ? crear(acceso.usuarioId(), acceso.semanaId(), Clase.AVATAR, "ABIERTA", null, new ArrayList<>(), total)
                : rondaId;
        return enTransaccion(() -> {
            RondaCustodiada b = rondas.bloquear(id).orElseThrow(() -> new NoSuchElementException("Sesión no encontrada."));
            exigirDueno(b, acceso.usuarioId());
            exigirClase(b, Clase.AVATAR);
            exigirSinCerrar(b);
            exigirVigente(b);
            List<Map<String, Object>> preguntas = preguntas(b);
            if (preguntas.size() >= b.getTotalTurnos() + TURNOS_DE_RESERVA) {
                throw new IllegalStateException("Esta sesión ya no admite más preguntas.");
            }
            preguntas.add(new LinkedHashMap<>(Map.of("enunciado", enunciado == null ? "" : enunciado)));
            List<Estado> estados = estados(b);
            estados.add(new Estado());
            escribir(b, preguntas, estados);
            Map<String, Object> salida = new LinkedHashMap<>();
            salida.put("ronda_id", b.getId());
            salida.put("turno_indice", preguntas.size() - 1);
            return salida;
        });
    }

    /** Enunciado y escalón los fija el servidor: un escalón menor subiría el techo de la nota. */
    public TurnoTutor turnoTutor(Long usuarioId, String rondaId, int indice) {
        RondaCustodiada r = abierta(usuarioId, rondaId, Clase.AVATAR);
        Map<String, Object> p = enIndice(preguntas(r), indice);
        Estado e = estados(r).get(indice);
        if (e.isTurnoCerrado()) throw new IllegalStateException("Esa pregunta ya fue calificada.");
        return new TurnoTutor(texto(p.get("enunciado")), Math.min(ESCALON_MAXIMO, e.getRepreguntas() + 1));
    }

    public void registrarVeredicto(String rondaId, int indice, String respuesta, VeredictoTurno veredicto) {
        enTransaccion(() -> {
            RondaCustodiada b = rondas.bloquear(rondaId).orElseThrow();
            List<Estado> estados = estados(b);
            Estado e = estados.get(indice);
            if (e.isTurnoCerrado() || b.getCerrada() != null) return null;
            if (veredicto.cerrado()) {
                e.setTurnoCerrado(true);
                e.setPuntuacion(veredicto.puntuacion());
                e.setRespuesta(respuesta == null || respuesta.isBlank() ? "[respuesta en audio]" : recortar(respuesta, LARGO_MAXIMO_RESPUESTA));
            } else {
                e.setRepreguntas(e.getRepreguntas() + 1);
            }
            escribirEstados(b, estados);
            return null;
        });
    }

    private Map<String, Object> finalizarTutor(Long usuarioId, String rondaId) {
        boolean[] yaCerrada = {false};
        RondaCustodiada cerrada = enTransaccion(() -> {
            RondaCustodiada b = rondas.bloquear(rondaId).orElseThrow();
            if (b.getCerrada() != null) { yaCerrada[0] = true; return b; }
            exigirVigente(b);
            double suma = estados(b).stream().filter(Estado::isTurnoCerrado).limit(b.getTotalTurnos())
                    .mapToDouble(e -> puntosDeTurno(e) / 4.0 * 20.0 / b.getTotalTurnos()).sum();
            if (estados(b).stream().noneMatch(Estado::isTurnoCerrado)) {
                throw new IllegalStateException("Responde al menos una pregunta antes de terminar.");
            }
            b.setNotaFinal(notaAcotada(suma));
            b.setCerrada(LocalDateTime.now());
            return rondas.save(b);
        });
        if (yaCerrada[0]) return resumen(cerrada);

        List<Map<String, Object>> preguntas = preguntas(cerrada);
        List<Estado> estados = estados(cerrada);
        List<RespuestaDetalle> detalle = new ArrayList<>();
        for (int i = 0; i < preguntas.size() && detalle.size() < cerrada.getTotalTurnos(); i++) {
            Estado e = estados.get(i);
            if (e.isTurnoCerrado()) {
                detalle.add(new RespuestaDetalle(texto(preguntas.get(i).get("enunciado")), "ABIERTA", e.getRespuesta(),
                        puntosDeTurno(e) >= 2, null, null, null, null));
            }
        }
        guardarOReabrir(cerrada, "avatar", detalle);
        return resumen(cerrada);
    }

    private static int puntosDeTurno(Estado e) {
        return e.getPuntuacion() == null ? 1 : Math.max(0, Math.min(4, e.getPuntuacion()));
    }

    // ── Adaptativa y ubicación ──────────────────────────────────────────

    /**
     * Cierra la ronda y arma las respuestas con los datos del servidor. Del cliente solo se usa lo
     * que escribió el alumno, por posición. Las abiertas las califica después CalificadorDeAbiertas.
     */
    public Adaptativa cerrarAdaptativa(Long usuarioId, String rondaId, Clase esperada, List<RespuestaEnviada> enviadas) {
        RondaCustodiada cerrada = enTransaccion(() -> {
            RondaCustodiada b = rondas.bloquear(rondaId).orElseThrow(() -> new NoSuchElementException("Evaluación no encontrada."));
            exigirDueno(b, usuarioId);
            exigirClase(b, esperada);
            exigirSinCerrar(b);
            exigirVigente(b);
            b.setCerrada(LocalDateTime.now());
            return rondas.save(b);
        });

        List<Map<String, Object>> preguntas = preguntas(cerrada);
        List<RespuestaDetalle> detalle = new ArrayList<>();
        for (int i = 0; i < preguntas.size(); i++) {
            Map<String, Object> p = preguntas.get(i);
            String tipo = texto(p.get("tipo_pregunta"));
            String respuesta = enviadas != null && i < enviadas.size() && enviadas.get(i) != null
                    ? recortar(texto(enviadas.get(i).respuestaEstudiante()), LARGO_MAXIMO_RESPUESTA) : "";
            boolean acerto = esAbierta(tipo, p) || coincide(tipo, p, respuesta);
            detalle.add(new RespuestaDetalle(texto(p.get("enunciado")), tipo, respuesta.isBlank() ? "No respondió" : respuesta,
                    acerto, p.get("nivel_bloom") != null ? texto(p.get("nivel_bloom")) : cerrada.getNivelBloom(),
                    texto(p.get("concepto")), esperada(tipo, p), null));
        }
        int segundos = (int) Duration.between(cerrada.getCreada(), LocalDateTime.now()).toSeconds();
        return new Adaptativa(idHasher.encode(cerrada.getSemanaId()), segundos, detalle);
    }

    /** Si el guardado falla después de cerrar, el alumno debe poder reenviar. */
    public void reabrir(String rondaId) {
        rondas.findById(rondaId).ifPresent(r -> {
            r.setCerrada(null);
            rondas.save(r);
        });
    }

    static boolean coincide(String tipo, Map<String, Object> p, String respuesta) {
        List<String> opciones = opcionesDe(tipo, p);
        int correcta = indiceDe(opciones, texto(p.get("respuesta_correcta")));
        return correcta >= 0 && correcta == indiceDe(opciones, respuesta);
    }

    // ── Reglas de las preguntas ─────────────────────────────────────────

    /** Criterio con el que se corrige: el mismo que se muestra después en el historial. */
    static String esperada(String tipo, Map<String, Object> p) {
        String justificacion = texto(p.get("justificacion_pregunta"));
        if (esAbierta(tipo, p)) {
            List<String> rubrica = textos(p.get("opciones_o_respuesta"));
            return rubrica.isEmpty() ? justificacion : justificacion + " Rúbrica: " + rubrica.get(0);
        }
        String correcta = texto(p.get("respuesta_correcta"));
        if ("DETECCION_ERRORES".equals(tipo)) return correcta;
        return correcta.isBlank() ? justificacion : correcta;
    }

    /** El modelo no siempre declara el tipo: una alternativa única o con "Rúbrica:" es una abierta. */
    static boolean esAbierta(String tipo, Map<String, Object> p) {
        if ("ABIERTA".equalsIgnoreCase(tipo) || "ABIERTA".equalsIgnoreCase(texto(p.get("tipo_pregunta")))) return true;
        if ("VERDADERO_FALSO".equals(tipo) || "DETECCION_ERRORES".equals(tipo)) return false;
        List<String> opciones = textos(p.get("opciones_o_respuesta"));
        return opciones.size() == 1 || (!opciones.isEmpty() && opciones.get(0).trim().matches("(?iu)^r[uú]brica\\s*:.*"));
    }

    static List<String> opcionesDe(String tipo, Map<String, Object> p) {
        if ("VERDADERO_FALSO".equals(tipo)) return VERDADERO_FALSO;
        return textos(p.get("opciones_o_respuesta"));
    }

    /** Posición de un texto entre las alternativas: exacto, sin la letra inicial, o solo la letra. */
    static int indiceDe(List<String> opciones, String buscado) {
        if (buscado == null || buscado.isBlank()) return -1;
        for (int i = 0; i < opciones.size(); i++) {
            if (opciones.get(i).trim().equals(buscado.trim())) return i;
        }
        String limpio = normalizar(sinLetra(buscado));
        for (int i = 0; i < opciones.size(); i++) {
            if (!limpio.isEmpty() && normalizar(sinLetra(opciones.get(i))).equals(limpio)) return i;
        }
        if (buscado.trim().matches("[A-Da-d][).]?")) {
            int letra = Character.toUpperCase(buscado.trim().charAt(0)) - 'A';
            for (int i = 0; i < opciones.size(); i++) {
                if (opciones.get(i).trim().toUpperCase(Locale.ROOT).matches((char) ('A' + letra) + "[).].*")) return i;
            }
        }
        return -1;
    }

    /** Misma fórmula que el reloj del navegador (useContrarreloj.ts). */
    static int segundosPara(Map<String, Object> p, String tipo, String nivelBloom) {
        boolean abierta = esAbierta(tipo, p);
        String opciones = abierta ? "" : String.join(" ", textos(p.get("opciones_o_respuesta")));
        String lectura = (texto(p.get("enunciado")) + " " + opciones).trim();
        int palabras = lectura.isEmpty() ? 1 : lectura.split("\\s+").length;
        String bloom = texto(nivelBloom).toLowerCase(Locale.ROOT);
        int extraBloom = bloom.matches(".*(5|6|evalu|crea|sintes).*") ? 20 : bloom.matches(".*(3|4|aplic|anali).*") ? 10 : 0;
        int extraTipo = abierta ? 45 : "DETECCION_ERRORES".equals(tipo) ? 30 : 0;
        double total = 8 + palabras * 0.4 + extraBloom + extraTipo;
        return Math.min(150, Math.max(15, (int) Math.round(total / 5) * 5));
    }

    // ── Soporte ─────────────────────────────────────────────────────────

    private RondaCustodiada deAlumno(Long usuarioId, String rondaId) {
        RondaCustodiada r = rondas.findById(rondaId == null ? "" : rondaId)
                .orElseThrow(() -> new NoSuchElementException("Esta práctica ya no está disponible."));
        exigirDueno(r, usuarioId);
        return r;
    }

    private RondaCustodiada abierta(Long usuarioId, String rondaId, Clase... clases) {
        RondaCustodiada r = deAlumno(usuarioId, rondaId);
        exigirClase(r, clases);
        exigirSinCerrar(r);
        exigirVigente(r);
        return r;
    }

    /** No se distingue una ronda ajena de una inexistente. */
    private static void exigirDueno(RondaCustodiada r, Long usuarioId) {
        if (!r.getUsuarioId().equals(usuarioId)) throw new NoSuchElementException("Esta práctica ya no está disponible.");
    }

    private static void exigirClase(RondaCustodiada r, Clase... clases) {
        if (!List.of(clases).contains(r.getClase())) throw new IllegalArgumentException("Esta acción no aplica a esta evaluación.");
    }

    private static void exigirSinCerrar(RondaCustodiada r) {
        if (r.getCerrada() != null) throw new IllegalStateException("Esta práctica ya terminó.");
    }

    private static void exigirVigente(RondaCustodiada r) {
        if (r.getCreada().isBefore(LocalDateTime.now().minusHours(HORAS_VIGENCIA))) {
            throw new IllegalStateException("Esta práctica venció. Empieza una nueva.");
        }
    }

    private static Map<String, Object> enIndice(List<Map<String, Object>> preguntas, int indice) {
        if (indice < 0 || indice >= preguntas.size()) throw new IllegalArgumentException("Pregunta no válida.");
        return preguntas.get(indice);
    }

    private <T> T enTransaccion(Supplier<T> accion) {
        return new TransactionTemplate(transacciones).execute(s -> accion.get());
    }

    private List<Map<String, Object>> preguntas(RondaCustodiada r) {
        try {
            return mapper.readValue(r.getPreguntasJson(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("La práctica guardada no se pudo leer.", e);
        }
    }

    private List<Estado> estados(RondaCustodiada r) {
        try {
            return mapper.readValue(r.getEstadoJson(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("La práctica guardada no se pudo leer.", e);
        }
    }

    private void escribir(RondaCustodiada r, List<Map<String, Object>> preguntas, List<Estado> estados) {
        try {
            r.setPreguntasJson(mapper.writeValueAsString(preguntas));
            r.setEstadoJson(mapper.writeValueAsString(estados));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo guardar la práctica.", e);
        }
    }

    private void escribirEstados(RondaCustodiada r, List<Estado> estados) {
        try {
            r.setEstadoJson(mapper.writeValueAsString(estados));
            rondas.save(r);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("No se pudo guardar la práctica.", e);
        }
    }

    private static List<Estado> estadosNuevos(int n) {
        List<Estado> estados = new ArrayList<>();
        for (int i = 0; i < n; i++) estados.add(new Estado());
        return estados;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listaDeMapas(Object o) {
        List<Map<String, Object>> lista = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) if (x instanceof Map<?, ?> m) lista.add((Map<String, Object>) m);
        }
        return lista;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapa(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<String> textos(Object o) {
        if (o instanceof List<?> l) return l.stream().map(CustodiaRondaService::texto).toList();
        String s = texto(o);
        return s.isBlank() ? List.of() : List.of(s.split(" \\| "));
    }

    private static String texto(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String sinLetra(String s) {
        return s.replaceFirst("^\\s*[A-Da-d][).]\\s*", "");
    }

    private static String normalizar(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String recortar(String s, int largo) {
        return s == null || s.length() <= largo ? s : s.substring(0, largo);
    }

    /** Las rondas guardan respuestas del alumno: se borran a los pocos días, ya pasadas a su historial. */
    @Scheduled(cron = "0 30 4 * * *")
    public void purgar() {
        Integer borradas = enTransaccion(() -> rondas.borrarAnterioresA(LocalDateTime.now().minusDays(DIAS_RETENCION)));
        log.info("[RONDA] Rondas antiguas borradas: {}", borradas);
    }
}
