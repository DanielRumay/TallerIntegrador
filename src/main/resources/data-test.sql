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
INSERT INTO usuario (nombre, correo, password, rol, grado_id, seccion_id) VALUES
                                                                              ('Charlie Morales', 'charlymorales@gmail.com', 'admin123', 'ADMIN', NULL, NULL),
                                                                              ('Profesor Irwin', 'irwin@colegio.edu.pe', 'profe123', 'TEACHER', NULL, NULL),
                                                                              ('Alumno Juan', 'juan@alumno.edu.pe', 'alumno123', 'STUDENT', 1, 1),
                                                                              ('Alumna Nerea', 'nerea@alumno.edu.pe', 'alumno123', 'STUDENT', 1, 2);

-- 4. INSERTAR CURSOS (¡Con los nuevos campos de UI!)
INSERT INTO curso (nombre, descripcion, profesor_id, grado_id, seccion_id, emoji, color) VALUES
                                                                                             ('Matemáticas', 'Matemáticas para 1er Año - Sección A', 2, 1, 1, '📐', 'primary'),
                                                                                             ('Matemáticas', 'Matemáticas para 1er Año - Sección B', 2, 1, 2, '📐', 'lime'),
                                                                                             ('Comunicación', 'Comunicación para 1er Año - Sección A', 2, 1, 1, '📚', 'coral'),
                                                                                             ('Lenguaje', 'adasd', 2, 1, 1, '📘', 'primary');

-- 5. INSERTAR MATRÍCULAS
INSERT INTO matriculas (usuario_id, curso_id, fecha_inscripcion) VALUES
                                                                     (3, 1, NOW()),
                                                                     (4, 2, NOW()),
                                                                     (3, 4, NOW());

-- 6. INSERTAR SEMANAS (Limpias, sin PDFs)
INSERT INTO semana (num_sem, curso_id) VALUES
                                           ('Semana 1', 1),
                                           ('Semana 1', 2),
                                           ('Semana 2', 1);

-- 6.5 INSERTAR MATERIALES (Vinculados a las semanas de arriba)
INSERT INTO material (nombre_archivo, mongo_id, semana_id) VALUES
                                                               ('Ecuaciones_A.pdf', 'mock-id-1', 1),
                                                               ('Ecuaciones_B.pdf', 'mock-id-2', 2),
                                                               ('Geometria.pdf', 'mock-id-3', 3);

-- 7. INSERTAR PREGUNTAS
INSERT INTO pregunta (pregunta, tipodepregunta, semana_id) VALUES
                                                               ('¿Cuál es el valor de x en: 2x = 4?', 0, 1),
                                                               ('¿Cuál es el valor de x en: 3x = 9?', 0, 2),
                                                               ('¿Cómo se calcula el área de un cuadrado?', 0, 3);

-- 8. INSERTAR RESPUESTAS
INSERT INTO respuesta (respuesta, pregunta_id, valor) VALUES
                                                          ('x = 2', 1, true),
                                                          ('x = 4', 1, false),
                                                          ('x = 3', 2, true),
                                                          ('x = 6', 2, false),
                                                          ('Lado x Lado', 3, true);

-- 9. INSERTAR INTENTOS
INSERT INTO intento (nota, fecha, usuario_id) VALUES
                                                  (20.0, NOW(), 3),
                                                  (10.0, NOW(), 4);

-- 10. INSERTAR RESPUESTA_USUARIO
INSERT INTO respuesta_usuario (usuario_id, pregunta_id, respuesta_id, correcta, fecha_creacion) VALUES
                                                                                                    (3, 1, 1, true, NOW()),
                                                                                                    (4, 2, 4, false, NOW());