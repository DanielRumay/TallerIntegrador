package com.example.tallerintegrador.entidades.postgres;

/**
 * Permisos de un administrador de colegio. El administrador principal los tiene todos; un
 * miniadministrador, solo los que le asignaron. Cada uno se expone a Spring Security como la
 * authority "PERM_" + nombre, y los endpoints la piden con hasAuthority.
 *
 * Los nombres se guardan como texto en usuario_permiso: renombrar uno exige migrar las filas.
 */
public enum Permiso {
    CUENTAS_GESTIONAR("Crear, eliminar, bloquear y desbloquear docentes y estudiantes"),
    CONTRASENAS_RESTABLECER("Restablecer la contraseña de docentes y estudiantes"),
    CURSOS_GESTIONAR("Gestionar cursos, matrículas y co-docentes"),
    NOTAS_VER("Ver el rendimiento y exportar las notas"),
    USO_VER("Ver el uso de la plataforma"),
    METRICAS_IA_VER("Ver y exportar las métricas de IA"),
    AUDITORIA_VER("Ver el registro de auditoría"),
    ADMINISTRADORES_GESTIONAR("Crear miniadministradores y asignar permisos");

    private final String descripcion;

    Permiso(String descripcion) {
        this.descripcion = descripcion;
    }

    public String getDescripcion() {
        return descripcion;
    }

    /** Authority con la que se protege el endpoint correspondiente. */
    public String authority() {
        return "PERM_" + name();
    }
}
