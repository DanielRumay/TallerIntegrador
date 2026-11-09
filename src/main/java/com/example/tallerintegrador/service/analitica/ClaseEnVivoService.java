package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.Curso;
import com.example.tallerintegrador.entidades.postgres.Semana;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.MatriculaRepository;
import com.example.tallerintegrador.repository.SemanaRepository;
import com.example.tallerintegrador.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clase en vivo: el docente proyecta preguntas y los alumnos responden desde su celular, con
 * puntaje por acierto y rapidez y ranking en vivo (al estilo de Kahoot).
 *
 * SIN TABLAS Y EN MEMORIA, a propósito: una partida dura lo que dura la clase, no aporta a la
 * nota ni al historial, y guardarla solo acumularía datos personales sin uso. Si el servidor se
 * reinicia, la partida se pierde y el docente abre otra. Las partidas se borran solas a las 3 h.
 *
 * Acceso: crea y avanza la partida solo un docente del curso; se une solo un alumno
 * MATRICULADO en ese curso (el código no basta: sin esa comprobación, cualquiera con el número
 * entraría a la clase de otro colegio). En la pantalla proyectada salen apodos, nunca nombres.
 *
 * La comunicación es por consulta periódica (cada segundo) y no por WebSocket: funciona detrás
 * del Nginx actual sin cambiar su configuración, y para 40 alumnos es una carga mínima.
 */
@Service
@RequiredArgsConstructor
public class ClaseEnVivoService {

    private final SemanaRepository semanaRepository;
    private final MatriculaRepository matriculaRepository;
    private final UserRepository userRepository;

    static final int MAX_PREGUNTAS = 15;
    static final int PUNTOS_BASE = 500;
    static final int PUNTOS_RAPIDEZ = 500;
    static final Duration VIDA_SESION = Duration.ofHours(3);

    public enum Fase { ESPERA, PREGUNTA, RESULTADO, FIN }

    public record PreguntaVivo(String enunciado, List<String> opciones, String correcta) {}

    static final class Participante {
        final Long id;
        final String apodo;
        int puntos;
        int racha;
        Integer ultimaGanancia;
        Boolean ultimaCorrecta;
        Participante(Long id, String apodo) { this.id = id; this.apodo = apodo; }
    }

    static final class Sesion {
        final String codigo;
        final Long cursoId;
        final String curso;
        final String tema;
        final List<PreguntaVivo> preguntas;
        final List<Integer> correctas;
        final int segundos;
        final Set<Long> docentes;
        final Instant creada = Instant.now();
        Fase fase = Fase.ESPERA;
        int indice = -1;
        Instant inicio;
        final Map<Long, Participante> participantes = new LinkedHashMap<>();
        final Map<Long, Integer> respuestas = new HashMap<>();
        final Map<Long, Long> milisegundos = new HashMap<>();

        Sesion(String codigo, Long cursoId, String curso, String tema, List<PreguntaVivo> preguntas,
               List<Integer> correctas, int segundos, Set<Long> docentes) {
            this.codigo = codigo; this.cursoId = cursoId; this.curso = curso; this.tema = tema;
            this.preguntas = preguntas; this.correctas = correctas; this.segundos = segundos;
            this.docentes = Set.copyOf(docentes);
        }
    }

    private final Map<String, Sesion> sesiones = new ConcurrentHashMap<>();
    private final SecureRandom azar = new SecureRandom();

    // ── Docente ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String crear(Long docenteId, Long semanaId, List<PreguntaVivo> preguntas, Integer segundos) {
        Semana semana = semanaRepository.findById(semanaId)
                .orElseThrow(() -> new IllegalArgumentException("La semana no existe."));
        Curso curso = semana.getCurso();
        if (curso == null || !curso.esDocente(docenteId)) {
            throw new SecurityException("Esta semana no es de un curso tuyo.");
        }
        List<PreguntaVivo> validas = new ArrayList<>();
        List<Integer> correctas = new ArrayList<>();
        for (PreguntaVivo p : preguntas == null ? List.<PreguntaVivo>of() : preguntas) {
            if (p == null || p.enunciado() == null || p.enunciado().isBlank() || p.enunciado().length() > 600) continue;
            if (p.opciones() == null || p.opciones().size() < 2 || p.opciones().size() > 6) continue;
            int correcta = indiceDe(p.opciones(), p.correcta());
            if (correcta < 0) continue;
            validas.add(new PreguntaVivo(p.enunciado().trim(), List.copyOf(p.opciones()), p.correcta()));
            correctas.add(correcta);
            if (validas.size() == MAX_PREGUNTAS) break;
        }
        if (validas.isEmpty()) throw new IllegalArgumentException("No hay preguntas de opción múltiple válidas para jugar.");

        int tiempo = segundos == null ? 20 : Math.max(10, Math.min(60, segundos));
        String codigo;
        do { codigo = String.format("%06d", azar.nextInt(1_000_000)); } while (sesiones.containsKey(codigo));
        Set<Long> docentes = new HashSet<>();
        if (curso.getProfesor() != null) docentes.add(curso.getProfesor().getId());
        curso.getCoDocentes().forEach(d -> docentes.add(d.getId()));
        docentes.add(docenteId);
        sesiones.put(codigo, new Sesion(codigo, curso.getId(), curso.getNombre(), semana.getNombreTema(),
                validas, correctas, tiempo, docentes));
        return codigo;
    }

    /** La alternativa correcta, aunque venga como "A) texto" y las opciones sin letra o al revés. */
    static int indiceDe(List<String> opciones, String correcta) {
        if (correcta == null) return -1;
        String c = sinLetra(correcta);
        for (int i = 0; i < opciones.size(); i++) {
            if (opciones.get(i) != null && sinLetra(opciones.get(i)).equalsIgnoreCase(c)) return i;
        }
        return -1;
    }

    private static String sinLetra(String s) {
        return s.trim().replaceFirst("^[A-Fa-f][).]\\s*", "").trim();
    }

    public Map<String, Object> tablero(Long docenteId, String codigo) {
        Sesion s = deDocente(docenteId, codigo);
        synchronized (s) {
            cerrarSiVencio(s);
            Map<String, Object> salida = base(s);
            salida.put("participantes", s.participantes.size());
            salida.put("apodos", s.participantes.values().stream().map(p -> p.apodo).toList());
            if (s.fase == Fase.PREGUNTA || s.fase == Fase.RESULTADO) {
                PreguntaVivo p = s.preguntas.get(s.indice);
                salida.put("pregunta", p.enunciado());
                salida.put("opciones", p.opciones());
                salida.put("respondieron", s.respuestas.size());
            }
            if (s.fase == Fase.RESULTADO) {
                salida.put("correcta", s.correctas.get(s.indice));
                int[] distribucion = new int[s.preguntas.get(s.indice).opciones().size()];
                s.respuestas.values().forEach(o -> { if (o >= 0 && o < distribucion.length) distribucion[o]++; });
                salida.put("distribucion", distribucion);
            }
            if (s.fase == Fase.RESULTADO || s.fase == Fase.FIN) salida.put("ranking", ranking(s, 5));
            return salida;
        }
    }

    public Map<String, Object> avanzar(Long docenteId, String codigo) {
        Sesion s = deDocente(docenteId, codigo);
        synchronized (s) {
            switch (s.fase) {
                case ESPERA, RESULTADO -> {
                    if (s.indice + 1 >= s.preguntas.size()) {
                        s.fase = Fase.FIN;
                    } else {
                        s.indice++;
                        s.fase = Fase.PREGUNTA;
                        s.inicio = Instant.now();
                        s.respuestas.clear();
                        s.milisegundos.clear();
                        s.participantes.values().forEach(p -> { p.ultimaGanancia = null; p.ultimaCorrecta = null; });
                    }
                }
                case PREGUNTA -> cerrar(s);
                case FIN -> { }
            }
        }
        return tablero(docenteId, codigo);
    }

    // ── Alumno ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> unirse(Long alumnoId, String codigo) {
        Sesion s = sesion(codigo);
        if (matriculaRepository.findByCursoIdAndUsuarioId(s.cursoId, alumnoId).isEmpty()) {
            throw new SecurityException("Esta clase en vivo es de un curso en el que no estás matriculado.");
        }
        Usuario u = userRepository.findById(alumnoId).orElseThrow();
        synchronized (s) {
            if (s.fase == Fase.FIN) throw new IllegalStateException("Esta clase en vivo ya terminó.");
            s.participantes.computeIfAbsent(alumnoId, id -> new Participante(id, GamificacionService.apodoDe(u)));
        }
        return estado(alumnoId, codigo);
    }

    public Map<String, Object> estado(Long alumnoId, String codigo) {
        Sesion s = sesion(codigo);
        synchronized (s) {
            Participante yo = s.participantes.get(alumnoId);
            if (yo == null) throw new SecurityException("Primero únete a la clase con el código.");
            cerrarSiVencio(s);
            Map<String, Object> salida = base(s);
            salida.put("apodo", yo.apodo);
            salida.put("puntos", yo.puntos);
            salida.put("racha", yo.racha);
            salida.put("puesto", puestoDe(s, alumnoId));
            salida.put("participantes", s.participantes.size());
            if (s.fase == Fase.PREGUNTA || s.fase == Fase.RESULTADO) {
                PreguntaVivo p = s.preguntas.get(s.indice);
                salida.put("pregunta", p.enunciado());
                salida.put("opciones", p.opciones());
                salida.put("miRespuesta", s.respuestas.get(alumnoId));
            }
            if (s.fase == Fase.RESULTADO) {
                salida.put("correcta", s.correctas.get(s.indice));
                salida.put("acerte", yo.ultimaCorrecta);
                salida.put("ganancia", yo.ultimaGanancia == null ? 0 : yo.ultimaGanancia);
            }
            if (s.fase == Fase.FIN) salida.put("ranking", ranking(s, 3));
            return salida;
        }
    }

    public Map<String, Object> responder(Long alumnoId, String codigo, int opcion) {
        Sesion s = sesion(codigo);
        synchronized (s) {
            if (!s.participantes.containsKey(alumnoId)) throw new SecurityException("Primero únete a la clase con el código.");
            cerrarSiVencio(s);
            if (s.fase != Fase.PREGUNTA) throw new IllegalStateException("Esta pregunta ya se cerró.");
            if (opcion < 0 || opcion >= s.preguntas.get(s.indice).opciones().size()) throw new IllegalArgumentException("Opción no válida.");
            if (s.respuestas.putIfAbsent(alumnoId, opcion) == null) {
                s.milisegundos.put(alumnoId, Duration.between(s.inicio, Instant.now()).toMillis());
            }
            // Si ya respondieron todos, no hace falta esperar al reloj.
            if (s.respuestas.size() >= s.participantes.size()) cerrar(s);
        }
        return estado(alumnoId, codigo);
    }

    // ── Reglas ──────────────────────────────────────────────────────────────

    /** 500 por acertar y hasta 500 más según la rapidez: responder al instante vale casi el doble. */
    static int puntaje(boolean correcta, long ms, int segundos) {
        if (!correcta) return 0;
        double fraccion = Math.min(1.0, Math.max(0.0, ms / (segundos * 1000.0)));
        return PUNTOS_BASE + (int) Math.round(PUNTOS_RAPIDEZ * (1 - fraccion));
    }

    private void cerrar(Sesion s) {
        if (s.fase != Fase.PREGUNTA) return;
        int correcta = s.correctas.get(s.indice);
        for (Participante p : s.participantes.values()) {
            Integer r = s.respuestas.get(p.id);
            boolean acerto = r != null && r == correcta;
            int ganancia = puntaje(acerto, s.milisegundos.getOrDefault(p.id, (long) s.segundos * 1000), s.segundos);
            p.ultimaCorrecta = r == null ? null : acerto;
            p.ultimaGanancia = ganancia;
            p.puntos += ganancia;
            p.racha = acerto ? p.racha + 1 : 0;
        }
        s.fase = Fase.RESULTADO;
    }

    private void cerrarSiVencio(Sesion s) {
        if (s.fase == Fase.PREGUNTA && Instant.now().isAfter(s.inicio.plusSeconds(s.segundos).plusMillis(500))) cerrar(s);
    }

    private Map<String, Object> base(Sesion s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("codigo", s.codigo);
        m.put("curso", s.curso);
        m.put("tema", s.tema);
        m.put("fase", s.fase.name());
        m.put("indice", s.indice);
        m.put("total", s.preguntas.size());
        m.put("segundos", s.segundos);
        if (s.fase == Fase.PREGUNTA) {
            long pasados = Duration.between(s.inicio, Instant.now()).toMillis();
            m.put("restanteMs", Math.max(0, s.segundos * 1000L - pasados));
        }
        return m;
    }

    private static List<Map<String, Object>> ranking(Sesion s, int cuantos) {
        return s.participantes.values().stream()
                .sorted(Comparator.comparingInt((Participante p) -> p.puntos).reversed())
                .limit(cuantos)
                .map(p -> Map.<String, Object>of("apodo", p.apodo, "puntos", p.puntos))
                .toList();
    }

    private static int puestoDe(Sesion s, Long id) {
        int mios = s.participantes.get(id).puntos;
        return 1 + (int) s.participantes.values().stream().filter(p -> p.puntos > mios).count();
    }

    private Sesion sesion(String codigo) {
        Sesion s = codigo == null ? null : sesiones.get(codigo.trim());
        if (s == null) throw new NoSuchElementException("No hay ninguna clase en vivo con ese código.");
        return s;
    }

    /** Los docentes del curso se fijan al crear la partida: no hace falta ir a la base cada segundo. */
    private Sesion deDocente(Long docenteId, String codigo) {
        Sesion s = sesion(codigo);
        if (!s.docentes.contains(docenteId)) throw new SecurityException("Esta clase en vivo no es tuya.");
        return s;
    }

    @Scheduled(fixedDelay = 600_000)
    void limpiar() {
        Instant limite = Instant.now().minus(VIDA_SESION);
        sesiones.values().removeIf(s -> s.creada.isBefore(limite));
    }
}
