package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.*;
import com.example.tallerintegrador.repository.IntentoRepository;
import com.example.tallerintegrador.repository.RespuestaUsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RendimientoService {

    private final IntentoRepository intentoRepository;
    private final RespuestaUsuarioRepository respuestaUsuarioRepository;

    /**
     * Genera el mapa de calor de conocimiento para un alumno.
     * Agrupa sus respuestas por semana y calcula el % de aciertos.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> obtenerMapaCalor(Long usuarioId) {
        log.info("[RENDIMIENTO] Generando mapa de calor para usuario ID={}", usuarioId);

        // Incluye cualquier intento que no sea DIAGNOSTICA (permite formativas, refuerzos y nulls)
        List<Intento> intentos = intentoRepository.findByUsuarioIdOrderByFechaDesc(usuarioId).stream()
                .filter(i -> i.getTipoEvaluacion() == null || i.getTipoEvaluacion() != TipoEvaluacion.DIAGNOSTICA)
                .collect(Collectors.toList());
        if (intentos.isEmpty()) {
            return List.of();
        }

        // Obtener todas las semanas únicas donde el alumno tiene intentos
        Set<Long> semanaIds = intentos.stream()
                .map(i -> i.getSemana().getId())
                .collect(Collectors.toSet());

        List<Map<String, Object>> resultado = new ArrayList<>();

        // Una sola consulta agregada para todas las semanas, en vez de cargar cada respuesta
        // con su pregunta (ver RespuestaUsuarioRepository.resumenPorSemana).
        Map<Long, long[]> resumen = new HashMap<>();
        for (Object[] fila : respuestaUsuarioRepository.resumenPorSemana(usuarioId, TipoEvaluacion.DIAGNOSTICA)) {
            long total = fila[1] == null ? 0 : ((Number) fila[1]).longValue();
            long aciertos = fila[2] == null ? 0 : ((Number) fila[2]).longValue();
            resumen.put(((Number) fila[0]).longValue(), new long[]{total, aciertos});
        }

        // Las semanas ya vienen cargadas con los intentos: no hace falta volver a pedirlas.
        Map<Long, Semana> semanasPorId = new HashMap<>();
        intentos.forEach(it -> semanasPorId.putIfAbsent(it.getSemana().getId(), it.getSemana()));

        for (Long semanaId : semanaIds) {
            long[] datos = resumen.get(semanaId);
            if (datos == null || datos[0] == 0) continue;

            Semana semana = semanasPorId.get(semanaId);
            Curso curso = semana.getCurso();

            long totalPreguntas = datos[0];
            long correctas = datos[1];
            double porcentaje = totalPreguntas > 0 ? (double) correctas / totalPreguntas * 100.0 : 0.0;

            String nivel;
            if (totalPreguntas == 0) {
                nivel = "SIN_DATOS";
            } else if (porcentaje >= 75) {
                nivel = "DOMINADO";
            } else if (porcentaje >= 50) {
                nivel = "EN_PROGRESO";
            } else {
                nivel = "DEBIL";
            }

            // Calcular promedio de notas en esta semana
            List<Intento> intentosSemana = intentos.stream()
                    .filter(i -> i.getSemana().getId().equals(semanaId))
                    .toList();
            double promedioNota = intentosSemana.stream()
                    .filter(i -> i.getNota() != null)
                    .mapToDouble(Intento::getNota)
                    .average()
                    .orElse(0.0);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("semanaId", semanaId);
            entry.put("numSem", semana.getNumSem());
            entry.put("cursoId", curso != null ? curso.getId() : null);
            entry.put("cursoNombre", curso != null ? curso.getNombre() : "Sin curso");
            entry.put("cursoColor", curso != null ? curso.getColor() : "primary");
            entry.put("cursoEmoji", curso != null ? curso.getEmoji() : "📚");
            entry.put("totalPreguntas", totalPreguntas);
            entry.put("correctas", correctas);
            entry.put("porcentaje", Math.round(porcentaje * 10.0) / 10.0);
            entry.put("promedioNota", Math.round(promedioNota * 10.0) / 10.0);
            entry.put("totalIntentos", intentosSemana.size());
            entry.put("nivel", nivel);

            resultado.add(entry);
        }

        // Ordenar por curso y luego por número de semana
        resultado.sort(Comparator
                .comparing((Map<String, Object> m) -> (String) m.get("cursoNombre"))
                .thenComparing(m -> {
                    String numSem = (String) m.get("numSem");
                    try {
                        return Integer.parseInt(numSem.replaceAll("\\D", ""));
                    } catch (Exception e) {
                        return 0;
                    }
                }));

        log.info("[RENDIMIENTO] Mapa de calor generado con {} entradas para usuario ID={}", resultado.size(), usuarioId);
        return resultado;
    }
}
