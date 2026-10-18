package com.example.tallerintegrador.entidades.postgres;

public enum Rol {
    /** Equipo que opera la plataforma: crea colegios y su administrador principal. No ve datos académicos. */
    SUPERADMIN,
    /** Administrador de UN colegio, con los permisos que tenga asignados (ver Permiso). */
    ADMIN,
    TEACHER,
    STUDENT
}
