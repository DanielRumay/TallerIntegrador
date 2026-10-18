package com.example.tallerintegrador.config;

import com.example.tallerintegrador.entidades.postgres.Colegio;
import com.example.tallerintegrador.entidades.postgres.Permiso;
import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.ColegioRepository;
import com.example.tallerintegrador.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;

/**
 * Lleva los datos existentes al modelo con colegios. Corre en cada arranque y es idempotente:
 * la segunda vez no encuentra nada que cambiar.
 *
 * 1. Quita los CHECK que Hibernate 6 fija sobre columnas enum (rol, permiso): con
 *    ddl-auto=update no se regeneran, y el rol nuevo SUPERADMIN o un permiso nuevo fallarían
 *    al guardarse. Los enum de Java ya restringen los valores.
 * 2. Si no hay ningún colegio, crea el inicial (app.colegio-inicial) y le asigna todos los
 *    usuarios y cursos existentes. Los ADMIN que ya existían reciben todos los permisos y
 *    quedan como principales: nadie pierde el acceso que tenía.
 * 3. Asigna al colegio inicial cualquier usuario o curso que siga sin colegio.
 * 4. Si se definen SUPERADMIN_CORREO y SUPERADMIN_PASSWORD_INICIAL y esa cuenta no existe, la
 *    crea con cambio de contraseña obligatorio. Nunca convierte una cuenta existente.
 */
@Slf4j
@Order(10)
@Component
@RequiredArgsConstructor
public class MigracionColegios implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final ColegioRepository colegioRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.colegio-inicial:Colegio Privado Andrés Avelino Cáceres}")
    private String nombreColegioInicial;

    @Value("${app.superadmin.correo:}")
    private String superadminCorreo;

    @Value("${app.superadmin.password-inicial:}")
    private String superadminPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        quitarCheck("usuario", "usuario_rol_check");
        quitarCheck("usuario_permiso", "usuario_permiso_permiso_check");

        boolean primeraVez = colegioRepository.count() == 0;
        Colegio inicial = colegioRepository.findByNombreIgnoreCase(nombreColegioInicial)
                .orElseGet(() -> primeraVez ? colegioRepository.save(new Colegio(nombreColegioInicial)) : null);

        if (inicial != null) {
            int usuarios = jdbcTemplate.update(
                    "UPDATE usuario SET colegio_id = ? WHERE colegio_id IS NULL AND rol <> 'SUPERADMIN'", inicial.getId());
            int cursos = jdbcTemplate.update("UPDATE curso SET colegio_id = ? WHERE colegio_id IS NULL", inicial.getId());
            if (usuarios > 0 || cursos > 0) {
                log.info("[COLEGIOS] Asignados al colegio '{}': {} usuarios y {} cursos.", inicial.getNombre(), usuarios, cursos);
            }
        }

        if (primeraVez && inicial != null) {
            // La auditoría anterior a los colegios era del colegio inicial. Solo la primera vez:
            // después, una fila sin colegio es una acción del SUPERADMIN y debe seguir así.
            jdbcTemplate.update("UPDATE registro_auditoria SET colegio_id = ? WHERE colegio_id IS NULL", inicial.getId());
            for (Usuario admin : userRepository.findByColegioIdAndRol(inicial.getId(), Rol.ADMIN)) {
                admin.setAdminPrincipal(true);
                admin.setPermisos(EnumSet.allOf(Permiso.class));
                userRepository.save(admin);
            }
            log.info("[COLEGIOS] Colegio inicial creado; los administradores existentes conservan todos sus permisos.");
        }

        crearSuperadminSiCorresponde();
    }

    private void crearSuperadminSiCorresponde() {
        if (superadminCorreo == null || superadminCorreo.isBlank()) return;
        if (userRepository.findByCorreo(superadminCorreo.trim()).isPresent()) return;
        if (superadminPassword == null || superadminPassword.length() < 8) {
            log.warn("[COLEGIOS] SUPERADMIN_CORREO definido sin SUPERADMIN_PASSWORD_INICIAL valida (min. 8): no se crea.");
            return;
        }
        Usuario sa = new Usuario();
        sa.setNombre("Superadministrador");
        sa.setCorreo(superadminCorreo.trim());
        sa.setRol(Rol.SUPERADMIN);
        sa.setPassword(passwordEncoder.encode(superadminPassword));
        sa.setRequiresPasswordSetup(true);
        sa.setConsentimientoAceptado(true);
        userRepository.save(sa);
        log.info("[COLEGIOS] Cuenta SUPERADMIN creada: {}", sa.getCorreo());
    }

    private void quitarCheck(String tabla, String restriccion) {
        try {
            jdbcTemplate.execute("ALTER TABLE IF EXISTS " + tabla + " DROP CONSTRAINT IF EXISTS " + restriccion);
        } catch (Exception e) {
            log.warn("[COLEGIOS] No se pudo revisar {} en {}: {}", restriccion, tabla, e.getMessage());
        }
    }
}
