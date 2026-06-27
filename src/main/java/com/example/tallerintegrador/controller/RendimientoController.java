package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.entidades.postgres.Intento;
import com.example.tallerintegrador.entidades.postgres.RespuestaUsuario;
import com.example.tallerintegrador.repository.IntentoRepository;
import com.example.tallerintegrador.repository.RespuestaUsuarioRepository;
import com.example.tallerintegrador.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/rendimiento")
@RequiredArgsConstructor
public class RendimientoController {

    private final IntentoRepository intentoRepository;
    private final RespuestaUsuarioRepository respuestaUsuarioRepository;
    private final UserRepository userRepository;

    /**
     * GET /api/rendimiento/mapa-calor/{usuarioId}
     * Devuelve un mapa de calor con la actividad del alumno por semana del anio.
     * Cada entrada contiene: fecha (YYYY-MM-DD), intentos, nota promedio, correctas, total.
     */
    @PreAuthorize("hasAuthority('STUDENT') or hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/mapa-calor/{usuarioId}")
    public ResponseEntity<?> mapaCalor(@PathVariable Long usuarioId) {
        try {
            userRepository.findById(usuarioId)
                    .orElseThrow(() -> new RuntimeException("Usuario no encontrado: " + usuarioId));

            List<Intento> intentos = intentoRepository.findByUsuarioIdOrderByFechaDesc(usuarioId);

            // Agrupar por fecha (truncada al dia)
            Map<LocalDate, List<Intento>> porFecha = new LinkedHashMap<>();
            for (Intento intento : intentos) {
                if (intento.getFecha() == null) continue;
                LocalDate fecha = intento.getFecha().toLocalDate();
                porFecha.computeIfAbsent(fecha, k -> new ArrayList<>()).add(intento);
            }

            List<Map<String, Object>> resultado = new ArrayList<>();
            for (Map.Entry<LocalDate, List<Intento>> entry : porFecha.entrySet()) {
                List<Intento> diaIntentos = entry.getValue();

                double notaPromedio = diaIntentos.stream()
                        .mapToDouble(i -> i.getNota() != null ? i.getNota() : 0.0)
                        .average().orElse(0.0);

                // Contar respuestas correctas e incorrectas del dia
                int totalRespuestas = 0;
                int correctas = 0;
                for (Intento intento : diaIntentos) {
                    List<RespuestaUsuario> respuestas = respuestaUsuarioRepository.findByIntentoId(intento.getId());
                    totalRespuestas += respuestas.size();
                    correctas += (int) respuestas.stream().filter(RespuestaUsuario::isCorrecta).count();
                }

                Map<String, Object> entry2 = new LinkedHashMap<>();
                entry2.put("fecha", entry.getKey().toString());
                entry2.put("intentos", diaIntentos.size());
                entry2.put("nota_promedio", Math.round(notaPromedio * 100.0) / 100.0);
                entry2.put("correctas", correctas);
                entry2.put("total_respuestas", totalRespuestas);
                // Nivel de actividad: 0=ninguno, 1=bajo, 2=medio, 3=alto (para colorear el heatmap)
                int nivel = diaIntentos.size() == 0 ? 0
                        : diaIntentos.size() == 1 ? 1
                        : diaIntentos.size() <= 3 ? 2 : 3;
                entry2.put("nivel_actividad", nivel);
                resultado.add(entry2);
            }

            // Ordenar por fecha ascendente
            resultado.sort(Comparator.comparing(m -> m.get("fecha").toString()));

            return ResponseEntity.ok(resultado);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/rendimiento/resumen/{usuarioId}
     * Devuelve estadisticas globales del alumno: nota promedio, total intentos,
     * semanas evaluadas, porcentaje de aciertos.
     */
    @PreAuthorize("hasAuthority('STUDENT') or hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/resumen/{usuarioId}")
    public ResponseEntity<?> resumenRendimiento(@PathVariable Long usuarioId) {
        try {
            userRepository.findById(usuarioId)
                    .orElseThrow(() -> new RuntimeException("Usuario no encontrado: " + usuarioId));

            List<Intento> intentos = intentoRepository.findByUsuarioIdOrderByFechaDesc(usuarioId);

            if (intentos.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "totalIntentos", 0,
                        "notaPromedio", 0.0,
                        "semanasEvaluadas", 0,
                        "porcentajeAciertos", 0.0
                ));
            }

            double notaPromedio = intentos.stream()
                    .mapToDouble(i -> i.getNota() != null ? i.getNota() : 0.0)
                    .average().orElse(0.0);

            long semanasDistintas = intentos.stream()
                    .map(i -> i.getSemana().getId())
                    .distinct().count();

            int totalResp = 0;
            int totalCorrectas = 0;
            for (Intento intento : intentos) {
                List<RespuestaUsuario> respuestas = respuestaUsuarioRepository.findByIntentoId(intento.getId());
                totalResp += respuestas.size();
                totalCorrectas += (int) respuestas.stream().filter(RespuestaUsuario::isCorrecta).count();
            }

            double porcentaje = totalResp > 0 ? (totalCorrectas * 100.0) / totalResp : 0.0;

            Map<String, Object> resumen = new LinkedHashMap<>();
            resumen.put("totalIntentos", intentos.size());
            resumen.put("notaPromedio", Math.round(notaPromedio * 100.0) / 100.0);
            resumen.put("semanasEvaluadas", semanasDistintas);
            resumen.put("porcentajeAciertos", Math.round(porcentaje * 100.0) / 100.0);

            return ResponseEntity.ok(resumen);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
