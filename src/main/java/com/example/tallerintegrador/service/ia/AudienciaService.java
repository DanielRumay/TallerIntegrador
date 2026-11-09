package com.example.tallerintegrador.service.ia;

import com.example.tallerintegrador.entidades.postgres.Curso;
import com.example.tallerintegrador.entidades.postgres.Grado;
import com.example.tallerintegrador.entidades.postgres.Material;
import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.entidades.postgres.Semana;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.MaterialRepository;
import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.seguridad.ContextoUsuario;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decide para quien se escribe cada generacion. Ver PerfilAudiencia.
 *
 * ORDEN DE RESOLUCION.
 *   1. Si quien pide es un estudiante con grado, su propio grado: es exactamente la audiencia.
 *   2. Si no (el docente prepara material o prueba la generacion), el grado del curso al que
 *      pertenece el material: material -> semana -> curso -> grado.
 *   3. Si nada de eso se conoce, PerfilAudiencia.GENERAL, que reproduce el comportamiento
 *      anterior. Nunca falla: la audiencia es una mejora, no un requisito para generar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudienciaService {

    private final UserRepository userRepository;
    private final MaterialRepository materialRepository;
    private final MarcoCneb marcoCneb;

    @Transactional(readOnly = true)
    public PerfilAudiencia resolver(String correoUsuario, String mongoIdMaterial) {
        PerfilAudiencia perfil = resolverGrado(correoUsuario, mongoIdMaterial);
        return perfil.conMarco(marcoDe(perfil, mongoIdMaterial));
    }

    /** El area sale del curso del material: el grado dice para quien, el curso dice de que. */
    private String marcoDe(PerfilAudiencia perfil, String mongoIdMaterial) {
        if (mongoIdMaterial == null || mongoIdMaterial.isBlank() || !perfil.esConocido()) return "";
        try {
            return materialRepository.findByMongoId(mongoIdMaterial)
                    .map(Material::getSemana)
                    .map(Semana::getCurso)
                    .map(Curso::getNombre)
                    .map(nombre -> marcoCneb.instruccion(nombre, perfil))
                    .orElse("");
        } catch (Exception e) {
            log.warn("[AUDIENCIA] No se pudo resolver el marco curricular: {}", e.getMessage());
            return "";
        }
    }

    private PerfilAudiencia resolverGrado(String correoUsuario, String mongoIdMaterial) {
        try {
            if (correoUsuario != null) {
                PerfilAudiencia delAlumno = userRepository.findByCorreo(correoUsuario)
                        .map(this::deEstudiante)
                        .orElse(PerfilAudiencia.GENERAL);
                if (delAlumno.esConocido()) return delAlumno;
            }
            if (mongoIdMaterial != null && !mongoIdMaterial.isBlank()) {
                return materialRepository.findByMongoId(mongoIdMaterial)
                        .map(Material::getSemana)
                        .map(Semana::getCurso)
                        .map(Curso::getGrado)
                        .map(Grado::getNombre)
                        .map(PerfilAudiencia::desdeNombreGrado)
                        .orElse(PerfilAudiencia.GENERAL);
            }
        } catch (Exception e) {
            log.warn("[AUDIENCIA] No se pudo resolver el grado; se usa el perfil general: {}", e.getMessage());
        }
        return PerfilAudiencia.GENERAL;
    }

    /** Audiencia de quien hace la peticion en curso (solo cuenta si es estudiante). */
    @Transactional(readOnly = true)
    public PerfilAudiencia delUsuarioActual() {
        ContextoUsuario ctx = ContextoUsuario.actual();
        return ctx == null ? PerfilAudiencia.GENERAL : resolver(ctx.correo(), null);
    }

    private PerfilAudiencia deEstudiante(Usuario u) {
        if (u.getRol() != Rol.STUDENT || u.getGrado() == null) return PerfilAudiencia.GENERAL;
        return PerfilAudiencia.desdeNombreGrado(u.getGrado().getNombre());
    }
}
