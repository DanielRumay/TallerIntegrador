package com.example.tallerintegrador.service.academico;

import com.example.tallerintegrador.DTO.CursoRequestDTO;
import com.example.tallerintegrador.DTO.CursoResponseDTO;
import com.example.tallerintegrador.DTO.CursoDocenteDTO;
import com.example.tallerintegrador.DTO.SemanaDTO;
import com.example.tallerintegrador.entidades.postgres.Curso;
import com.example.tallerintegrador.entidades.postgres.Grado;
import com.example.tallerintegrador.entidades.postgres.Seccion;
import com.example.tallerintegrador.entidades.postgres.Semana;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.*;
import com.example.tallerintegrador.service.util.IdHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CursoService {

    private final CursoRepository cursoRepository;
    private final UserRepository userRepository;
    private final GradoRepository gradoRepository;
    private final MatriculaRepository matriculaRepository;
    private final SeccionRepository seccionRepository;
    private final SemanaRepository semanaRepository;
    private final PreguntaRepository preguntaRepository;
    private final RespuestaRepository respuestaRepository;
    private final RespuestaUsuarioRepository respuestaUsuarioRepository;
    private final MaterialRepository materialRepository;
    private final CursoBannerRepository cursoBannerRepository;
    private final IdHasher idHasher;

    private static final long MAX_BYTES_BANNER = 2L * 1024 * 1024;
    private static final java.util.Set<String> TIPOS_BANNER = java.util.Set.of("image/jpeg", "image/png", "image/webp");

    public List<Curso> obtenerCursosPorProfesor(Long profesorId) {
        return cursoRepository.findByProfesorId(profesorId);
    }

    public CursoResponseDTO crearCurso(CursoRequestDTO request) {
        Long profesorId = request.profesorId();
        Long gradoId = request.gradoId();
        Long seccionId = request.seccionId();

        Usuario profesor = userRepository.findById(profesorId)
                .orElseThrow(() -> new RuntimeException("Profesor no encontrado"));
        Grado grado = gradoRepository.findById(gradoId)
                .orElseThrow(() -> new RuntimeException("Grado no encontrado"));
        Seccion seccion = seccionRepository.findById(seccionId)
                .orElseThrow(() -> new RuntimeException("Sección no encontrada"));

        Curso nuevoCurso = new Curso();
        nuevoCurso.setNombre(request.nombre());
        nuevoCurso.setDescripcion(request.descripcion());
        nuevoCurso.setProfesor(profesor);
        nuevoCurso.setGrado(grado);
        nuevoCurso.setSeccion(seccion);

        if (request.emoji() != null) {
            nuevoCurso.setEmoji(request.emoji());
        }
        if (request.color() != null) {
            nuevoCurso.setColor(request.color());
        }

        Curso cursoGuardado = cursoRepository.save(nuevoCurso);

        if (request.semanas() != null) {
            int cantidadSemanas = request.semanas();
            for (int i = 1; i <= cantidadSemanas; i++) {
                com.example.tallerintegrador.entidades.postgres.Semana nuevaSemana =
                        new com.example.tallerintegrador.entidades.postgres.Semana();
                nuevaSemana.setNumSem("Semana " + i);
                nuevaSemana.setCurso(cursoGuardado);
                semanaRepository.save(nuevaSemana);
            }
        }
        return CursoResponseDTO.builder()
                .id(idHasher.encode(cursoGuardado.getId()))
                .name(cursoGuardado.getNombre())
                .description(cursoGuardado.getDescripcion())
                .emoji(cursoGuardado.getEmoji())
                .color(cursoGuardado.getColor())
                .bannerVersion(cursoGuardado.getBannerVersion())
                .nombreProfesor(profesor.getNombre())
                .build();
    }

    public List<Curso> obtenerCursosPorAlumno(Long alumnoId) {
        return cursoRepository.findCursosByAlumnoId(alumnoId);
    }

    public List<CursoDocenteDTO> obtenerCursosResumenProfesor(Long profesorId) {
        List<Curso> cursos = cursoRepository.findByProfesorId(profesorId);

        return cursos.stream().map(curso -> {
            long alumnos = matriculaRepository.countByCursoId(curso.getId());
            long semanas = semanaRepository.countByCursoId(curso.getId());

            return CursoDocenteDTO.builder()
                    .id(idHasher.encode(curso.getId()))
                    .name(curso.getNombre())
                    .description(curso.getDescripcion())
                    .emoji(curso.getEmoji() != null ? curso.getEmoji() : "📚")
                    .color(curso.getColor() != null ? curso.getColor() : "primary")
                    .bannerVersion(curso.getBannerVersion())
                    .weeks(semanas)
                    .studentCount(alumnos)
                    .build();
        }).toList();
    }

    public List<CursoDocenteDTO> obtenerCursosResumenAlumno(Long alumnoId) {
        List<Curso> cursos = cursoRepository.findCursosByAlumnoId(alumnoId);

        return cursos.stream().map(curso -> {
            return CursoDocenteDTO.builder()
                    .id(idHasher.encode(curso.getId()))
                    .name(curso.getNombre())
                    .description(curso.getDescripcion())
                    .emoji(curso.getEmoji() != null ? curso.getEmoji() : "📚")
                    .color(curso.getColor() != null ? curso.getColor() : "primary")
                    .bannerVersion(curso.getBannerVersion())
                    .build();
        }).toList();
    }

    private int extraerNumeroSemana(String numSem) {
        if (numSem == null) return 0;
        String digits = numSem.replaceAll("\\D+", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    /**
     * Las semanas de un curso, con su conteo de preguntas y sus materiales.
     *
     * El conteo se pide en UNA consulta agregada (ver PreguntaRepository.contarPorSemana) en
     * vez de recorrer `semana.getPreguntas()`: ese recorrido cargaba todas las preguntas y,
     * por el mapeo EAGER de sus alternativas, una consulta más por pregunta.
     */
    public List<SemanaDTO> obtenerSemanasPorCurso(Long cursoId) {
        List<Semana> semanas = semanaRepository.findByCursoId(cursoId);

        List<Long> ids = semanas.stream().map(Semana::getId).filter(java.util.Objects::nonNull).toList();
        Map<Long, Integer> preguntasPorSemana = ids.isEmpty()
                ? Map.of()
                : preguntaRepository.contarPorSemana(ids).stream().collect(Collectors.toMap(
                        fila -> ((Number) fila[0]).longValue(),
                        fila -> ((Number) fila[1]).intValue()));

        return semanas
                .stream()
                .sorted((s1, s2) -> {
                    int n1 = extraerNumeroSemana(s1.getNumSem());
                    int n2 = extraerNumeroSemana(s2.getNumSem());
                    if (n1 != n2) return Integer.compare(n1, n2);
                    return Long.compare(s1.getId() != null ? s1.getId() : 0L, s2.getId() != null ? s2.getId() : 0L);
                })
                .map(semana -> {
                    List<SemanaDTO.MaterialDTO> materialesDTO = null;

                    if (semana.getMateriales() != null) {
                        materialesDTO = semana.getMateriales().stream()
                                .map(mat -> SemanaDTO.MaterialDTO.builder()
                                        .id(idHasher.encode(mat.getId()))
                                        .nombreArchivo(mat.getNombreArchivo())
                                        .mongoId(mat.getMongoId())
                                        .visible(mat.isVisible())
                                        .fechaCarga(mat.getFechaCarga() != null ? mat.getFechaCarga().toString() : null)
                                        .build())
                                .collect(Collectors.toList());
                    }

                    return SemanaDTO.builder()
                            .id(idHasher.encode(semana.getId()))
                            .numSem(semana.getNumSem())
                            .nombreTema(semana.getNombreTema())
                            .habilitada(semana.isHabilitada())
                            .totalPreguntas(preguntasPorSemana.getOrDefault(semana.getId(), 0))
                            .materiales(materialesDTO)
                            .build();
                })
                .collect(Collectors.toList());
    }

    public void reordenarSemanas(Long cursoId, List<String> semanaIds) {
        if (semanaIds == null || semanaIds.isEmpty()) return;
        for (int i = 0; i < semanaIds.size(); i++) {
            Long semanaId = idHasher.decode(semanaIds.get(i));
            Semana semana = semanaRepository.findById(semanaId).orElse(null);
            if (semana != null && semana.getCurso() != null && semana.getCurso().getId().equals(cursoId)) {
                semana.setNumSem("Semana " + (i + 1));
                semanaRepository.save(semana);
            }
        }
    }

    public CursoResponseDTO actualizarCurso(Long courseId, com.example.tallerintegrador.DTO.CursoRequestDTO request) {
        Curso curso = cursoRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Curso no encontrado"));

        if (request.nombre() != null) curso.setNombre(request.nombre());
        if (request.descripcion() != null) curso.setDescripcion(request.descripcion());
        if (request.emoji() != null) curso.setEmoji(request.emoji());
        if (request.color() != null) curso.setColor(request.color());

        Curso actualizado = cursoRepository.save(curso);

        return CursoResponseDTO.builder()
                .id(idHasher.encode(actualizado.getId()))
                .name(actualizado.getNombre())
                .description(actualizado.getDescripcion())
                .emoji(actualizado.getEmoji())
                .color(actualizado.getColor())
                .bannerVersion(actualizado.getBannerVersion())
                .build();
    }

    // ── Portada del curso ────────────────────────────────────────────────────

    @Transactional
    public Long guardarBanner(Long courseId, byte[] imagen, String tipoContenido) {
        if (imagen == null || imagen.length == 0) {
            throw new IllegalArgumentException("La imagen está vacía");
        }
        if (imagen.length > MAX_BYTES_BANNER) {
            throw new IllegalArgumentException("La portada no puede superar 2 MB");
        }
        if (tipoContenido == null || !TIPOS_BANNER.contains(tipoContenido)) {
            throw new IllegalArgumentException("Formato no permitido: usa JPG, PNG o WEBP");
        }
        Curso curso = cursoRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Curso no encontrado"));

        com.example.tallerintegrador.entidades.postgres.CursoBanner banner =
                cursoBannerRepository.findById(courseId)
                        .orElseGet(com.example.tallerintegrador.entidades.postgres.CursoBanner::new);
        banner.setCursoId(courseId);
        banner.setImagen(imagen);
        banner.setTipoContenido(tipoContenido);
        cursoBannerRepository.save(banner);

        curso.setBannerVersion(System.currentTimeMillis());
        cursoRepository.save(curso);
        return curso.getBannerVersion();
    }

    public java.util.Optional<com.example.tallerintegrador.entidades.postgres.CursoBanner> obtenerBanner(Long courseId) {
        return cursoBannerRepository.findById(courseId);
    }

    @Transactional
    public void eliminarBanner(Long courseId) {
        Curso curso = cursoRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Curso no encontrado"));
        if (cursoBannerRepository.existsById(courseId)) cursoBannerRepository.deleteById(courseId);
        curso.setBannerVersion(null);
        cursoRepository.save(curso);
    }

    /** Un docente solo puede cambiar la portada de SUS cursos (titular o co-docente); el administrador, de cualquiera. */
    @Transactional(readOnly = true)
    public boolean puedeEditar(Long courseId, Usuario usuario) {
        if (usuario == null) return false;
        if (usuario.getRol() == com.example.tallerintegrador.entidades.postgres.Rol.ADMIN) return true;
        return cursoRepository.findById(courseId)
                .map(c -> c.esDocente(usuario.getId()))
                .orElse(false);
    }

    // ── Co-docentes ──────────────────────────────────────────────────────────

    /** Solo el titular y el administrador gestionan quién más enseña el curso. */
    @Transactional(readOnly = true)
    public boolean puedeGestionarDocentes(Long courseId, Usuario usuario) {
        if (usuario == null) return false;
        if (usuario.getRol() == com.example.tallerintegrador.entidades.postgres.Rol.ADMIN) return true;
        return cursoRepository.findById(courseId).map(c -> c.esTitular(usuario.getId())).orElse(false);
    }

    /** Titular y co-docentes, para mostrarlos en la pantalla del curso. Nunca expone entidades. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarDocentes(Long courseId) {
        Curso curso = cursoRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Curso no encontrado"));
        List<Map<String, Object>> salida = new java.util.ArrayList<>();
        if (curso.getProfesor() != null) {
            salida.add(filaDocente(curso.getProfesor(), true));
        }
        curso.getCoDocentes().stream()
                .sorted(java.util.Comparator.comparing(u -> String.valueOf(u.getNombre())))
                .forEach(u -> salida.add(filaDocente(u, false)));
        return salida;
    }

    private Map<String, Object> filaDocente(Usuario u, boolean titular) {
        Map<String, Object> fila = new java.util.LinkedHashMap<>();
        fila.put("id", idHasher.encode(u.getId()));
        fila.put("nombre", u.getNombre());
        fila.put("correo", u.getCorreo());
        fila.put("titular", titular);
        return fila;
    }

    /**
     * Añade un co-docente por su correo. Se busca por correo y no por id porque es lo que el
     * titular conoce de su colega; y se exige que la cuenta sea de DOCENTE, para que un alumno
     * no acabe con acceso de profesor por un error al teclear.
     */
    @Transactional
    public List<Map<String, Object>> agregarCoDocente(Long courseId, String correo) {
        if (correo == null || correo.isBlank()) {
            throw new IllegalArgumentException("Indica el correo o usuario del docente.");
        }
        Curso curso = cursoRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Curso no encontrado"));
        Usuario docente = userRepository.findByCorreo(correo.strip())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No existe ninguna cuenta con el correo o usuario " + correo.strip() + "."));
        if (docente.getRol() != com.example.tallerintegrador.entidades.postgres.Rol.TEACHER) {
            throw new IllegalArgumentException("Esa cuenta no es de docente: solo se pueden añadir profesores.");
        }
        if (curso.esTitular(docente.getId())) {
            throw new IllegalArgumentException("Esa persona ya es la docente titular del curso.");
        }
        curso.getCoDocentes().add(docente);
        cursoRepository.save(curso);
        return listarDocentes(courseId);
    }

    @Transactional
    public List<Map<String, Object>> quitarCoDocente(Long courseId, Long docenteId) {
        Curso curso = cursoRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Curso no encontrado"));
        if (curso.esTitular(docenteId)) {
            // Quitar al titular dejaría el curso sin nadie que pueda gestionar docentes.
            throw new IllegalArgumentException("No se puede quitar a la docente titular del curso.");
        }
        curso.getCoDocentes().removeIf(u -> u.getId().equals(docenteId));
        cursoRepository.save(curso);
        return listarDocentes(courseId);
    }

    @Transactional
    public void eliminarCurso(Long courseId) {
        Curso curso = cursoRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Curso no encontrado"));

        List<Long> semanaIds = semanaRepository.findByCursoId(courseId)
                .stream()
                .map(s -> s.getId())
                .toList();

        if (!semanaIds.isEmpty()) {
            List<Long> preguntaIds = preguntaRepository.findBySemanaIdIn(semanaIds)
                    .stream()
                    .map(p -> p.getId())
                    .toList();

            if (!preguntaIds.isEmpty()) {
                respuestaUsuarioRepository.deleteByPreguntaIdIn(preguntaIds);

                respuestaRepository.deleteByPreguntaIdIn(preguntaIds);

                preguntaRepository.deleteBySemanaIdIn(semanaIds);
            }

            materialRepository.deleteBySemanaIdIn(semanaIds);
        }

        matriculaRepository.deleteByCursoId(courseId);

        if (cursoBannerRepository.existsById(courseId)) cursoBannerRepository.deleteById(courseId);

        semanaRepository.deleteByCursoId(courseId);

        cursoRepository.delete(curso);
    }

    public void matricularAlumno(Long courseId, Long alumnoId) {
        Curso curso = cursoRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Curso no encontrado"));
        Usuario alumno = userRepository.findById(alumnoId)
                .orElseThrow(() -> new RuntimeException("Alumno no encontrado"));

        // VALIDACIÓN: Evitar matricular al mismo alumno dos veces
        if (matriculaRepository.findByCursoIdAndUsuarioId(courseId, alumnoId).isPresent()) {
            throw new RuntimeException("El alumno ya se encuentra matriculado en este curso");
        }

        com.example.tallerintegrador.entidades.postgres.Matricula matricula =
                new com.example.tallerintegrador.entidades.postgres.Matricula();
        matricula.setCurso(curso);
        matricula.setUsuario(alumno);

        matriculaRepository.save(matricula);
    }

    public void desmatricularAlumno(Long courseId, Long alumnoId) {
        com.example.tallerintegrador.entidades.postgres.Matricula matricula =
                matriculaRepository.findByCursoIdAndUsuarioId(courseId, alumnoId)
                        .orElseThrow(() -> new RuntimeException("El alumno no está matriculado en este curso"));

        matriculaRepository.delete(matricula);
    }

    public List<Map<String, Object>> obtenerAlumnosPorCurso(Long cursoId) {
        // Traemos todas las matrículas de este curso
        List<com.example.tallerintegrador.entidades.postgres.Matricula> matriculas =
                matriculaRepository.findByCursoId(cursoId);

        return matriculas.stream().map(m -> {
            Usuario alumno = m.getUsuario();
            return Map.<String, Object>of(
                    "id", alumno.getId(),
                    "nombre", alumno.getNombre(),
                    "correo", alumno.getCorreo()
            );
        }).collect(Collectors.toList());
    }

    public List<Map<String, Object>> buscarEstudiantesPorNombre(String nombre) {
        List<Usuario> estudiantes;
        if (nombre == null || nombre.trim().isEmpty()) {
            estudiantes = userRepository.findByRol(com.example.tallerintegrador.entidades.postgres.Rol.STUDENT);
        } else {
            estudiantes = userRepository.findByRolAndNombreContainingIgnoreCase(com.example.tallerintegrador.entidades.postgres.Rol.STUDENT, nombre);
        }
        return estudiantes.stream().map(u -> Map.<String, Object>of(
                "id", u.getId(),
                "nombre", u.getNombre(),
                "correo", u.getCorreo()
        )).collect(Collectors.toList());
    }

    public List<Map<String, Object>> obtenerRendimientoCursos(Long profesorId) {
        return cursoRepository.findRendimientoAlumnosPorCurso(profesorId);
    }
}