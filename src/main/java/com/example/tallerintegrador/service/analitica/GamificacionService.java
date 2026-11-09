package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.Intento;
import com.example.tallerintegrador.entidades.postgres.Matricula;
import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.entidades.postgres.TipoEvaluacion;
import com.example.tallerintegrador.entidades.postgres.TurnoTutorSocratico;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.IntentoRepository;
import com.example.tallerintegrador.repository.MatriculaRepository;
import com.example.tallerintegrador.repository.TurnoTutorSocraticoRepository;
import com.example.tallerintegrador.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Niveles, monedas y liga semanal del alumno.
 *
 * Se apoya en ProgresoAlumnoService y respeta su misma separación: todo lo de aquí mide
 * ACTIVIDAD y existe para motivar. No toca el nivel de dominio ni la dificultad de las preguntas.
 *
 * Como allí, NO hay tablas de puntos: XP y monedas se calculan desde los intentos y los turnos
 * con Aria, así el número siempre refleja lo que el alumno hizo. Solo se guarda el apodo.
 *
 * LA LIGA COMPITE POR ESFUERZO DE LA SEMANA, NO POR NOTA ACUMULADA. Cada lunes todos vuelven a
 * cero: quien quedó último la semana pasada puede ganar esta. Y los compañeros solo ven apodos,
 * nunca nombres: son menores de edad y su desempeño es dato personal (Ley 29733).
 */
@Service
@RequiredArgsConstructor
public class GamificacionService {

    private final ProgresoAlumnoService progresoAlumnoService;
    private final IntentoRepository intentoRepository;
    private final TurnoTutorSocraticoRepository turnoRepository;
    private final MatriculaRepository matriculaRepository;
    private final UserRepository userRepository;
    private final MonedasService monedasService;

    /** Cuántos puestos de la cabeza de la liga ve todo el mundo. */
    static final int TOP_VISIBLE = 5;

    /** Monedas por puesto del podio semanal (1.º, 2.º, 3.º). */
    static final int[] PREMIO_PODIO = {50, 30, 20};
    /** Semanas hacia atrás en las que se revisa si quedó un podio sin cobrar. */
    static final int SEMANAS_PODIO = 4;
    /** Alumnos con actividad que debe tener la semana para repartir podio. */
    static final int MIN_PARTICIPANTES_PODIO = 3;

    // ── Niveles ─────────────────────────────────────────────────────────────

    /**
     * XP total necesaria para llegar al nivel n: 25·n·(n−1). Del 1 al 2 bastan 50 XP (unas tres
     * evaluaciones), y cada nivel cuesta 50 XP más que el anterior: al principio se sube rápido,
     * que es cuando más falta hace el empujón.
     */
    static int xpParaNivel(int nivel) {
        return 25 * nivel * (nivel - 1);
    }

    static int nivelDe(int xp) {
        int nivel = 1;
        while (xpParaNivel(nivel + 1) <= xp) nivel++;
        return nivel;
    }

    // ── Resumen del alumno ──────────────────────────────────────────────────

    public Map<String, Object> resumen(Long usuarioId) {
        // Antes de mostrar el saldo, se cobran los podios de semanas pasadas que falten.
        otorgarPodiosPendientes(usuarioId);

        Map<String, Object> progreso = progresoAlumnoService.progresoDe(usuarioId);
        int xp = (Integer) progreso.get("puntos");
        int nivel = nivelDe(xp);
        int desde = xpParaNivel(nivel);
        int hasta = xpParaNivel(nivel + 1);

        Usuario u = userRepository.findById(usuarioId).orElseThrow();

        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("xp", xp);
        salida.put("nivel", nivel);
        salida.put("xpEnNivel", xp - desde);
        salida.put("xpDelNivel", hasta - desde);
        salida.put("monedas", monedasService.saldo(usuarioId));
        salida.put("racha", progreso.get("racha"));
        salida.put("titulo", progreso.get("rango"));
        salida.put("tituloEmoji", progreso.get("rangoEmoji"));
        salida.put("apodo", apodoDe(u));
        salida.put("ariaEquipado", TiendaService.equipadoDe(u));
        salida.put("ariaVariante", u.getAriaVariante());
        salida.put("perfilVisible", !Boolean.FALSE.equals(u.getPerfilVisible()));
        salida.put("etapa", etapaDe(u.getGrado() == null ? null : u.getGrado().getNombre()));
        return salida;
    }

    /**
     * Cobra el podio de las últimas semanas cerradas en cada curso del alumno. Se revisa al
     * abrir la aplicación, así no hace falta una tarea programada; la clave del movimiento
     * impide cobrarlo dos veces.
     */
    void otorgarPodiosPendientes(Long usuarioId) {
        LocalDateTime estaSemana = inicioDeSemana(LocalDate.now());
        for (Matricula m : matriculaRepository.findByUsuarioId(usuarioId)) {
            List<Usuario> companeros = companerosDe(m);
            List<Long> ids = companeros.stream().map(Usuario::getId).toList();
            for (int s = 1; s <= SEMANAS_PODIO; s++) {
                LocalDateTime lunes = estaSemana.minusWeeks(s);
                String clave = "podio:" + m.getCurso().getId() + ":" + lunes.toLocalDate();
                Map<Long, Integer> xp = xpEntre(ids, lunes, lunes.plusWeeks(1));
                if (xp.getOrDefault(usuarioId, 0) == 0) continue;
                // Sin competencia no hay podio: si solo practicó él, el "1.º puesto" sería regalado.
                if (xp.values().stream().filter(v -> v > 0).count() < MIN_PARTICIPANTES_PODIO) continue;
                FilaLiga yo = clasificar(companeros, xp, usuarioId).stream()
                        .filter(FilaLiga::esTu).findFirst().orElse(null);
                if (yo == null || yo.posicion() > PREMIO_PODIO.length) continue;
                monedasService.premiar(usuarioId, PREMIO_PODIO[yo.posicion() - 1],
                        yo.posicion() + ".º puesto en la liga de " + m.getCurso().getNombre(), clave);
            }
        }
    }

    private List<Usuario> companerosDe(Matricula m) {
        Long colegioId = m.getCurso().getColegio() == null ? null : m.getCurso().getColegio().getId();
        return matriculaRepository.findByCursoId(m.getCurso().getId()).stream()
                .map(Matricula::getUsuario)
                .filter(u -> u.getRol() == Rol.STUDENT)
                .filter(u -> Objects.equals(u.colegioId(), colegioId))
                .toList();
    }

    /** La diagnóstica no suma: es un punto de partida, no esfuerzo. Igual que en ProgresoAlumnoService. */
    private static boolean cuenta(Intento i) {
        return i.getTipoEvaluacion() != TipoEvaluacion.DIAGNOSTICA;
    }

    /**
     * Primaria o secundaria según el nombre del grado ("2.° de secundaria", "5to primaria"…).
     * Decide el estilo de Aria por defecto; null si el grado no lo dice o no está asignado.
     */
    static String etapaDe(String grado) {
        if (grado == null) return null;
        String g = grado.toLowerCase(java.util.Locale.ROOT);
        if (g.contains("sec")) return "SECUNDARIA";
        if (g.contains("prim")) return "PRIMARIA";
        return null;
    }

    // ── Liga semanal ────────────────────────────────────────────────────────

    /** orden: índice en la tabla completa, para que la interfaz marque los tramos ocultos. */
    public record FilaLiga(int orden, int posicion, String apodo, int xp, boolean esTu,
                           @com.fasterxml.jackson.annotation.JsonIgnore Long usuarioId) {}

    /**
     * Liga de la semana en un curso del alumno. Con cursoId null usa su primer curso.
     * Devuelve vacío si el alumno no está matriculado en ese curso.
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> ligaSemanal(Long usuarioId, Long cursoId) {
        List<Matricula> mias = matriculaRepository.findByUsuarioId(usuarioId);
        if (mias.isEmpty()) return Optional.empty();
        Matricula elegida = cursoId == null ? mias.get(0)
                : mias.stream().filter(m -> m.getCurso().getId().equals(cursoId)).findFirst().orElse(null);
        if (elegida == null) return Optional.empty();

        List<Usuario> companeros = companerosDe(elegida);

        LocalDateTime lunes = inicioDeSemana(LocalDate.now());
        Map<Long, Integer> xpSemana = xpEntre(companeros.stream().map(Usuario::getId).toList(), lunes, lunes.plusWeeks(1));

        List<FilaLiga> tabla = clasificar(companeros, xpSemana, usuarioId);
        FilaLiga yo = tabla.stream().filter(FilaLiga::esTu).findFirst().orElseThrow();

        // Se ve la cabeza de la liga y, si el alumno no está en ella, sus vecinos de puesto:
        // compararse con el de al lado motiva; ver solo a los cinco primeros desde el puesto 25, no.
        int miIndice = tabla.indexOf(yo);
        Set<Integer> visibles = new TreeSet<>();
        for (int i = 0; i < Math.min(TOP_VISIBLE, tabla.size()); i++) visibles.add(i);
        for (int i = Math.max(0, miIndice - 1); i <= Math.min(tabla.size() - 1, miIndice + 1); i++) visibles.add(i);
        List<FilaLiga> filas = visibles.stream().map(tabla::get).toList();

        // Cuánto le falta para pasar al de arriba (con otra XP; si empatan no hay a quién pasar).
        Integer xpParaSubir = null;
        for (int i = miIndice - 1; i >= 0; i--) {
            if (tabla.get(i).xp() > yo.xp()) { xpParaSubir = tabla.get(i).xp() - yo.xp() + 1; break; }
        }

        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("cursoId", elegida.getCurso().getId());
        salida.put("curso", elegida.getCurso().getNombre());
        salida.put("cursos", mias.stream().map(m -> Map.of("id", m.getCurso().getId(), "nombre", m.getCurso().getNombre())).toList());
        salida.put("desde", lunes.toLocalDate().toString());
        salida.put("hasta", lunes.toLocalDate().plusDays(6).toString());
        salida.put("participantes", tabla.size());
        salida.put("miPosicion", yo.posicion());
        salida.put("miXp", yo.xp());
        salida.put("xpParaSubir", xpParaSubir);
        salida.put("filas", filas);
        return Optional.of(salida);
    }

    static LocalDateTime inicioDeSemana(LocalDate hoy) {
        return hoy.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
    }

    /** XP de la semana con los mismos valores que ProgresoAlumnoService (sin racha ni conceptos, que no son de la semana). */
    Map<Long, Integer> xpEntre(List<Long> ids, LocalDateTime desde, LocalDateTime hasta) {
        Map<Long, Integer> xp = new HashMap<>();
        if (ids.isEmpty()) return xp;
        for (Intento i : intentoRepository.findByUsuarioIdInAndFechaGreaterThanEqualAndFechaLessThan(ids, desde, hasta)) {
            if (!cuenta(i)) continue;
            int puntos = ProgresoAlumnoService.PUNTOS_POR_EVALUACION
                    + (i.getNota() == null ? 0 : (int) Math.round(i.getNota() * ProgresoAlumnoService.PUNTOS_POR_PUNTO_DE_NOTA));
            xp.merge(i.getUsuario().getId(), puntos, Integer::sum);
        }
        for (TurnoTutorSocratico t : turnoRepository.findByUsuarioIdInAndCerradoTrueAndFechaGreaterThanEqualAndFechaLessThan(ids, desde, hasta)) {
            int puntos = t.getEscalonConsumido() <= 1
                    ? ProgresoAlumnoService.PUNTOS_RESOLVER_SOLO : ProgresoAlumnoService.PUNTOS_RESOLVER_CON_PISTA;
            xp.merge(t.getUsuario().getId(), puntos, Integer::sum);
        }
        return xp;
    }

    /** Orden por XP; los empatados comparten puesto (1, 2, 2, 4). */
    static List<FilaLiga> clasificar(List<Usuario> alumnos, Map<Long, Integer> xp, Long yoId) {
        List<Usuario> orden = new ArrayList<>(alumnos);
        orden.sort(Comparator.<Usuario>comparingInt(u -> xp.getOrDefault(u.getId(), 0)).reversed()
                .thenComparing(GamificacionService::apodoDe));
        List<FilaLiga> filas = new ArrayList<>();
        int posicion = 0, anterior = Integer.MIN_VALUE;
        for (int i = 0; i < orden.size(); i++) {
            Usuario u = orden.get(i);
            int puntos = xp.getOrDefault(u.getId(), 0);
            if (puntos != anterior) { posicion = i + 1; anterior = puntos; }
            filas.add(new FilaLiga(i, posicion, apodoDe(u), puntos, u.getId().equals(yoId), u.getId()));
        }
        return filas;
    }

    // ── Apodos ──────────────────────────────────────────────────────────────
    // Animal + adjetivo invariable en género ("Zorro Veloz", "Llama Veloz"). Elegirlo de una
    // lista cerrada evita tener que moderar lo que escriben menores de edad.

    static final List<String> ANIMALES = List.of(
            "Zorro", "Panda", "Búho", "Llama", "Cóndor", "Jaguar", "Delfín", "Puma", "Halcón",
            "Tortuga", "Lobo", "Koala", "Pingüino", "Colibrí", "Tigre", "Nutria", "Vicuña", "Gato");
    static final List<String> ADJETIVOS = List.of(
            "Veloz", "Audaz", "Valiente", "Brillante", "Genial", "Tenaz", "Sagaz", "Ágil",
            "Imparable", "Increíble", "Feliz", "Leal", "Capaz", "Fugaz", "Estelar");

    static String apodoDe(Usuario u) {
        if (u.getApodo() != null && !u.getApodo().isBlank()) return u.getApodo();
        long id = u.getId() == null ? 0 : u.getId();
        return ANIMALES.get((int) (id % ANIMALES.size())) + " "
                + ADJETIVOS.get((int) ((id / ANIMALES.size()) % ADJETIVOS.size()));
    }

    static boolean apodoValido(String apodo) {
        if (apodo == null) return false;
        String[] partes = apodo.trim().split(" ");
        return partes.length == 2 && ANIMALES.contains(partes[0]) && ADJETIVOS.contains(partes[1]);
    }

    /** Seis combinaciones al azar para que el alumno elija. */
    public List<String> opcionesDeApodo() {
        Random r = new Random();
        Set<String> opciones = new LinkedHashSet<>();
        while (opciones.size() < 6) {
            opciones.add(ANIMALES.get(r.nextInt(ANIMALES.size())) + " " + ADJETIVOS.get(r.nextInt(ADJETIVOS.size())));
        }
        return new ArrayList<>(opciones);
    }

    @Transactional
    public String cambiarApodo(Long usuarioId, String apodo) {
        if (!apodoValido(apodo)) throw new IllegalArgumentException("Elige uno de los apodos propuestos.");
        Usuario u = userRepository.findById(usuarioId).orElseThrow();
        u.setApodo(apodo.trim());
        userRepository.save(u);
        return u.getApodo();
    }
}
