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
    private final com.example.tallerintegrador.service.academico.AccesoService accesoService;

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

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('PERM_NOTAS_VER')")
    @GetMapping("/semana/{semanaId}")
    public ResponseEntity<?> intentosPorSemana(@PathVariable String semanaId) {
        Long id = idHasher.decode(semanaId);
        accesoService.exigirAccesoASemana(id);   // el docente debe dictar el curso de la semana
        return ResponseEntity.ok(intentoService.obtenerIntentosPorSemana(id));
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('PERM_NOTAS_VER')")
    @GetMapping("/todos")
    public ResponseEntity<?> obtenerTodosLosIntentos() {
        return ResponseEntity.ok(intentoService.obtenerTodosLosIntentos(accesoService.usuarioActual()));
    }
}