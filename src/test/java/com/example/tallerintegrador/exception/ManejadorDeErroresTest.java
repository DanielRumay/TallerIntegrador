package com.example.tallerintegrador.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El codigo de esta respuesta es un contrato con el frontend: la interfaz decide mostrar a Aria
 * descansando en funcion de el. Si alguien lo cambia, la pantalla vuelve a ser un error generico
 * sin que nadie se entere, y por eso esta fijado aqui.
 */
class ManejadorDeErroresTest {

    private final ManejadorDeErrores manejador = new ManejadorDeErrores();

    @Test
    @DisplayName("Un fallo de IA responde 503 con el codigo que la interfaz espera")
    void respondeCon503YCodigo() {
        var respuesta = manejador.iaNoDisponible(new IaNoDisponibleException("sin conexion"));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(respuesta.getBody()).isNotNull();
        assertThat(respuesta.getBody().get("codigo")).isEqualTo("IA_NO_DISPONIBLE");
        assertThat(respuesta.getBody().get("reintentable")).isEqualTo(true);
    }

    @Test
    @DisplayName("Un recurso inexistente responde 404, no 500")
    void recursoInexistenteEs404() {
        var respuesta = manejador.noEncontrado(
                new RecursoNoEncontradoException("El material ya no esta disponible."));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(respuesta.getBody()).isNotNull();
        assertThat(respuesta.getBody().get("codigo")).isEqualTo("RECURSO_NO_ENCONTRADO");
        assertThat(respuesta.getBody().get("reintentable")).isEqualTo(false);
    }

    @Test
    @DisplayName("El mensaje al estudiante no filtra el detalle tecnico del fallo")
    void noFiltraDetalleTecnico() {
        var respuesta = manejador.iaNoDisponible(
                new IaNoDisponibleException("429 RESOURCE_EXHAUSTED quota metric generate_content"));

        String mensaje = String.valueOf(respuesta.getBody().get("mensaje"));
        assertThat(mensaje).doesNotContain("429").doesNotContain("quota").doesNotContain("RESOURCE_EXHAUSTED");
        assertThat(mensaje).contains("Aria");
    }
}
