package com.example.tallerintegrador.service.academico;

import com.example.tallerintegrador.entidades.postgres.Colegio;
import com.example.tallerintegrador.entidades.postgres.Permiso;
import com.example.tallerintegrador.entidades.postgres.RegistroAuditoria.Accion;
import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.exception.RecursoNoEncontradoException;
import com.example.tallerintegrador.repository.ColegioRepository;
import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.seguridad.ContextoUsuarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Operaciones del SUPERADMIN: alta de colegios, de su administrador principal y activación.
 *
 * El SUPERADMIN gestiona la plataforma, no los datos académicos: aquí solo ve el nombre de cada
 * colegio y cuántas cuentas tiene, nunca notas, intentos ni datos personales de los estudiantes.
 */
@Service
@RequiredArgsConstructor
public class ColegiosService {

    private final ColegioRepository colegioRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditoriaService auditoriaService;
    private final ContextoUsuarioService contextoUsuarioService;

    public record ColegioDTO(Long id, String nombre, boolean activo, long administradores, long docentes,
                             long estudiantes, List<String> administradoresPrincipales) {}

    @Transactional(readOnly = true)
    public List<ColegioDTO> listar() {
        return colegioRepository.findAll().stream()
                .sorted(Comparator.comparing(Colegio::getNombre))
                .map(this::aDto)
                .toList();
    }

    @Transactional
    public ColegioDTO crear(String nombreColegio, String adminNombre, String adminCorreo, String adminPassword) {
        if (nombreColegio == null || nombreColegio.isBlank()) throw new IllegalArgumentException("El nombre del colegio es obligatorio.");
        if (colegioRepository.findByNombreIgnoreCase(nombreColegio.trim()).isPresent()) {
            throw new IllegalArgumentException("Ya existe un colegio con ese nombre.");
        }
        Colegio colegio = colegioRepository.save(new Colegio(nombreColegio.trim()));
        auditoriaService.registrar(Accion.COLEGIO_CREADO, "colegio", colegio.getId(), colegio.getNombre());
        crearPrincipal(colegio, adminNombre, adminCorreo, adminPassword);
        return aDto(colegio);
    }

    /** Agrega otro administrador principal (p. ej. si el anterior dejó la institución). */
    @Transactional
    public ColegioDTO agregarAdministradorPrincipal(Long colegioId, String nombre, String correo, String password) {
        Colegio colegio = colegioRepository.findById(colegioId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Colegio no encontrado"));
        crearPrincipal(colegio, nombre, correo, password);
        return aDto(colegio);
    }

    @Transactional
    public ColegioDTO cambiarEstado(Long colegioId, boolean activo) {
        Colegio colegio = colegioRepository.findById(colegioId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Colegio no encontrado"));
        colegio.setActivo(activo);
        colegioRepository.save(colegio);
        // Las sesiones abiertas de sus usuarios se cortan en la siguiente petición.
        contextoUsuarioService.invalidarTodo();
        auditoriaService.registrar(activo ? Accion.COLEGIO_ACTIVADO : Accion.COLEGIO_DESACTIVADO,
                "colegio", colegio.getId(), colegio.getNombre());
        return aDto(colegio);
    }

    private void crearPrincipal(Colegio colegio, String nombre, String correo, String password) {
        if (nombre == null || nombre.isBlank()) throw new IllegalArgumentException("El nombre del administrador es obligatorio.");
        if (correo == null || correo.isBlank()) throw new IllegalArgumentException("El correo del administrador es obligatorio.");
        if (password == null || password.length() < 8) throw new IllegalArgumentException("La contraseña debe tener al menos 8 caracteres.");
        if (userRepository.existeCorreo(correo.trim())) {
            throw new IllegalArgumentException("El correo o usuario ya está registrado en el sistema: " + correo.trim());
        }
        Usuario admin = new Usuario();
        admin.setNombre(nombre.trim());
        admin.setCorreo(correo.trim());
        admin.setRol(Rol.ADMIN);
        admin.setPassword(passwordEncoder.encode(password));
        admin.setRequiresPasswordSetup(true); // la contraseña la eligió otra persona
        admin.setColegio(colegio);
        admin.setAdminPrincipal(true);
        admin.setPermisos(EnumSet.allOf(Permiso.class));
        Usuario guardado = userRepository.save(admin);
        auditoriaService.registrar(Accion.ADMIN_CREADO, "administrador", guardado.getId(),
                "Administrador principal " + guardado.getCorreo() + " del colegio " + colegio.getNombre());
    }

    private ColegioDTO aDto(Colegio c) {
        List<Usuario> admins = userRepository.findByColegioIdAndRol(c.getId(), Rol.ADMIN);
        return new ColegioDTO(c.getId(), c.getNombre(), c.isActivo(), admins.size(),
                userRepository.countByColegioIdAndRol(c.getId(), Rol.TEACHER),
                userRepository.countByColegioIdAndRol(c.getId(), Rol.STUDENT),
                admins.stream().filter(Usuario::isAdminPrincipal).map(Usuario::getCorreo).sorted().toList());
    }
}
