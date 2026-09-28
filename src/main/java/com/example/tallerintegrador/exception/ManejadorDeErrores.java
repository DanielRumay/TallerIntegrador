package com.example.tallerintegrador.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Traduce los fallos de la capa de IA a una respuesta que la interfaz sepa interpretar.
 *
 * El codigo IA_NO_DISPONIBLE es el contrato con el frontend: cuando llega, la aplicacion
 * muestra a Aria descansando y le dice al estudiante que vuelva en unos minutos, en vez de
 * una pantalla de error generica.
 */
@Slf4j
@RestControllerAdvice
public class ManejadorDeErrores {

    public static final String CODIGO_IA_NO_DISPONIBLE = "IA_NO_DISPONIBLE";

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<Map<String, Object>> noEncontrado(RecursoNoEncontradoException e) {
        log.warn("[404] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "codigo", "RECURSO_NO_ENCONTRADO",
                "mensaje", e.getMessage(),
                "reintentable", false
        ));
    }

    @ExceptionHandler(IaNoDisponibleException.class)
    public ResponseEntity<Map<String, Object>> iaNoDisponible(IaNoDisponibleException e) {
        log.warn("[IA] Servicio no disponible: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "codigo", CODIGO_IA_NO_DISPONIBLE,
                "mensaje", "Aria esta descansando. Vuelve a intentarlo en unos minutos.",
                "reintentable", true
        ));
    }
}
