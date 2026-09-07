package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.DTO.GuardarIntentoRequest;
import com.example.tallerintegrador.controller.util.UsuarioAutenticado;
import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.service.academico.IntentoService;
import com.example.tallerintegrador.service.util.IdHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@lombok.extern.slf4j.Slf4j
@RestController
@RequestMapping("/intentos")
@RequiredArgsConstructor
public class IntentoController {

    private final IntentoService intentoService;
    private final IdHasher idHasher;
    private final UserRepository userRepository;
    private final UsuarioAutenticado usuarioAutenticado;

    /**
     * El `usuarioId` del cuerpo se IGNORA y se sustituye por el del token.
     *
     * Antes se usaba tal cual, así que bastaba cambiar ese número en el JSON para guardar un
     * intento —con su nota— a nombre de otro alumno. No es solo un problema de seguridad: los
     * resultados de la tesis se calculan sobre estos registros, y si no se puede garantizar a
     * quién pertenece cada intento, ninguna cifra derivada se sostiene.
     *
     * Se ignora en vez de validar a propósito: validar deja la puerta abierta a que alguien
     * olvide hacerlo en el siguiente endpoint; ignorar elimina la posibilidad.
     */
    @PreAuthorize("hasAuthority('STUDENT')")
    @PostMapping("/guardar")
    public ResponseEntity<?> guardarIntento(
            @RequestBody GuardarIntentoRequest request, Authentication authentication) {
        Long idReal = usuarioAutenticado.idActual(authentication);
        if (idReal == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "No se pudo identificar al estudiante."));
        }
        if (request.usuarioId() != null && !request.usuarioId().equals(idReal)) {
            log.warn("[SEGURIDAD] El intento declaraba usuarioId={} pero el token es del usuario {}. Se usa el del token.",
                    request.usuarioId(), idReal);
        }
        GuardarIntentoRequest seguro = new GuardarIntentoRequest(
                idReal, request.semanaId(), request.notaFinal(), request.tecnica(), request.respuestas());
        try {
            intentoService.guardarIntentoCompleto(seguro);
            return ResponseEntity.ok(Map.of("message", "Examen guardado correctamente en el historial"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Historial completo del alumno: cada intento con sus preguntas, lo que respondió, si
     * acertó, la respuesta correcta y la retroalimentación del juez.
     *
     * La comprobación de propiedad no es decorativa. Sin ella bastaba cambiar el id de la
     * URL para leer el historial de cualquier compañero, y desde este cambio la respuesta
     * ya no lleva solo notas: lleva las respuestas escritas del alumno. Es la misma
     * comprobación que ya protege /rendimiento/mapa-calor.
     */
    @PreAuthorize("hasAuthority('STUDENT')")
    @GetMapping("/mis-intentos/{usuarioId}")
    public ResponseEntity<?> misIntentos(@PathVariable Long usuarioId, Authentication authentication) {
        if (authentication == null || userRepository.findByCorreo(authentication.getName())
                .map(u -> !u.getId().equals(usuarioId))
                .orElse(true)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "No tiene permiso para consultar el historial de otro estudiante."));
        }
        return ResponseEntity.ok(intentoService.obtenerIntentosPorUsuario(usuarioId));
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/semana/{semanaId}")
    public ResponseEntity<?> intentosPorSemana(@PathVariable String semanaId) {
        return ResponseEntity.ok(intentoService.obtenerIntentosPorSemana(idHasher.decode(semanaId)));
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/todos")
    public ResponseEntity<?> obtenerTodosLosIntentos() {
        return ResponseEntity.ok(intentoService.obtenerTodosLosIntentos());
    }
}