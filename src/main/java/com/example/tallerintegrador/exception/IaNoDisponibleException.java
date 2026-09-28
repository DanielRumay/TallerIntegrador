package com.example.tallerintegrador.exception;

/**
 * El modelo de lenguaje no respondio tras agotar reintentos y modelo de respaldo.
 *
 * POR QUE UNA EXCEPCION PROPIA. Antes este fallo viajaba como RuntimeException generica y el
 * alumno recibia un 500 indistinguible de un error de programacion. Al tener tipo propio, el
 * manejador puede devolver 503 con un codigo que la interfaz reconoce, y mostrar que Aria esta
 * descansando en lugar de una pantalla de error.
 *
 * LIMITE QUE ESTO NO RESUELVE. No hay modo degradado: sin modelo no se generan reactivos nuevos
 * ni se califican respuestas abiertas. Esta excepcion solo hace honesto y legible el fallo.
 */
public class IaNoDisponibleException extends RuntimeException {

    public IaNoDisponibleException(String mensaje) {
        super(mensaje);
    }

    public IaNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
