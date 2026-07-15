-- 1. LIMPIAR TABLAS (¡Añadido material!)
TRUNCATE TABLE respuesta_usuario, respuesta, pregunta, intento, matriculas, material, semana, usuario, curso, seccion, grado, fragmentos_pdf RESTART IDENTITY CASCADE;

-- 2. INSERTAR GRADOS Y SECCIONES
INSERT INTO grado (nombre) VALUES
                               ('1er Año de Secundaria'),
                               ('2do Año de Secundaria'),
                               ('5to Año de Secundaria');

INSERT INTO seccion (nombre) VALUES
                                 ('Sección A'),
                                 ('Sección B'),
                                 ('Sección Única');

-- 3. INSERTAR USUARIOS
-- Roles: ADMIN, TEACHER, STUDENT
INSERT INTO usuario (nombre, correo, diagnostico_completado, dificultades_detectadas, nivel_conocimiento, intentos_fallidos, cuenta_bloqueada, requires_password_setup, password, rol, grado_id, seccion_id) VALUES
                                                                              ('Charlie Morales', 'charlymorales@gmail.com', false, null, null, 0, false, true, '$2a$12$ybCskrwNCk0OsO3Mqmwdreadcv8ID49gBh1VYgc1Hh7Dnf07l1VF2', 'ADMIN', NULL, NULL),
                                                                              ('Profesor Irwin', 'irwin@colegio.edu.pe', false, null, null, 0, false, true, '$2a$12$hh9/E9uZcR71i3dMbYlR8.mXvSj4FaSFGhZ/GpQdWgivqTw7qE.zy', 'TEACHER', NULL, NULL),
                                                                              ('Alumno Juan', 'juan@alumno.edu.pe', false, null, null, 0, false, true, '$2a$12$cvB9NFNvB99u244.sU54OefA.i50rOHGtdf/GJLzgPcoxG4P5QQBK', 'STUDENT', 1, 1),
                                                                              ('Alumna Nerea', 'nerea@alumno.edu.pe', false, null, null, 0, false, true, '$2a$12$cvB9NFNvB99u244.sU54OefA.i50rOHGtdf/GJLzgPcoxG4P5QQBK', 'STUDENT', 1, 2);