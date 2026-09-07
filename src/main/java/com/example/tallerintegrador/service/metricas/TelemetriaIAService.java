package com.example.tallerintegrador.service.metricas;

import com.example.tallerintegrador.entidades.postgres.EventoMetricaIA;
import com.example.tallerintegrador.entidades.postgres.TipoEventoIA;
import com.example.tallerintegrador.repository.EventoMetricaIARepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Punto único de escritura de la telemetría del canal de IA.
 *
 * REGLA DE DISEÑO: instrumentar nunca puede tumbar el flujo pedagógico. Un problema de la
 * tabla de métricas no debe impedir que un alumno reciba su evaluación.
 *
 * POR QUÉ ESTO USA TransactionTemplate Y NO @Transactional(REQUIRES_NEW).
 *
 * Antes lo hacía con la anotación, y no servía de nada: los métodos cortos de abajo llamaban
 * a `registrar(evento)` sobre `this`, y una llamada interna NO pasa por el proxy de Spring,
 * que es quien aplica la anotación. El INSERT terminaba ejecutándose dentro de la transacción
 * de quien llamaba.
 *
 * Cuando esa transacción era de sólo lectura — el caso de la prueba de ubicación —, Postgres
 * rechazaba el INSERT con "no se puede ejecutar INSERT en una transacción de sólo lectura"
 * (SQLState 25006), y a partir de ahí la transacción quedaba ABORTADA: toda sentencia
 * posterior fallaba con 25P02 y la petición entera moría con
 * "Transaction silently rolled back because it has been marked as rollback-only" — un 400 al
 * alumno por culpa de una métrica.
 *
 * El `try/catch` no salvaba nada, y conviene entender por qué: atrapar la excepción en Java
 * no desenvenena una transacción de Postgres. Una vez que una sentencia falla dentro del
 * bloque, el bloque entero está perdido.
 *
 * `TransactionTemplate` abre la transacción de forma explícita, sin depender de que la
 * llamada venga de fuera de la clase. Funciona igual la llame quien la llame.
 */
@Slf4j
@Service
public class TelemetriaIAService {

    private final EventoMetricaIARepository repository;
    private final TransactionTemplate transaccionAparte;

    public TelemetriaIAService(EventoMetricaIARepository repository,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transaccionAparte = new TransactionTemplate(transactionManager);
        // Transacción propia y separada: lo que pase aquí dentro no toca a quien nos llamó.
        this.transaccionAparte.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void registrar(EventoMetricaIA evento) {
        try {
            transaccionAparte.executeWithoutResult(estado -> repository.save(evento));
        } catch (Exception e) {
            // Ahora sí es seguro tragárselo: el fallo ocurrió en una transacción propia que
            // se revierte sola, sin arrastrar la del flujo pedagógico.
            log.warn("[TELEMETRIA] No se pudo registrar el evento {}: {}",
                    evento.getTipo(), e.getMessage());
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
