package com.example.tallerintegrador.exception;

/**
 * El recurso pedido no existe. Se traduce a 404, no a 500.
 *
 * POR QUE EXISTE. El caso que la motivo: un material cuya fila sigue en PostgreSQL pero cuyo
 * archivo ya no esta en MongoDB. Eso devolvia un 500 con traza completa, indistinguible de un
 * fallo de programacion, tanto para el alumno como para quien lee los registros.
 */
public class RecursoNoEncontradoException extends RuntimeException {

    public RecursoNoEncontradoException(String mensaje) {
        super(mensaje);
    }
}
