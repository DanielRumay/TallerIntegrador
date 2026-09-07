package com.example.tallerintegrador.service.metricas;

import com.example.tallerintegrador.entidades.postgres.EventoMetricaIA;
import com.example.tallerintegrador.entidades.postgres.TipoEventoIA;
import com.example.tallerintegrador.repository.EventoMetricaIARepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Punto único de escritura de la telemetría del pipeline de IA.
 *
 * Regla de diseño: instrumentar nunca puede tumbar el flujo pedagógico. Cada registro va en
 * su propia transacción (REQUIRES_NEW) y cualquier fallo se traga con un warning, de modo que
 * un problema de la tabla de métricas no impida calificar a un alumno.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetriaIAService {

    private final EventoMetricaIARepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrar(EventoMetricaIA evento) {
        try {
            repository.save(evento);
        } catch (Exception e) {
            log.warn("[TELEMETRIA] No se pudo registrar el evento {}: {}", evento.getTipo(), e.getMessage());
        }
    }

    /** Registro compacto para los casos que solo necesitan tipo + etiqueta + usuario. */
    public void registrar(TipoEventoIA tipo, String etiqueta, Long usuarioId) {
        EventoMetricaIA e = EventoMetricaIA.de(tipo, etiqueta);
        e.setUsuarioId(usuarioId);
        registrar(e);
    }

    public void registrar(TipoEventoIA tipo, String etiqueta, Long usuarioId, Double valor) {
        EventoMetricaIA e = EventoMetricaIA.de(tipo, etiqueta);
        e.setUsuarioId(usuarioId);
        e.setValor(valor);
        registrar(e);
    }
}
