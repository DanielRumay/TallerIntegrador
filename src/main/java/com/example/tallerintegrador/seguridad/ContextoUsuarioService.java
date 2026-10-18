package com.example.tallerintegrador.seguridad;

import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carga el ContextoUsuario de cada petición y lo guarda unos segundos en memoria.
 *
 * QUE RECHAZA. Devuelve vacío (la petición se responde con 401) si la cuenta ya no existe, está
 * bloqueada, o su colegio está desactivado. Así un bloqueo, una baja o la desactivación de un
 * colegio cortan las sesiones abiertas en la siguiente petición: es la revocación de sesiones
 * del RFS-08.
 *
 * CACHE. Una consulta por petición sería innecesaria: se guarda TTL_MS. Toda operación que
 * cambia permisos, bloqueo, baja o colegio llama a invalidar(), por lo que el cambio rige de
 * inmediato; el TTL solo acota lo que se modifique por fuera de la aplicación.
 */
@Service
@RequiredArgsConstructor
public class ContextoUsuarioService {

    static final long TTL_MS = 30_000;

    private final UserRepository userRepository;

    private record Entrada(Optional<ContextoUsuario> contexto, long expira) {}

    private final ConcurrentHashMap<String, Entrada> cache = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public Optional<ContextoUsuario> cargar(String correo) {
        if (correo == null) return Optional.empty();
        long ahora = System.currentTimeMillis();
        Entrada e = cache.get(correo);
        if (e != null && e.expira() > ahora) return e.contexto();

        Optional<ContextoUsuario> ctx = userRepository.findByCorreo(correo).flatMap(this::aContexto);
        cache.put(correo, new Entrada(ctx, ahora + TTL_MS));
        return ctx;
    }

    private Optional<ContextoUsuario> aContexto(Usuario u) {
        if (u.isCuentaBloqueada()) return Optional.empty();
        if (u.getRol() != Rol.SUPERADMIN) {
            // Todo usuario que no sea SUPERADMIN debe tener un colegio activo.
            if (u.getColegio() == null || !u.getColegio().isActivo()) return Optional.empty();
        }
        return Optional.of(new ContextoUsuario(u.getId(), u.getCorreo(), u.getRol(), u.colegioId(),
                u.isAdminPrincipal(), u.getPermisos()));
    }

    public void invalidar(String correo) {
        if (correo != null) cache.remove(correo);
    }

    /** Tras desactivar un colegio: afecta a todos sus usuarios a la vez. */
    public void invalidarTodo() {
        cache.clear();
    }
}
