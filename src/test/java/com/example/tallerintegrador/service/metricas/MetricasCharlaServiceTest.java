package com.example.tallerintegrador.service.metricas;

import com.example.tallerintegrador.entidades.postgres.SesionCharla;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetricasCharlaServiceTest {

    private static SesionCharla sesion(long alumno, boolean terminada) {
        SesionCharla s = new SesionCharla();
        s.setUsuarioId(alumno);
        s.setIniciada(LocalDateTime.now());
        if (terminada) s.setTerminada(LocalDateTime.now());
        return s;
    }

    @Test
    @SuppressWarnings("unchecked")
    void separaRecuperacionAyudasRepasoYCharlasAbandonadas() {
        List<MetricasCharlaService.Fila> filas = List.of(
                new MetricasCharlaService.Fila(1L, true, true, 0, null, false),
                new MetricasCharlaService.Fila(1L, false, true, 1, "PISTA", false),
                new MetricasCharlaService.Fila(2L, false, false, 1, "PISTA,MITAD", false),
                new MetricasCharlaService.Fila(2L, true, true, 0, "", true),
                new MetricasCharlaService.Fila(2L, false, false, 0, null, true));

        Map<String, Object> m = MetricasCharlaService.calcular(List.of(sesion(1, true), sesion(2, false)), filas);

        assertEquals(2, m.get("alumnos"));
        assertEquals(true, m.get("muestra_pequena"));
        Map<String, Object> charlas = (Map<String, Object>) m.get("charlas");
        assertEquals(1L, charlas.get("terminadas"));
        assertEquals(50.0, charlas.get("porcentaje_terminadas"));
        Map<String, Object> recuperacion = (Map<String, Object>) m.get("recuperacion");
        assertEquals(2, recuperacion.get("preguntas_con_repregunta"));
        assertEquals(1L, recuperacion.get("recuperadas"));
        assertEquals(50.0, recuperacion.get("porcentaje_recuperacion"));
        Map<String, Map<String, Object>> ayudas = (Map<String, Map<String, Object>>) m.get("ayudas");
        assertEquals(2, ayudas.get("PISTA").get("usos"));
        assertEquals(1, ayudas.get("MITAD").get("usos"));
        assertEquals(3, ayudas.get("SIN_AYUDA").get("usos"));
        Map<String, Object> repaso = (Map<String, Object>) m.get("repaso");
        assertEquals(2, repaso.get("preguntas"));
        assertEquals(50.0, repaso.get("porcentaje_retencion"));
    }
}
