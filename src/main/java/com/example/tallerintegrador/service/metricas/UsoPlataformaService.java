package com.example.tallerintegrador.service.metricas;

import com.example.tallerintegrador.repository.RegistroAccesoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Uso de la plataforma por los alumnos, a partir de los inicios de sesión (RegistroAcceso).
 *
 * Mide DÍAS de uso, no visitas: la sesión dura 24 horas, así que volver el mismo día sin cerrar
 * sesión no genera un registro nuevo. Para un estudio de adherencia es la unidad adecuada.
 */
@Service
@RequiredArgsConstructor
public class UsoPlataformaService {

    public static final int DIAS_MIN = 7;
    public static final int DIAS_MAX = 90;

    private final RegistroAccesoRepository repository;

    @Transactional(readOnly = true)
    public Map<String, Object> resumen(int diasSolicitados) {
        int dias = Math.max(DIAS_MIN, Math.min(DIAS_MAX, diasSolicitados));
        LocalDate hoy = LocalDate.now();
        LocalDate primerDia = hoy.minusDays(dias - 1L);
        LocalDateTime desde = primerDia.atStartOfDay();

        // Días sin actividad también aparecen, con cero: un hueco en el gráfico debe verse.
        Map<LocalDate, long[]> actividad = new HashMap<>();
        for (Object[] fila : repository.accesosPorDia(desde)) {
            actividad.put(aFecha(fila[0]), new long[]{numero(fila[1]), numero(fila[2])});
        }
        List<Map<String, Object>> porDia = new ArrayList<>();
        for (LocalDate d = primerDia; !d.isAfter(hoy); d = d.plusDays(1)) {
            long[] v = actividad.getOrDefault(d, new long[]{0, 0});
            Map<String, Object> punto = new LinkedHashMap<>();
            punto.put("fecha", d.toString());
            punto.put("alumnos", v[0]);
            punto.put("accesos", v[1]);
            porDia.add(punto);
        }

        LocalDateTime haceSieteDias = hoy.minusDays(6).atStartOfDay();
        List<Map<String, Object>> porAlumno = new ArrayList<>();
        long sinIngresar = 0;
        long activos7 = 0;
        long sumaDias = 0;
        for (Object[] fila : repository.usoPorAlumno(desde)) {
            long diasDeUso = numero(fila[2]);
            LocalDateTime ultimo = aFechaHora(fila[4]);
            if (diasDeUso == 0) sinIngresar++;
            if (ultimo != null && !ultimo.isBefore(haceSieteDias)) activos7++;
            sumaDias += diasDeUso;

            Map<String, Object> alumno = new LinkedHashMap<>();
            alumno.put("nombre", fila[0]);
            alumno.put("correo", fila[1]);
            alumno.put("diasDeUso", diasDeUso);
            alumno.put("accesos", numero(fila[3]));
            alumno.put("ultimoAcceso", ultimo == null ? null : ultimo.toString());
            porAlumno.add(alumno);
        }

        long totalAlumnos = porAlumno.size();
        long activosHoy = porDia.isEmpty() ? 0 : (long) porDia.get(porDia.size() - 1).get("alumnos");

        Map<String, Object> cifras = new LinkedHashMap<>();
        cifras.put("totalAlumnos", totalAlumnos);
        cifras.put("activosHoy", activosHoy);
        cifras.put("activosUltimos7Dias", activos7);
        cifras.put("promedioDiasDeUso", totalAlumnos == 0 ? 0.0
                : Math.round((double) sumaDias / totalAlumnos * 10.0) / 10.0);
        cifras.put("sinIngresarEnElPeriodo", sinIngresar);

        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("dias", dias);
        salida.put("desde", primerDia.toString());
        salida.put("hasta", hoy.toString());
        salida.put("cifras", cifras);
        salida.put("porDia", porDia);
        salida.put("porAlumno", porAlumno);
        return salida;
    }

    private static long numero(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static LocalDate aFecha(Object o) {
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof LocalDate d) return d;
        return LocalDate.parse(String.valueOf(o).substring(0, 10));
    }

    private static LocalDateTime aFechaHora(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        if (o instanceof LocalDateTime t) return t;
        if (o instanceof java.time.OffsetDateTime t) return t.toLocalDateTime();
        if (o instanceof java.time.Instant t) return LocalDateTime.ofInstant(t, java.time.ZoneId.systemDefault());
        return LocalDateTime.parse(String.valueOf(o).replace(' ', 'T'));
    }
}
