package com.example.tallerintegrador.service.ia;

import com.example.tallerintegrador.exception.IaNoDisponibleException;
import com.example.tallerintegrador.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * El 30/09/2026 la aplicacion dejo de responder durante ocho horas sin caerse: cuatro llamadas
 * de embedding a Gemini se quedaron esperando sin tope, cada una reteniendo un hilo de Tomcat,
 * y con el pool en diez el servicio se quedo sin hilos para nada mas. El contenedor seguia vivo
 * y el navegador en "pending".
 *
 * Estas pruebas verifican lo unico que rompe esa cadena: que una llamada colgada SE ABANDONE y
 * devuelva el control al hilo que llamaba, en vez de esperar indefinidamente.
 */
class TopeDeEsperaTest {

    private GeminiService servicioConTope(int segundos) {
        GeminiService s = new GeminiService(null, mock(UserRepository.class));
        ReflectionTestUtils.setField(s, "tiempoLimiteSegundos", segundos);
        return s;
    }

    private <T> T invocar(GeminiService s, java.util.function.Supplier<T> llamada) {
        return ReflectionTestUtils.invokeMethod(s, "conTopeDeEspera", llamada, "probar");
    }

    @Test
    @DisplayName("Una llamada que se cuelga se abandona y no retiene el hilo que la pidio")
    void abandonaLaLlamadaColgada() {
        GeminiService s = servicioConTope(1);
        CountDownLatch nuncaSeLibera = new CountDownLatch(1);

        long inicio = System.currentTimeMillis();
        assertThatThrownBy(() -> invocar(s, () -> {
            try {
                nuncaSeLibera.await();          // simula la llamada remota que no vuelve jamas
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "jamas llega";
        })).isInstanceOf(IaNoDisponibleException.class);

        long transcurrido = System.currentTimeMillis() - inicio;
        // Lo esencial: el control VUELVE. Si esto fallara, el hilo quedaria atrapado como en produccion.
        assertThat(Duration.ofMillis(transcurrido)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("El mensaje identifica que se estaba intentando, para poder diagnosticarlo")
    void elMensajeDiceQueFallo() {
        GeminiService s = servicioConTope(1);
        CountDownLatch colgada = new CountDownLatch(1);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                s, "conTopeDeEspera",
                (java.util.function.Supplier<String>) () -> {
                    try { colgada.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return "x";
                },
                "generar el embedding"))
                .isInstanceOf(IaNoDisponibleException.class)
                .hasMessageContaining("generar el embedding")
                .hasMessageContaining("1 s");
    }

    @Test
    @DisplayName("Una llamada normal pasa sin estorbo")
    void laLlamadaRapidaNoSeToca() {
        GeminiService s = servicioConTope(5);
        assertThat(this.<String>invocar(s, () -> "respuesta")).isEqualTo("respuesta");
    }

    @Test
    @DisplayName("Un fallo real de la API se propaga tal cual, no se disfraza de tiempo agotado")
    void elFalloRealNoSeConfundeConUnCuelgue() {
        GeminiService s = servicioConTope(5);
        assertThatThrownBy(() -> invocar(s, () -> {
            throw new IllegalStateException("429 quota excedida");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("429");
    }
}
