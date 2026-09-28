package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.entidades.postgres.RegistroAuditoria;
import com.example.tallerintegrador.service.academico.AuditoriaService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Consulta del registro de auditoria. SOLO ADMINISTRADOR y SOLO LECTURA.
 *
 * No hay endpoint de borrado ni de edicion, y no debe haberlo: un registro que el propio
 * sistema puede alterar no sirve como evidencia (RNFS-09).
 */
@RestController
@RequestMapping("/admin/auditoria")
@RequiredArgsConstructor
public class AuditoriaController {

    private final AuditoriaService auditoriaService;

    @PreAuthorize("hasAuthority('ADMIN')")
    @GetMapping
    public ResponseEntity<?> listar(@RequestParam(required = false) String actor,
                                    @RequestParam(required = false) String accion,
                                    @RequestParam(defaultValue = "0") int pagina,
                                    @RequestParam(defaultValue = "50") int tamano) {
        var resultado = auditoriaService.consultar(actor, accion,
                PageRequest.of(Math.max(0, pagina), Math.min(Math.max(1, tamano), 200)));

        List<Map<String, Object>> filas = resultado.getContent().stream()
                .map(r -> Map.<String, Object>of(
                        "fecha", r.getFecha(),
                        "actor", r.getActorCorreo(),
                        "rol", r.getActorRol() == null ? "" : r.getActorRol(),
                        "accion", r.getAccion().name(),
                        "recurso", r.getRecursoTipo(),
                        "recursoId", r.getRecursoId() == null ? 0L : r.getRecursoId(),
                        "detalle", r.getDetalle() == null ? "" : r.getDetalle()))
                .toList();

        return ResponseEntity.ok(Map.of(
                "total", resultado.getTotalElements(),
                "pagina", resultado.getNumber(),
                "acciones", List.of(RegistroAuditoria.Accion.values()).stream().map(Enum::name).toList(),
                "registros", filas));
    }
}
