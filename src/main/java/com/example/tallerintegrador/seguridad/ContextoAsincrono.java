package com.example.tallerintegrador.seguridad;

import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;

import java.util.concurrent.CompletableFuture;

/**
 * CompletableFuture.runAsync que conserva quién hizo la petición.
 *
 * POR QUE. El SecurityContext vive en el hilo de la petición; runAsync ejecuta en otro hilo del
 * pool y allí ContextoUsuario.actual() devolvía null. Consecuencia concreta: la telemetría del
 * juez y de la recuperación RAG, que corre en esos hilos, quedaba sin colegio y desaparecía de
 * las métricas del administrador. DelegatingSecurityContextRunnable copia el contexto al hilo
 * de fondo y lo limpia al terminar, así que el pool no arrastra la sesión de otra petición.
 */
public final class ContextoAsincrono {

    private ContextoAsincrono() {}

    public static CompletableFuture<Void> runAsync(Runnable tarea) {
        return CompletableFuture.runAsync(new DelegatingSecurityContextRunnable(tarea));
    }
}
