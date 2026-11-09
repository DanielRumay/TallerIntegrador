package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.Matricula;
import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.repository.MatriculaRepository;
import com.example.tallerintegrador.repository.RespuestaUsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.IsoFields;
import java.util.*;

/**
 * Jefe de la semana: una batalla COOPERATIVA del salón.
 *
 * Cada semana, cada curso tiene un "jefe" con una barra de vida. Cada respuesta correcta de
 * cualquier alumno del curso le quita un punto. Si lo derrotan antes del domingo, quienes
 * aportaron al menos un golpe reciben un cofre de monedas.
 *
 * Por qué cooperativo y no otra competencia: la liga ya premia al que más practica; el jefe
 * suma el esfuerzo de todos, así el alumno que va peor también ayuda a su salón (es la
 * mecánica de "boss battle" de Classcraft, cuyos estudios destacan el trabajo en equipo).
 *
 * La vida escala con el tamaño del salón (6 aciertos por alumno, mínimo 20): un jefe fijo sería
 * imposible para un salón pequeño y trivial para uno grande. Todo se calcula desde las
 * respuestas guardadas; solo el cofre queda en el libro de movimientos.
 */
@Service
@RequiredArgsConstructor
public class JefeSemanalService {

    private final MatriculaRepository matriculaRepository;
    private final RespuestaUsuarioRepository respuestaRepository;
    private final MonedasService monedasService;

    static final int VIDA_POR_ALUMNO = 6;
    static final int VIDA_MINIMA = 20;
    static final int COFRE = 30;
    static final int SEMANAS_COFRE = 4;

    public record Jefe(String id, String nombre, String lema) {}

    /** Rotan por semana del año: cada lunes llega uno distinto. */
    static final List<Jefe> JEFES = List.of(
            new Jefe("olvido", "Gólem del Olvido", "Se alimenta de lo que no repasas."),
            new Jefe("duda", "Pulpo de la Duda", "Enreda las respuestas con sus tentáculos."),
            new Jefe("pereza", "Dragón de la Pereza", "Duerme sobre las tareas sin hacer."),
            new Jefe("distraccion", "Murciélago Distraído", "Revolotea para que pierdas el hilo."));

    static Jefe jefeDe(LocalDate lunes) {
        return JEFES.get(lunes.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) % JEFES.size());
    }

    static int vidaPara(int alumnos) {
        return Math.max(VIDA_MINIMA, alumnos * VIDA_POR_ALUMNO);
    }

    /** Estado de la batalla de esta semana en un curso del alumno (con cursoId null, su primer curso). */
    public Optional<Map<String, Object>> estado(Long usuarioId, Long cursoId) {
        List<Matricula> mias = matriculaRepository.findByUsuarioId(usuarioId);
        if (mias.isEmpty()) return Optional.empty();
        Matricula m = cursoId == null ? mias.get(0)
                : mias.stream().filter(x -> x.getCurso().getId().equals(cursoId)).findFirst().orElse(null);
        if (m == null) return Optional.empty();

        cobrarCofresPendientes(usuarioId, mias);

        LocalDateTime lunes = GamificacionService.inicioDeSemana(LocalDate.now());
        Batalla b = batalla(m, lunes);
        Jefe jefe = jefeDe(lunes.toLocalDate());

        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("cursoId", m.getCurso().getId());
        salida.put("curso", m.getCurso().getNombre());
        salida.put("jefe", jefe.id());
        salida.put("nombre", jefe.nombre());
        salida.put("lema", jefe.lema());
        salida.put("vidaMaxima", b.vida());
        salida.put("danio", Math.min(b.danio(), b.vida()));
        salida.put("derrotado", b.derrotado());
        salida.put("miAporte", b.aportes().getOrDefault(usuarioId, 0L));
        salida.put("colaboradores", b.aportes().values().stream().filter(v -> v > 0).count());
        salida.put("alumnos", b.alumnos());
        salida.put("cofre", COFRE);
        salida.put("hasta", lunes.toLocalDate().plusDays(6).toString());
        return Optional.of(salida);
    }

    record Batalla(int alumnos, int vida, long danio, Map<Long, Long> aportes) {
        boolean derrotado() { return danio >= vida; }
    }

    Batalla batalla(Matricula m, LocalDateTime lunes) {
        Long colegioId = m.getCurso().getColegio() == null ? null : m.getCurso().getColegio().getId();
        Set<Long> alumnos = new HashSet<>();
        for (Matricula x : matriculaRepository.findByCursoId(m.getCurso().getId())) {
            if (x.getUsuario().getRol() == Rol.STUDENT && Objects.equals(x.getUsuario().colegioId(), colegioId)) {
                alumnos.add(x.getUsuario().getId());
            }
        }
        Map<Long, Long> aportes = new HashMap<>();
        for (Object[] fila : respuestaRepository.aciertosPorAlumnoEnCurso(m.getCurso().getId(), lunes, lunes.plusWeeks(1))) {
            Long id = (Long) fila[0];
            if (alumnos.contains(id)) aportes.put(id, ((Number) fila[1]).longValue());
        }
        long danio = aportes.values().stream().mapToLong(Long::longValue).sum();
        return new Batalla(alumnos.size(), vidaPara(alumnos.size()), danio, aportes);
    }

    /**
     * Cofre de las batallas ganadas (esta semana y las 4 anteriores) a las que el alumno aportó.
     * Se revisa al abrir la tarjeta; la clave impide cobrarlo dos veces.
     */
    void cobrarCofresPendientes(Long usuarioId, List<Matricula> mias) {
        LocalDateTime estaSemana = GamificacionService.inicioDeSemana(LocalDate.now());
        for (Matricula m : mias) {
            for (int s = 0; s <= SEMANAS_COFRE; s++) {
                LocalDateTime lunes = estaSemana.minusWeeks(s);
                Batalla b = batalla(m, lunes);
                if (b.derrotado() && b.aportes().getOrDefault(usuarioId, 0L) > 0) {
                    monedasService.premiar(usuarioId, COFRE,
                            "Cofre: derrotaron a " + jefeDe(lunes.toLocalDate()).nombre() + " en " + m.getCurso().getNombre(),
                            "jefe:" + m.getCurso().getId() + ":" + lunes.toLocalDate());
                }
            }
        }
    }
}
