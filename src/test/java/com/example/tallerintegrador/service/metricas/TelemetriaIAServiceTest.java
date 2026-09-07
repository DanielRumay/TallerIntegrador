package com.example.tallerintegrador.service.metricas;

import com.example.tallerintegrador.entidades.postgres.EventoMetricaIA;
import com.example.tallerintegrador.entidades.postgres.TipoEventoIA;
import com.example.tallerintegrador.repository.EventoMetricaIARepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regresion de un fallo que tumbo la prueba de ubicacion entera.
 *
 * La telemetria se anotaba con @Transactional(REQUIRES_NEW), pero los metodos cortos llamaban
 * al largo sobre `this`. Una llamada interna no pasa por el proxy de Spring, asi que la
 * anotacion no hacia nada y el INSERT caia dentro de la transaccion de quien llamaba. Si esa
 * era de solo lectura, Postgres la abortaba (25006) y la peticion moria con un 400 — al
 * alumno se le negaba su evaluacion por culpa de una metrica.
 *
 * Por eso estas pruebas atacan el camino de la llamada INTERNA, que es justo el que estaba
 * roto y el que una prueba del metodo publico "largo" no habria cubierto.
 */
class TelemetriaIAServiceTest {

    private EventoMetricaIARepository repositorio;
    private PlatformTransactionManager gestorTransacciones;
    private TelemetriaIAService servicio;

    @BeforeEach
    void setUp() {
        repositorio = mock(EventoMetricaIARepository.class);
        gestorTransacciones = mock(PlatformTransactionManager.class);
        when(gestorTransacciones.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        servicio = new TelemetriaIAService(repositorio, gestorTransacciones);
    }

    @Test
    @DisplayName("El atajo de tres argumentos tambien abre su propia transaccion")
    void elAtajoAbreTransaccionPropia() {
        servicio.registrar(TipoEventoIA.GENERACION_PREGUNTA, "CONFORME", 7L);

        ArgumentCaptor<TransactionDefinition> definicion =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(gestorTransacciones).getTransaction(definicion.capture());

        // Este es EL punto: con la anotacion, esta llamada interna no abria transaccion
        // ninguna y el insert se colaba en la del que llamaba.
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                definicion.getValue().getPropagationBehavior(),
                "la escritura de telemetria debe ir en una transaccion aparte");
        verify(repositorio).save(any(EventoMetricaIA.class));
    }

    @Test
    @DisplayName("El atajo con valor tambien, no solo el de tres argumentos")
    void elAtajoConValorTambien() {
        servicio.registrar(TipoEventoIA.GENERACION_PREGUNTA, "CONFORME", 7L, 0.87);

        ArgumentCaptor<TransactionDefinition> definicion =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(gestorTransacciones).getTransaction(definicion.capture());

        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                definicion.getValue().getPropagationBehavior());
    }

    @Test
    @DisplayName("Si la escritura falla, NO se propaga: la clase nunca tumba el flujo")
    void unFalloDeTelemetriaNoSePropaga() {
        when(repositorio.save(any(EventoMetricaIA.class)))
                .thenThrow(new RuntimeException("tabla caida"));

        // Instrumentar no puede costarle la evaluacion a un alumno.
        assertDoesNotThrow(() ->
                servicio.registrar(TipoEventoIA.GENERACION_PREGUNTA, "CONFORME", 7L));
    }

    @Test
    @DisplayName("El evento guardado lleva el usuario y el valor que se le pasaron")
    void elEventoLlevaLosDatos() {
        servicio.registrar(TipoEventoIA.GENERACION_PREGUNTA, "CONFORME", 7L, 0.87);

        ArgumentCaptor<EventoMetricaIA> evento = ArgumentCaptor.forClass(EventoMetricaIA.class);
        verify(repositorio).save(evento.capture());

        assertEquals(7L, evento.getValue().getUsuarioId());
        assertEquals(0.87, evento.getValue().getValor());
        assertEquals("CONFORME", evento.getValue().getEtiqueta());
    }
}
