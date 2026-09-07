package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.DTO.GuardarIntentoAdaptativoRequest;
import com.example.tallerintegrador.controller.util.UsuarioAutenticado;
import com.example.tallerintegrador.service.analitica.AdaptiveLearningService;
import com.example.tallerintegrador.service.util.IdHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/adaptive")
@RequiredArgsConstructor
public class AdaptiveLearningController {

    private final AdaptiveLearningService adaptiveLearningService;
    private final com.example.tallerintegrador.repository.UserRepository userRepository;
    private final IdHasher idHasher;
    private final UsuarioAutenticado usuarioAutenticado;

    /**
     * Reescribe la petición con el usuario del token, ignorando el que venga en el cuerpo.
     * Ver la explicación en IntentoController.guardarIntento.
     */
    private GuardarIntentoAdaptativoRequest conUsuarioDelToken(
            GuardarIntentoAdaptativoRequest r, Long idReal) {
        if (r.usuarioId() != null && !r.usuarioId().equals(idReal)) {
            log.warn("[SEGURIDAD] El intento adaptativo declaraba usuarioId={} pero el token es del usuario {}.",
                    r.usuarioId(), idReal);
        }
        return new GuardarIntentoAdaptativoRequest(
                idReal, r.semanaId(), r.notaFinal(), r.tiempoEmpleadoSegundos(),
                r.numeroIntentos(), r.tipoEvaluacion(), r.respuestas());
    }

    @PreAuthorize("hasAuthority('STUDENT')")
    @GetMapping("/evaluacion")
    public ResponseEntity<?> obtenerEvaluacionAdaptativa(
            @RequestParam Long usuarioId,
            @RequestParam String semanaId,
            Authentication authentication) {
        if (!usuarioAutenticado.puedeConsultarA(usuarioId, authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "No tiene permiso para generar la evaluación de otro estudiante."));
        }
        try {
            return ResponseEntity.ok(adaptiveLearningService.generarEvaluacionAdaptativa(usuarioId, idHasher.decode(semanaId)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Guarda la prueba de UBICACIÓN de una semana y devuelve el nivel asignado.
     *
     * Es un endpoint aparte del de guardar intentos a propósito: la ubicación no es un examen
     * —no genera Intento ni nota— y mezclarla con el guardado normal haría que un cero de
     * ubicación hundiera el promedio del alumno antes de haber estudiado. Es el mismo defecto
     * que tenía el ACRA antes de separarlo a su propia tabla.
     */
    /**
     * Si el alumno ya hizo la evaluacion recomendadora de esta semana, y con que resultado.
     *
     * Existe porque la pantalla lo decidia mirando localStorage: desde otro dispositivo, o
     * tras limpiar el navegador, la evaluacion reaparecia como "Pendiente" aunque estuviera
     * hecha y guardada. El estado de un alumno no puede vivir solo en su navegador.
     */
    @PreAuthorize("hasAuthority('STUDENT')")
    @GetMapping("/estado")
    public ResponseEntity<?> estadoDeLaSemana(Authentication authentication,
                                              @RequestParam String semanaId) {
        Long usuarioId = usuarioAutenticado.idActual(authentication);
        if (usuarioId == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "No se pudo identificar al estudiante."));
        }
        try {
            return ResponseEntity.ok(
                    adaptiveLearningService.estadoDeLaSemana(usuarioId, idHasher.decode(semanaId)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("hasAuthority('STUDENT')")
    @PostMapping("/ubicacion")
    public ResponseEntity<?> guardarUbicacion(@RequestBody GuardarIntentoAdaptativoRequest request) {
        try {
            String correo = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName();
            Long usuarioId = userRepository.findByCorreo(correo)
                    .map(u -> u.getId()).orElse(null);
            if (usuarioId == null) {
                return ResponseEntity.status(403).body(Map.of("error", "No se pudo identificar al alumno."));
            }
            return ResponseEntity.ok(adaptiveLearningService.guardarUbicacion(
                    usuarioId, request.semanaId(), request.respuestas()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("hasAuthority('STUDENT')")
    @PostMapping("/guardar")
    public ResponseEntity<?> guardarIntentoAdaptativo(
            @RequestBody GuardarIntentoAdaptativoRequest request, Authentication authentication) {
        Long idReal = usuarioAutenticado.idActual(authentication);
        if (idReal == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "No se pudo identificar al estudiante."));
        }
        try {
            Map<String, Object> resultadoDebate =
                    adaptiveLearningService.guardarIntentoConDebate(conUsuarioDelToken(request, idReal));
            return ResponseEntity.ok(resultadoDebate);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Misma operación que /guardar, pero transmitiendo cada turno del comité por SSE en
     * cuanto se produce, en vez de que el frontend espere en silencio a que las 5 llamadas
     * secuenciales terminen. Puramente aditivo: /guardar sigue existiendo igual que antes
     * para quien no necesite ver el debate en vivo.
     *
     * Eventos emitidos: "turno" (una Postura por cada uno de los 4 turnos deliberantes),
     * "consenso" (la decisión cruda del Coordinador, antes del veto), "veto" (si el
     * VerificadorAgent corrigió la propuesta y por qué), "final" (la misma respuesta que
     * hoy devuelve /guardar), "error" (si algo falla).
     */
    @PreAuthorize("hasAuthority('STUDENT')")
    @PostMapping(value = "/guardar-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter guardarIntentoAdaptativoStream(
            @RequestBody GuardarIntentoAdaptativoRequest request, Authentication authentication) {
        SseEmitter emitter = new SseEmitter(120_000L);
        // El usuario se resuelve AQUI, en el hilo de la petición: el SecurityContext no viaja
        // solo al hilo de runAsync.
        final Long idReal = usuarioAutenticado.idActual(authentication);
        if (idReal == null) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("error", "No se pudo identificar al estudiante.")));
            } catch (IOException ignored) {}
            emitter.complete();
            return emitter;
        }
        final GuardarIntentoAdaptativoRequest seguro = conUsuarioDelToken(request, idReal);
        CompletableFuture.runAsync(() -> {
            try {
                adaptiveLearningService.guardarIntentoConDebate(seguro, (evento, datos) -> {
                    try {
                        emitter.send(SseEmitter.event().name(evento).data(datos));
                    } catch (IOException e) {
                        log.warn("[SSE-DEBATE] No se pudo enviar el evento '{}': {}", evento, e.getMessage());
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                log.error("[SSE-DEBATE] Error en el debate: {}", e.getMessage());
                try {
                    emitter.send(SseEmitter.event().name("error").data(Map.of("error", e.getMessage())));
                } catch (IOException ignored) {}
                emitter.complete();
            }
        });
        return emitter;
    }

    @PreAuthorize("hasAuthority('STUDENT')")
    @GetMapping("/materiales-recomendados")
    public ResponseEntity<?> obtenerMaterialesRecomendados(
            @RequestParam Long usuarioId,
            @RequestParam String semanaId,
            Authentication authentication) {
        if (!usuarioAutenticado.puedeConsultarA(usuarioId, authentication)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "No tiene permiso para consultar los materiales de otro estudiante."));
        }
        try {
            return ResponseEntity.ok(adaptiveLearningService.recomendarMateriales(usuarioId, idHasher.decode(semanaId)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
