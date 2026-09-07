package com.example.tallerintegrador.service.metricas;

import com.example.tallerintegrador.entidades.postgres.*;
import com.example.tallerintegrador.repository.*;
import com.example.tallerintegrador.service.metricas.AcuerdoJuezService.ParCalificacion;
import com.example.tallerintegrador.service.metricas.AcuerdoJuezService.ResultadoAcuerdo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Flujo de validación del juez de IA contra docentes humanos.
 *
 * Tres pasos: (1) se arma una muestra de respuestas que la IA ya calificó, (2) los docentes
 * las califican SIN ver la nota de la IA, (3) se mide la concordancia.
 *
 * El paso 2 es el que da validez a todo lo demás. Si el docente viera la nota de la IA antes
 * de calificar, tendería a confirmarla —el efecto de anclaje está bien documentado— y la
 * concordancia resultante mediría la sugestión, no el acuerdo. Por eso el DTO que viaja al
 * docente no incluye `puntuacionIa`, y no es un olvido: es el control experimental.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ValidacionJuezService {

    public static final String ORIGEN_PRACTICA = "PRACTICA";
    public static final String ORIGEN_ARIA = "ARIA";

    private final MuestraValidacionRepository muestraRepository;
    private final CalificacionDocenteRepository calificacionRepository;
    private final RespuestaUsuarioRepository respuestaUsuarioRepository;
    private final TurnoTutorSocraticoRepository turnoTutorRepository;
    private final UserRepository userRepository;
    private final AcuerdoJuezService acuerdoJuezService;

    /** Lo que ve el docente: todo menos la nota de la IA. */
    public record CasoParaCalificar(
            Long muestraId,
            String pregunta,
            String respuestaAlumno,
            String respuestaCorrecta,
            int escalaMin,
            int escalaMax,
            String origen
    ) {}

    // ------------------------------------------------------------------
    // Paso 1: armar la muestra
    // ------------------------------------------------------------------

    /**
     * Selecciona hasta {@code tamano} respuestas al azar de las que la IA ya calificó y que
     * todavía no estén en la muestra.
     *
     * El muestreo es aleatorio a propósito: elegir "las más dudosas" o "las más largas"
     * produciría una concordancia que no representa el funcionamiento normal del sistema.
     */
    @Transactional
    public int construirMuestra(String origen, int tamano) {
        List<MuestraValidacion> candidatas = switch (origen) {
            case ORIGEN_PRACTICA -> candidatasDePractica();
            case ORIGEN_ARIA -> candidatasDeAria();
            default -> throw new IllegalArgumentException(
                    "Origen no reconocido: " + origen + ". Use PRACTICA o ARIA.");
        };

        Collections.shuffle(candidatas);
        List<MuestraValidacion> seleccion = candidatas.stream().limit(Math.max(0, tamano)).toList();
        muestraRepository.saveAll(seleccion);

        log.info("[VALIDACION] Muestra '{}' ampliada con {} casos (había {} candidatas nuevas)",
                origen, seleccion.size(), candidatas.size());
        return seleccion.size();
    }

    /**
     * Respuestas abiertas de exámenes. Solo las abiertas: en una de opción múltiple la
     * corrección es una comparación de cadenas y medir el acuerdo no dice nada del juez.
     *
     * La escala es binaria (0/1) porque es lo que el flujo de práctica guarda hoy: un
     * booleano `correcta`. La escala de 1 a 4 de Aria es más informativa; ésta existe porque
     * es la que ya tiene datos históricos.
     */
    private List<MuestraValidacion> candidatasDePractica() {
        // Volumen esperado en este proyecto: decenas o cientos de filas. Si algún día crece,
        // esto debe pasar a una consulta paginada con filtro en la base.
        return respuestaUsuarioRepository.findAll().stream()
                .filter(r -> r.getPregunta() != null
                        && r.getPregunta().getTipodepregunta() == Tipo.Responder)
                .filter(r -> r.getRespuestaTexto() != null && !r.getRespuestaTexto().isBlank())
                .filter(r -> !muestraRepository.existsByOrigenAndOrigenId(ORIGEN_PRACTICA, r.getId()))
                .map(r -> {
                    MuestraValidacion m = new MuestraValidacion();
                    m.setPregunta(r.getPregunta().getPregunta());
                    m.setRespuestaAlumno(r.getRespuestaTexto());
                    m.setRespuestaCorrecta(r.getPregunta().getRespuestaCorrecta());
                    m.setPuntuacionIa(r.isCorrecta() ? 1 : 0);
                    m.setEscalaMin(0);
                    m.setEscalaMax(1);
                    m.setOrigen(ORIGEN_PRACTICA);
                    m.setOrigenId(r.getId());
                    return m;
                })
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /**
     * Turnos cerrados de tutoría con Aria, en escala de 1 a 4.
     *
     * Se excluyen los de modalidad AUDIO: no hay transcripción guardada en el servidor, así
     * que un docente no podría calificar lo que el alumno dijo.
     */
    private List<MuestraValidacion> candidatasDeAria() {
        return turnoTutorRepository.findAll().stream()
                .filter(TurnoTutorSocratico::isCerrado)
                .filter(t -> t.getPuntuacion() != null)
                .filter(t -> !"AUDIO".equalsIgnoreCase(t.getModalidad()))
                .filter(t -> t.getRespuestaEstudiante() != null && !t.getRespuestaEstudiante().isBlank())
                .filter(t -> !muestraRepository.existsByOrigenAndOrigenId(ORIGEN_ARIA, t.getId()))
                .map(t -> {
                    MuestraValidacion m = new MuestraValidacion();
                    m.setPregunta(t.getPregunta());
                    m.setRespuestaAlumno(t.getRespuestaEstudiante());
                    m.setPuntuacionIa(t.getPuntuacion());
                    m.setEscalaMin(1);
                    m.setEscalaMax(4);
                    m.setOrigen(ORIGEN_ARIA);
                    m.setOrigenId(t.getId());
                    return m;
                })
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    // ------------------------------------------------------------------
    // Paso 2: calificación a ciegas
    // ------------------------------------------------------------------

    /** Casos que este docente todavía no ha calificado, sin la nota de la IA. */
    @Transactional(readOnly = true)
    public List<CasoParaCalificar> pendientesPara(Long docenteId, String origen, int limite) {
        List<MuestraValidacion> muestras = (origen == null || origen.isBlank())
                ? muestraRepository.findAll()
                : muestraRepository.findByOrigen(origen);

        return muestras.stream()
                .filter(m -> !calificacionRepository.existsByMuestraIdAndDocenteId(m.getId(), docenteId))
                .limit(Math.max(0, limite))
                .map(m -> new CasoParaCalificar(
                        m.getId(),
                        m.getPregunta(),
                        m.getRespuestaAlumno(),
                        m.getRespuestaCorrecta(),
                        m.getEscalaMin(),
                        m.getEscalaMax(),
                        m.getOrigen()))
                .toList();
    }

    @Transactional
    public void calificar(Long muestraId, Long docenteId, int puntuacion, String comentario) {
        MuestraValidacion muestra = muestraRepository.findById(muestraId)
                .orElseThrow(() -> new IllegalArgumentException("Caso no encontrado: " + muestraId));

        if (puntuacion < muestra.getEscalaMin() || puntuacion > muestra.getEscalaMax()) {
            throw new IllegalArgumentException("La calificación debe estar entre "
                    + muestra.getEscalaMin() + " y " + muestra.getEscalaMax());
        }
        if (calificacionRepository.existsByMuestraIdAndDocenteId(muestraId, docenteId)) {
            throw new IllegalStateException("Este caso ya fue calificado por usted.");
        }

        Usuario docente = userRepository.findById(docenteId)
                .orElseThrow(() -> new IllegalArgumentException("Docente no encontrado"));

        CalificacionDocente calificacion = new CalificacionDocente();
        calificacion.setMuestra(muestra);
        calificacion.setDocente(docente);
        calificacion.setPuntuacion(puntuacion);
        calificacion.setComentario(comentario);
        calificacionRepository.save(calificacion);
    }

    // ------------------------------------------------------------------
    // Paso 3: concordancia
    // ------------------------------------------------------------------

    /**
     * Concordancia global para un origen.
     *
     * Si varios docentes calificaron el mismo caso, cada par (IA, docente) entra por
     * separado. Es la opción conservadora: promediar primero las notas de los docentes
     * suavizaría su desacuerdo entre ellos y haría parecer que la IA concuerda más de lo que
     * concuerda.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> concordancia(String origen) {
        List<CalificacionDocente> calificaciones = calificacionRepository.findByMuestraOrigen(origen);

        List<ParCalificacion> pares = calificaciones.stream()
                .map(c -> new ParCalificacion(c.getMuestra().getPuntuacionIa(), c.getPuntuacion()))
                .toList();

        int escalaMin = calificaciones.isEmpty() ? 0 : calificaciones.get(0).getMuestra().getEscalaMin();
        int escalaMax = calificaciones.isEmpty() ? 1 : calificaciones.get(0).getMuestra().getEscalaMax();

        ResultadoAcuerdo r = acuerdoJuezService.calcular(pares, escalaMin, escalaMax);

        long docentesDistintos = calificaciones.stream()
                .map(c -> c.getDocente().getId()).distinct().count();

        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("origen", origen);
        salida.put("casosEnMuestra", muestraRepository.countByOrigen(origen));
        salida.put("calificacionesRecibidas", r.n());
        salida.put("docentesParticipantes", docentesDistintos);
        salida.put("escala", escalaMin + " a " + escalaMax);
        salida.put("acuerdoExacto", redondear(r.acuerdoExacto()));
        salida.put("acuerdoAdyacente", redondear(r.acuerdoAdyacente()));
        salida.put("kappaCuadratica", redondear(r.kappaCuadratica()));
        salida.put("kappaSinPonderar", redondear(r.kappaSinPonderar()));
        salida.put("sesgoMedio", redondear(r.sesgoMedio()));
        salida.put("interpretacion", r.interpretacion());
        return salida;
    }

    /** Los desacuerdos, para poder revisarlos uno a uno. Es donde se aprende qué falla el juez. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> desacuerdos(String origen) {
        return calificacionRepository.findByMuestraOrigen(origen).stream()
                .filter(c -> c.getPuntuacion() != c.getMuestra().getPuntuacionIa())
                .map(c -> {
                    Map<String, Object> fila = new LinkedHashMap<>();
                    fila.put("muestraId", c.getMuestra().getId());
                    fila.put("pregunta", c.getMuestra().getPregunta());
                    fila.put("respuestaAlumno", c.getMuestra().getRespuestaAlumno());
                    fila.put("puntuacionIa", c.getMuestra().getPuntuacionIa());
                    fila.put("puntuacionDocente", c.getPuntuacion());
                    fila.put("docente", c.getDocente().getNombre());
                    fila.put("comentario", c.getComentario());
                    return fila;
                })
                .toList();
    }

    private double redondear(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
