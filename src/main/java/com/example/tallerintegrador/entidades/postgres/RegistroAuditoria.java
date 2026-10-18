package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Una accion privilegiada que alguien ejecuto sobre el contenido del colegio.
 *
 * QUE REGISTRA. Lo que un docente o un administrador CAMBIA: crear o borrar un curso, crear una
 * semana, subir o eliminar material, matricular o retirar a un alumno. No registra lecturas.
 *
 * POR QUE EXISTE. RegistroAcceso solo guarda entradas al sistema: sirve para saber quien entro,
 * no que hizo. Sin esto, si un material desaparece o un alumno queda fuera de un curso, no hay
 * forma de reconstruir quien lo hizo ni cuando. Cubre RFS-07 (registro de acciones
 * privilegiadas) y RNFS-09 (registros de auditoria no borrables) del acta de constitucion.
 *
 * NO SE BORRA NI SE EDITA. A proposito no existe metodo de borrado ni de actualizacion en el
 * repositorio, y los campos no tienen setter publico mas alla de la creacion: un registro que
 * se puede alterar no sirve como evidencia. Si hay que purgar por retencion, sera un
 * procedimiento explicito y auditado, no una llamada mas del servicio.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "registro_auditoria", indexes = {
        @Index(name = "idx_auditoria_fecha", columnList = "fecha"),
        @Index(name = "idx_auditoria_actor", columnList = "actor_correo, fecha")
})
public class RegistroAuditoria {

    public enum Accion {
        CURSO_CREADO, CURSO_ACTUALIZADO, CURSO_ELIMINADO,
        SEMANA_CREADA,
        MATERIAL_SUBIDO, MATERIAL_ELIMINADO,
        ALUMNO_MATRICULADO, ALUMNO_RETIRADO,
        // Acciones sobre cuentas (RFS-07): quién dio de alta, eliminó, bloqueó o cambió la
        // contraseña de quién. Ver MigracionAuditoria para el CHECK que Hibernate no actualiza.
        USUARIO_CREADO, USUARIO_ELIMINADO, USUARIO_BLOQUEADO, USUARIO_DESBLOQUEADO,
        CONTRASENA_RESTABLECIDA,
        // Gestión de colegios y de administradores.
        COLEGIO_CREADO, COLEGIO_ACTIVADO, COLEGIO_DESACTIVADO,
        ADMIN_CREADO, ADMIN_ELIMINADO, PERMISOS_CAMBIADOS
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime fecha;

    /** Correo de quien ejecuto la accion. Texto y no relacion: el registro sobrevive al usuario. */
    @Column(name = "actor_correo", nullable = false, length = 150)
    private String actorCorreo;

    @Column(name = "actor_rol", length = 20)
    private String actorRol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Accion accion;

    /** Sobre que se actuo: "curso", "semana", "material", "matricula", "usuario". */
    @Column(name = "recurso_tipo", nullable = false, length = 20)
    private String recursoTipo;

    @Column(name = "recurso_id")
    private Long recursoId;

    /**
     * Colegio en el que ocurrió la acción (el del actor). Cada administrador ve solo la
     * auditoría de su colegio. NULL para acciones del SUPERADMIN o del sistema.
     */
    @Column(name = "colegio_id")
    private Long colegioId;

    /** Lo que hace util la fila: nombre del archivo, del curso, numero de semana. */
    @Column(columnDefinition = "TEXT")
    private String detalle;
}
