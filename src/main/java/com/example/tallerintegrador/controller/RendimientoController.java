package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.service.analitica.MapaConocimientoService;
import com.example.tallerintegrador.service.analitica.RendimientoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/rendimiento")
@RequiredArgsConstructor
public class RendimientoController {

    private final RendimientoService rendimientoService;
    private final MapaConocimientoService mapaConocimientoService;
    private final UserRepository userRepository;
    private final com.example.tallerintegrador.service.analitica.ProgresoAlumnoService progresoAlumnoService;

    /**
     * GET /api/rendimiento/progreso/{usuarioId}
     *
     * Puntos, rango y marca personal.
     *
     * ATENCIÓN AL LEER ESTE ENDPOINT: devuelve PROGRESO, no nivel de dominio. Los puntos
     * miden constancia y esfuerzo, y existen para motivar. El nivel cognitivo que decide la
     * dificultad de las preguntas sale del desempeño observado —escalograma de Guttman,
     * deliberación del comité y veto determinista— y NO se toca desde aquí. Mezclarlos haría
     * que practicar mucho y mal subiera de nivel.
     */
    @PreAuthorize("hasAuthority('STUDENT') or hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/progreso/{usuarioId}")
    public ResponseEntity<?> progreso(@PathVariable Long usuarioId, Authentication authentication) {
        if (!puedeConsultar(usuarioId, authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "No tiene permiso para consultar el progreso de otro estudiante."));
        }
        return ResponseEntity.ok(progresoAlumnoService.progresoDe(usuarioId));
    }

    /**
     * GET /api/rendimiento/mapa-calor/{usuarioId}
     * Devuelve la actividad del alumno para el componente heatmap.
     *
     * Un STUDENT solo puede consultar su propio rendimiento: sin esta comprobación
     * bastaba cambiar el id de la URL para leer el de cualquier compañero.
     */
    @PreAuthorize("hasAuthority('STUDENT') or hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/mapa-calor/{usuarioId}")
    public ResponseEntity<?> obtenerMapaCalor(@PathVariable Long usuarioId, Authentication authentication) {
        if (!puedeConsultar(usuarioId, authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "No tiene permiso para consultar el rendimiento de otro estudiante."));
        }
        try {
            return ResponseEntity.ok(rendimientoService.obtenerMapaCalor(usuarioId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/rendimiento/mapa-conocimiento/{usuarioId}
     * Mapa de conocimiento concepto × nivel de Bloom, con probabilidad de dominio (BKT) en
     * vez de % de aciertos crudo. Aditivo a /mapa-calor, no lo reemplaza. Depende de que
     * el frontend reenvíe nivelBloom/conceptos al guardar el intento (ver
     * GuardarIntentoRequest.RespuestaDetalle) — hasta entonces devuelve una lista vacía en
     * vez de fallar.
     */
    @PreAuthorize("hasAuthority('STUDENT') or hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/mapa-conocimiento/{usuarioId}")
    public ResponseEntity<?> obtenerMapaDeConocimiento(@PathVariable Long usuarioId, Authentication authentication) {
        if (!puedeConsultar(usuarioId, authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "No tiene permiso para consultar el rendimiento de otro estudiante."));
        }
        try {
            return ResponseEntity.ok(mapaConocimientoService.obtenerMapaDeConocimiento(usuarioId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Docentes y administradores ven a cualquier alumno; un estudiante, solo a sí mismo. */
    private boolean puedeConsultar(Long usuarioId, Authentication authentication) {
        if (authentication == null) return false;

        boolean esDocenteOAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> "TEACHER".equals(a.getAuthority()) || "ADMIN".equals(a.getAuthority()));
        if (esDocenteOAdmin) return true;

        return userRepository.findByCorreo(authentication.getName())
                .map(u -> u.getId().equals(usuarioId))
                .orElse(false);
    }
}
