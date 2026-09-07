package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.service.metricas.ValidacionJuezService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Validación del juez de IA contra docentes humanos.
 *
 * Endpoints pensados para producir la cifra que sostiene el constructo de la tesis: cuánto
 * concuerda la calificación automática con la de un profesor sobre las mismas respuestas.
 *
 * Todo el controlador está restringido a docentes y administradores. Un alumno no debe poder
 * ver las respuestas de sus compañeros, que es exactamente lo que contiene la muestra.
 */
@RestController
@RequestMapping("/validacion-juez")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
public class ValidacionJuezController {

    private final ValidacionJuezService validacionJuezService;
    private final UserRepository userRepository;

    /**
     * Arma o amplía la muestra. `origen` = PRACTICA (respuestas abiertas de exámenes,
     * escala 0/1) o ARIA (turnos de tutoría, escala 1-4).
     */
    @PostMapping("/muestra")
    public ResponseEntity<?> construirMuestra(
            @RequestParam(defaultValue = "PRACTICA") String origen,
            @RequestParam(defaultValue = "150") int tamano) {
        try {
            int agregados = validacionJuezService.construirMuestra(origen, tamano);
            return ResponseEntity.ok(Map.of(
                    "origen", origen,
                    "casosAgregados", agregados,
                    "mensaje", agregados == 0
                            ? "No quedaban respuestas nuevas para añadir a la muestra."
                            : "Se añadieron " + agregados + " casos a la muestra."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Los casos que el docente autenticado todavía no ha calificado.
     *
     * La respuesta NO incluye la calificación de la IA, y es deliberado: si el docente la
     * viera antes de decidir, tendería a confirmarla y la concordancia medida sería
     * artificialmente alta. Es el control experimental del método, no una omisión.
     */
    @GetMapping("/pendientes")
    public ResponseEntity<?> pendientes(
            Authentication authentication,
            @RequestParam(required = false) String origen,
            @RequestParam(defaultValue = "20") int limite) {
        Long docenteId = idDe(authentication);
        if (docenteId == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "No se pudo identificar al docente."));
        }
        return ResponseEntity.ok(validacionJuezService.pendientesPara(docenteId, origen, limite));
    }

    @PostMapping("/{muestraId}/calificar")
    public ResponseEntity<?> calificar(
            Authentication authentication,
            @PathVariable Long muestraId,
            @RequestBody CalificacionRequest req) {
        Long docenteId = idDe(authentication);
        if (docenteId == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "No se pudo identificar al docente."));
        }
        try {
            validacionJuezService.calificar(muestraId, docenteId, req.puntuacion(), req.comentario());
            return ResponseEntity.ok(Map.of("mensaje", "Calificación registrada."));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** La cifra reportable: kappa ponderada, acuerdo exacto, sesgo e interpretación. */
    @GetMapping("/concordancia")
    public ResponseEntity<?> concordancia(@RequestParam(defaultValue = "PRACTICA") String origen) {
        return ResponseEntity.ok(validacionJuezService.concordancia(origen));
    }

    /** Los casos donde IA y docente no coincidieron: es donde se ve qué falla el juez. */
    @GetMapping("/desacuerdos")
    public ResponseEntity<?> desacuerdos(@RequestParam(defaultValue = "PRACTICA") String origen) {
        return ResponseEntity.ok(validacionJuezService.desacuerdos(origen));
    }

    private Long idDe(Authentication authentication) {
        if (authentication == null) return null;
        return userRepository.findByCorreo(authentication.getName())
                .map(u -> u.getId())
                .orElse(null);
    }

    public record CalificacionRequest(int puntuacion, String comentario) {}
}
