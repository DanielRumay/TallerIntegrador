package com.example.tallerintegrador.service.academico;

import com.example.tallerintegrador.entidades.postgres.Permiso;
import com.example.tallerintegrador.entidades.postgres.RegistroAuditoria.Accion;
import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.exception.RecursoNoEncontradoException;
import com.example.tallerintegrador.repository.ColegioRepository;
import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.seguridad.ColegioGuard;
import com.example.tallerintegrador.seguridad.ContextoUsuario;
import com.example.tallerintegrador.seguridad.ContextoUsuarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Administradores de un colegio y sus permisos (miniadministradores).
 *
 * REGLAS CONTRA LA ESCALADA DE PRIVILEGIOS. Sin ellas, un miniadministrador con permiso para
 * gestionar administradores podría darse a sí mismo (o a un cómplice) cualquier permiso:
 *  1. Nadie otorga ni quita un permiso que no tiene: la diferencia entre los permisos de antes y
 *     los de después debe estar contenida en los permisos de quien hace el cambio.
 *  2. Nadie se modifica, bloquea ni elimina a sí mismo desde aquí.
 *  3. Los administradores PRINCIPALES no se tocan desde aquí; solo el SUPERADMIN los gestiona.
 *     Así un colegio nunca se queda sin un administrador con todos los permisos.
 * Toda operación queda en la auditoría del colegio.
 */
@Service
@RequiredArgsConstructor
public class AdministradoresService {

    private final UserRepository userRepository;
    private final ColegioRepository colegioRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditoriaService auditoriaService;
    private final ContextoUsuarioService contextoUsuarioService;

    @Value("${app.cuentas.exigir-cambio-contrasena:false}")
    private boolean exigirCambioContrasena;

    public record AdministradorDTO(Long id, String nombre, String correo, boolean adminPrincipal,
                                   boolean cuentaBloqueada, List<String> permisos) {}

    @Transactional(readOnly = true)
    public List<AdministradorDTO> listar() {
        Long colegioId = ColegioGuard.colegioObligatorio();
        return userRepository.findByColegioIdAndRol(colegioId, Rol.ADMIN).stream()
                .sorted(Comparator.comparing(Usuario::isAdminPrincipal).reversed().thenComparing(Usuario::getNombre))
                .map(AdministradoresService::aDto)
                .toList();
    }

    @Transactional
    public AdministradorDTO crear(String nombre, String correo, String password, Set<Permiso> permisos) {
        ContextoUsuario actor = actor();
        if (nombre == null || nombre.isBlank()) throw new IllegalArgumentException("El nombre es obligatorio.");
        if (correo == null || correo.isBlank()) throw new IllegalArgumentException("El correo o usuario es obligatorio.");
        if (password == null || password.length() < 8) throw new IllegalArgumentException("La contraseña debe tener al menos 8 caracteres.");
        Set<Permiso> pedidos = EnumSet.copyOf(nonEmpty(permisos == null ? Set.of() : permisos));
        exigirQuePuedeOtorgar(actor, pedidos);
        String correoLimpio = correo.trim();
        if (userRepository.existeCorreo(correoLimpio)) {
            throw new IllegalArgumentException("El correo o usuario ya está registrado en el sistema: " + correoLimpio);
        }

        Usuario admin = new Usuario();
        admin.setNombre(nombre.trim());
        admin.setCorreo(correoLimpio);
        admin.setRol(Rol.ADMIN);
        admin.setPassword(passwordEncoder.encode(password));
        admin.setRequiresPasswordSetup(exigirCambioContrasena);
        admin.setColegio(colegioRepository.getReferenceById(actor.colegioId()));
        admin.setCreadoPorId(actor.id());
        admin.setAdminPrincipal(false);
        admin.setPermisos(pedidos);
        Usuario guardado = userRepository.save(admin);

        auditoriaService.registrar(Accion.ADMIN_CREADO, "administrador", guardado.getId(),
                "Miniadministrador " + guardado.getCorreo() + " con permisos " + nombres(pedidos));
        return aDto(guardado);
    }

    @Transactional
    public AdministradorDTO cambiarPermisos(Long id, Set<Permiso> nuevos) {
        ContextoUsuario actor = actor();
        Usuario objetivo = administradorGestionable(id, actor);
        Set<Permiso> antes = objetivo.getPermisos().isEmpty() ? EnumSet.noneOf(Permiso.class) : EnumSet.copyOf(objetivo.getPermisos());
        Set<Permiso> despues = nuevos == null || nuevos.isEmpty() ? EnumSet.noneOf(Permiso.class) : EnumSet.copyOf(nuevos);

        // Lo que cambia (lo que se otorga y lo que se quita) debe estar dentro de lo que tiene el actor.
        Set<Permiso> cambio = EnumSet.noneOf(Permiso.class);
        for (Permiso p : Permiso.values()) {
            if (antes.contains(p) != despues.contains(p)) cambio.add(p);
        }
        exigirQuePuedeOtorgar(actor, cambio);

        objetivo.setPermisos(despues);
        userRepository.save(objetivo);
        contextoUsuarioService.invalidar(objetivo.getCorreo()); // rige en su siguiente petición
        auditoriaService.registrar(Accion.PERMISOS_CAMBIADOS, "administrador", objetivo.getId(),
                objetivo.getCorreo() + ": " + nombres(antes) + " -> " + nombres(despues));
        return aDto(objetivo);
    }

    @Transactional
    public boolean alternarBloqueo(Long id) {
        ContextoUsuario actor = actor();
        Usuario objetivo = administradorGestionable(id, actor);
        objetivo.setCuentaBloqueada(!objetivo.isCuentaBloqueada());
        if (!objetivo.isCuentaBloqueada()) objetivo.setIntentosFallidos(0);
        userRepository.save(objetivo);
        contextoUsuarioService.invalidar(objetivo.getCorreo());
        auditoriaService.registrar(objetivo.isCuentaBloqueada() ? Accion.USUARIO_BLOQUEADO : Accion.USUARIO_DESBLOQUEADO,
                "administrador", objetivo.getId(), objetivo.getCorreo());
        return objetivo.isCuentaBloqueada();
    }

    @Transactional
    public void eliminar(Long id) {
        ContextoUsuario actor = actor();
        Usuario objetivo = administradorGestionable(id, actor);
        auditoriaService.registrar(Accion.ADMIN_ELIMINADO, "administrador", objetivo.getId(), objetivo.getCorreo());
        userRepository.delete(objetivo);
        contextoUsuarioService.invalidar(objetivo.getCorreo());
    }

    // ── reglas ──────────────────────────────────────────────────────────────

    private ContextoUsuario actor() {
        ContextoUsuario actor = ContextoUsuario.actual();
        if (actor == null || actor.rol() != Rol.ADMIN || !actor.tiene(Permiso.ADMINISTRADORES_GESTIONAR)) {
            throw new RecursoNoEncontradoException("Recurso no encontrado");
        }
        return actor;
    }

    /** Administrador del mismo colegio, que no sea quien opera ni un principal. */
    private Usuario administradorGestionable(Long id, ContextoUsuario actor) {
        Usuario objetivo = userRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Administrador no encontrado"));
        ColegioGuard.exigir(objetivo);
        if (objetivo.getRol() != Rol.ADMIN) throw new RecursoNoEncontradoException("Administrador no encontrado");
        if (objetivo.getId().equals(actor.id())) {
            throw new IllegalArgumentException("No puedes modificar tu propia cuenta de administrador.");
        }
        if (objetivo.isAdminPrincipal()) {
            throw new IllegalArgumentException("Un administrador principal solo puede ser modificado por el superadministrador.");
        }
        return objetivo;
    }

    static void exigirQuePuedeOtorgar(ContextoUsuario actor, Set<Permiso> permisos) {
        for (Permiso p : permisos) {
            if (!actor.permisos().contains(p)) {
                throw new IllegalArgumentException("No puedes otorgar ni quitar un permiso que no tienes: " + p.name());
            }
        }
    }

    private static Set<Permiso> nonEmpty(Set<Permiso> p) {
        if (p.isEmpty()) throw new IllegalArgumentException("Un administrador debe tener al menos un permiso.");
        return p;
    }

    private static String nombres(Set<Permiso> permisos) {
        return permisos.isEmpty() ? "[]" : permisos.stream().map(Enum::name).sorted().toList().toString();
    }

    private static AdministradorDTO aDto(Usuario u) {
        return new AdministradorDTO(u.getId(), u.getNombre(), u.getCorreo(), u.isAdminPrincipal(),
                u.isCuentaBloqueada(), u.getPermisos().stream().map(Enum::name).sorted().toList());
    }
}
