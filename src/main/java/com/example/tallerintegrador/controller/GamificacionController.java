package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.seguridad.ContextoUsuario;
import com.example.tallerintegrador.service.analitica.GamificacionService;
import com.example.tallerintegrador.service.analitica.PoderesService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Niveles, monedas y liga semanal. Todo es del propio alumno (salvo el perfil de un compañero de
 * curso, ver PerfilService): el id sale de la sesión, nunca de
 * la URL, así no hay forma de pedir los datos de un compañero.
 */
@RestController
@RequestMapping("/gamificacion")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('STUDENT')")
public class GamificacionController {

    private final GamificacionService gamificacionService;
    private final com.example.tallerintegrador.service.analitica.MonedasService monedasService;
    private final com.example.tallerintegrador.service.analitica.TiendaService tiendaService;
    private final PoderesService poderesService;
    private final com.example.tallerintegrador.service.analitica.JefeSemanalService jefeSemanalService;
    private final com.example.tallerintegrador.service.analitica.PerfilService perfilService;
    private final com.example.tallerintegrador.service.util.IdHasher idHasher;

    /** GET /api/gamificacion/resumen: nivel, XP, monedas, racha, título y apodo. */
    @GetMapping("/resumen")
    public ResponseEntity<?> resumen() {
        return ResponseEntity.ok(gamificacionService.resumen(ContextoUsuario.actual().id()));
    }

    /** GET /api/gamificacion/monedas: saldo, de dónde sale, cuánto puede ganar hoy y últimos movimientos. */
    @GetMapping("/monedas")
    public ResponseEntity<?> monedas() {
        return ResponseEntity.ok(monedasService.billetera(ContextoUsuario.actual().id()));
    }

    /** GET /api/gamificacion/liga?cursoId=: liga de la semana en uno de sus cursos. */
    @GetMapping("/liga")
    public ResponseEntity<?> liga(@RequestParam(required = false) Long cursoId) {
        return gamificacionService.ligaSemanal(ContextoUsuario.actual().id(), cursoId)
                .map(this::conPerfiles)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No estás matriculado en ese curso.")));
    }

    /** Cada fila de la liga lleva el id cifrado de su perfil, para abrirlo desde la tabla. */
    private Map<String, Object> conPerfiles(Map<String, Object> liga) {
        @SuppressWarnings("unchecked")
        var filas = (java.util.List<GamificacionService.FilaLiga>) liga.get("filas");
        Map<String, Object> salida = new java.util.LinkedHashMap<>(liga);
        salida.put("filas", filas.stream().map(f -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("orden", f.orden());
            m.put("posicion", f.posicion());
            m.put("apodo", f.apodo());
            m.put("xp", f.xp());
            m.put("esTu", f.esTu());
            m.put("perfilId", idHasher.encode(f.usuarioId()));
            return m;
        }).toList());
        return salida;
    }

    // ── Perfil de juego ─────────────────────────────────────────────────────
    // Excepción a "todo es del propio alumno": se puede ver el perfil de un compañero de curso.
    // PerfilService comprueba que compartan curso y solo devuelve apodo, nivel, rachas y su Aria.

    /** GET /api/gamificacion/perfil/{perfilId}: perfil de un compañero ("yo" para el propio). */
    @GetMapping("/perfil/{perfilId}")
    public ResponseEntity<?> perfil(@PathVariable String perfilId) {
        Long yo = ContextoUsuario.actual().id();
        Long otro;
        try {
            otro = "yo".equals(perfilId) ? yo : idHasher.decode(perfilId);
        } catch (RuntimeException e) {
            otro = null;
        }
        return perfilService.perfil(yo, otro)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Ese perfil no es de tu curso.")));
    }

    /** PUT /api/gamificacion/perfil {"visible": false, "variante": "perro"}: ajustes del propio perfil. */
    @PutMapping("/perfil")
    public ResponseEntity<?> ajustarPerfil(@RequestBody Map<String, Object> body) {
        try {
            Object visible = body.get("visible");
            Object variante = body.get("variante");
            return ResponseEntity.ok(perfilService.ajustar(ContextoUsuario.actual().id(),
                    visible instanceof Boolean b ? b : null, variante instanceof String s ? s : null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Tienda ──────────────────────────────────────────────────────────────

    @GetMapping("/tienda")
    public ResponseEntity<?> tienda() {
        return ResponseEntity.ok(tiendaService.catalogo(ContextoUsuario.actual().id()));
    }

    @PostMapping("/tienda/accesorios/{id}")
    public ResponseEntity<?> comprarAccesorio(@PathVariable String id) {
        return compra(() -> tiendaService.comprarAccesorio(ContextoUsuario.actual().id(), id));
    }

    @PostMapping("/tienda/protector")
    public ResponseEntity<?> comprarProtector() {
        return compra(() -> tiendaService.protegerManana(ContextoUsuario.actual().id()));
    }

    /** PUT /api/gamificacion/aria {"equipado": ["gorro","bufanda"]}: lo que Aria lleva puesto. */
    @PutMapping("/aria")
    public ResponseEntity<?> equipar(@RequestBody Map<String, java.util.List<String>> body) {
        try {
            var puestos = tiendaService.equipar(ContextoUsuario.actual().id(),
                    body.getOrDefault("equipado", java.util.List.of()));
            return ResponseEntity.ok(Map.of("equipado", puestos));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Poderes del contrarreloj ────────────────────────────────────────────

    public record UsoPoder(String partidaId, PoderesService.Poder poder, PoderesService.Pregunta pregunta) {}

    /** GET /api/gamificacion/poderes/precio?partidaId=&poder=PISTA: precio del próximo uso. */
    @GetMapping("/poderes/precio")
    public ResponseEntity<?> precioPoder(@RequestParam String partidaId, @RequestParam PoderesService.Poder poder) {
        try {
            return ResponseEntity.ok(Map.of("precio", poderesService.precioDe(ContextoUsuario.actual().id(), partidaId, poder)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/poderes")
    public ResponseEntity<?> usarPoder(@RequestBody UsoPoder uso) {
        try {
            return ResponseEntity.ok(poderesService.usar(ContextoUsuario.actual().id(), uso.partidaId(), uso.poder(), uso.pregunta()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    /** POST /api/gamificacion/partidas/{id}/bono {"rapidas":4,"comboMaximo":5}: bono al terminar. */
    @PostMapping("/partidas/{partidaId}/bono")
    public ResponseEntity<?> bonoPartida(@PathVariable String partidaId, @RequestBody Map<String, Integer> body) {
        try {
            int bono = poderesService.cobrarBono(ContextoUsuario.actual().id(), partidaId,
                    body.getOrDefault("rapidas", 0), body.getOrDefault("comboMaximo", 0));
            return ResponseEntity.ok(Map.of("bono", bono));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<?> compra(java.util.function.IntSupplier comprar) {
        try {
            return ResponseEntity.ok(Map.of("saldo", comprar.getAsInt()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            // Saldo insuficiente o ya comprado: 409, el estado no permite la compra.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    /** GET /api/gamificacion/jefe?cursoId=: batalla cooperativa de la semana en un curso del alumno. */
    @GetMapping("/jefe")
    public ResponseEntity<?> jefe(@RequestParam(required = false) Long cursoId) {
        return jefeSemanalService.estado(ContextoUsuario.actual().id(), cursoId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "No estás matriculado en ese curso.")));
    }

    @GetMapping("/apodos")
    public ResponseEntity<?> opcionesDeApodo() {
        return ResponseEntity.ok(Map.of("opciones", gamificacionService.opcionesDeApodo()));
    }

    @PutMapping("/apodo")
    public ResponseEntity<?> cambiarApodo(@RequestBody Map<String, String> body) {
        try {
            String apodo = gamificacionService.cambiarApodo(ContextoUsuario.actual().id(), body.get("apodo"));
            return ResponseEntity.ok(Map.of("apodo", apodo));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
