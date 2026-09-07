package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.service.academico.BancoPreguntasService;
import com.example.tallerintegrador.service.util.IdHasher;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Banco de preguntas: lo que la IA generó para una semana, visto por el docente.
 *
 * El controlador entero está cerrado a docentes y administradores, y además comprueba
 * propiedad del curso: el rol TEACHER autoriza a ver TUS semanas, no las de cualquiera. Sin
 * esa segunda comprobación, cambiar el número de la URL bastaría para leer las respuestas de
 * los alumnos de otro profesor.
 */
@RestController
@RequestMapping("/banco-preguntas")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
public class BancoPreguntasController {

    private final BancoPreguntasService bancoPreguntasService;
    private final UserRepository userRepository;
    private final IdHasher idHasher;

    /**
     * El `semanaId` de la URL NO es la clave numerica: es el id ofuscado que produce
     * `IdHasher`, igual que en el resto de rutas de semanas. Declararlo como Long hacia que
     * Spring intentara convertir "kg-Oo-VSQrUIcfZzJ-vl5w" a numero y devolviera 400 antes de
     * llegar al metodo.
     */
    @Operation(summary = "Preguntas generadas para una semana, con respuestas de los alumnos")
    @GetMapping("/semana/{semanaId}")
    public ResponseEntity<?> porSemana(Authentication authentication, @PathVariable String semanaId) {
        final Long semana;
        try {
            semana = idHasher.decode(semanaId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Identificador de semana invalido."));
        }
        Long usuarioId = idDe(authentication);
        boolean esAdmin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ADMIN".equals(a.getAuthority()));

        if (!bancoPreguntasService.puedeVer(semana, usuarioId, esAdmin)) {
            // 403 y no 404: el recurso existe, lo que falta es el permiso. Mentir sobre su
            // existencia complicaría depurar sin ganar nada, porque el id ya lo tiene quien
            // pregunta.
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Esta semana no pertenece a un curso tuyo."));
        }
        return ResponseEntity.ok(bancoPreguntasService.porSemana(semana));
    }

    private Long idDe(Authentication authentication) {
        if (authentication == null) return null;
        return userRepository.findByCorreo(authentication.getName())
                .map(u -> u.getId())
                .orElse(null);
    }
}
