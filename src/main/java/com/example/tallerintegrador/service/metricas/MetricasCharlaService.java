package com.example.tallerintegrador.service.metricas;

import com.example.tallerintegrador.entidades.postgres.SesionCharla;
import com.example.tallerintegrador.repository.RespuestaUsuarioRepository;
import com.example.tallerintegrador.repository.SesionCharlaRepository;
import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.seguridad.ColegioGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Indicadores de la charla con Aria, siempre agregados: ninguna salida identifica a un alumno.
 * Una "recuperación" es una pregunta fallada al primer intento que el alumno resolvió tras una
 * repregunta; mide lo que aporta el diálogo y no que el alumno domine el concepto.
 */
@Service
@RequiredArgsConstructor
public class MetricasCharlaService {

    /** Por debajo de esta cantidad de alumnos los porcentajes no deben publicarse como tendencia. */
    static final int ALUMNOS_MINIMOS = 5;
    private static final List<String> AYUDAS = List.of("PISTA", "CONGELAR", "MITAD", "TIEMPO");

    private final SesionCharlaRepository sesiones;
    private final RespuestaUsuarioRepository respuestas;
    private final UserRepository usuarios;
    private final MetricasIAService metricasIA;

    public record Fila(Long usuarioId, boolean primerIntento, boolean aciertoFinal, int repreguntas, String ayudas, boolean repaso) {}

    @Transactional(readOnly = true)
    public Map<String, Object> resumen(LocalDateTime desde, LocalDateTime hasta) {
        Long colegio = ColegioGuard.colegioActual();
        Set<Long> delColegio = colegio == null ? null : usuarios.idsDelColegio(colegio);

        List<SesionCharla> sesionesVentana = sesiones.findByIniciadaBetween(desde, hasta).stream()
                .filter(s -> delColegio == null || delColegio.contains(s.getUsuarioId())).toList();
        List<Fila> filas = respuestas.filasDeCharla(desde, hasta).stream()
                .map(MetricasCharlaService::aFila)
                .filter(f -> delColegio == null || delColegio.contains(f.usuarioId())).toList();

        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("ventana", Map.of("desde", desde.toString(), "hasta", hasta.toString()));
        salida.putAll(calcular(sesionesVentana, filas));

        Map<String, Object> consumo = metricasIA.consumoCharla(desde, hasta);
        double costo = ((Number) consumo.get("costo_total_usd")).doubleValue();
        salida.put("consumo_ia", consumo);
        salida.put("costo_por_charla_usd", sesionesVentana.isEmpty() ? null
                : Math.round(costo / sesionesVentana.size() * 1_000_000d) / 1_000_000d);
        return salida;
    }

    private static Fila aFila(Object[] f) {
        return new Fila((Long) f[0], Boolean.TRUE.equals(f[1]), Boolean.TRUE.equals(f[2]),
                f[3] == null ? 0 : (Integer) f[3], (String) f[4], Boolean.TRUE.equals(f[5]));
    }

    static Map<String, Object> calcular(List<SesionCharla> sesiones, List<Fila> filas) {
        Map<String, Object> salida = new LinkedHashMap<>();
        Set<Long> alumnos = sesiones.stream().map(SesionCharla::getUsuarioId).collect(Collectors.toSet());
        alumnos.addAll(filas.stream().map(Fila::usuarioId).collect(Collectors.toSet()));
        salida.put("alumnos", alumnos.size());
        salida.put("muestra_pequena", alumnos.size() < ALUMNOS_MINIMOS);

        long terminadas = sesiones.stream().filter(s -> s.getTerminada() != null).count();
        salida.put("charlas", Map.of("iniciadas", sesiones.size(), "terminadas", terminadas,
                "porcentaje_terminadas", porcentaje(terminadas, sesiones.size())));

        long primerIntento = filas.stream().filter(Fila::primerIntento).count();
        salida.put("respuestas", Map.of("total", filas.size(), "acierto_primer_intento", primerIntento,
                "porcentaje_primer_intento", porcentaje(primerIntento, filas.size())));

        List<Fila> conRepregunta = filas.stream().filter(f -> f.repreguntas() > 0).toList();
        long recuperadas = conRepregunta.stream().filter(Fila::aciertoFinal).count();
        salida.put("recuperacion", Map.of("preguntas_con_repregunta", conRepregunta.size(), "recuperadas", recuperadas,
                "porcentaje_recuperacion", porcentaje(recuperadas, conRepregunta.size())));

        Map<String, Object> ayudas = new LinkedHashMap<>();
        for (String ayuda : AYUDAS) {
            List<Fila> usaron = filas.stream().filter(f -> usa(f, ayuda)).toList();
            long acertaron = usaron.stream().filter(Fila::aciertoFinal).count();
            ayudas.put(ayuda, Map.of("usos", usaron.size(), "porcentaje_acierto_final", porcentaje(acertaron, usaron.size())));
        }
        List<Fila> sinAyuda = filas.stream().filter(f -> f.ayudas() == null || f.ayudas().isBlank()).toList();
        ayudas.put("SIN_AYUDA", Map.of("usos", sinAyuda.size(),
                "porcentaje_acierto_final", porcentaje(sinAyuda.stream().filter(Fila::aciertoFinal).count(), sinAyuda.size())));
        salida.put("ayudas", ayudas);

        List<Fila> repaso = filas.stream().filter(Fila::repaso).toList();
        long repasoBien = repaso.stream().filter(Fila::primerIntento).count();
        salida.put("repaso", Map.of("preguntas", repaso.size(), "acierto_primer_intento", repasoBien,
                "porcentaje_retencion", porcentaje(repasoBien, repaso.size())));
        return salida;
    }

    private static boolean usa(Fila f, String ayuda) {
        return f.ayudas() != null && Arrays.asList(f.ayudas().split(",")).contains(ayuda);
    }

    private static double porcentaje(long parte, long total) {
        return total == 0 ? 0.0 : Math.round(parte * 1000.0 / total) / 10.0;
    }
}
