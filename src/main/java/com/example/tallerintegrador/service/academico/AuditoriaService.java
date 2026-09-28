package com.example.tallerintegrador.service.academico;

import com.example.tallerintegrador.entidades.postgres.RegistroAuditoria;
import com.example.tallerintegrador.repository.RegistroAuditoriaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Deja constancia de quien cambio que.
 *
 * DOS DECISIONES QUE IMPORTAN:
 *
 * 1. REQUIRES_NEW. El registro va en su propia transaccion. Si la operacion de negocio se
 *    deshace despues, la constancia del intento se conserva; y al reves, un fallo al registrar
 *    nunca tumba la operacion del docente.
 *
 * 2. NUNCA PROPAGA EXCEPCIONES. Un problema al auditar no puede impedir que un profesor suba su
 *    material. Se anota en el log de la aplicacion y se sigue.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditoriaService {

    private final RegistroAuditoriaRepository repositorio;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrar(RegistroAuditoria.Accion accion, String recursoTipo,
                          Long recursoId, String detalle) {
        try {
            RegistroAuditoria r = new RegistroAuditoria();
            r.setFecha(LocalDateTime.now());
            r.setActorCorreo(correoDelActor());
            r.setActorRol(rolDelActor());
            r.setAccion(accion);
            r.setRecursoTipo(recursoTipo);
            r.setRecursoId(recursoId);
            r.setDetalle(detalle);
            repositorio.save(r);
        } catch (Exception e) {
            log.warn("[AUDITORIA] No se pudo registrar {} sobre {} {}: {}",
                    accion, recursoTipo, recursoId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Page<RegistroAuditoria> consultar(String actor, String accion, Pageable pagina) {
        String a = (actor == null) ? "" : actor.trim();
        String ac = (accion == null) ? "" : accion.trim();
        return repositorio.buscar(a, ac, pagina);
    }

    private String correoDelActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "sistema" : auth.getName();
    }

    private String rolDelActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().isEmpty()) return null;
        return auth.getAuthorities().iterator().next().getAuthority();
    }
}
