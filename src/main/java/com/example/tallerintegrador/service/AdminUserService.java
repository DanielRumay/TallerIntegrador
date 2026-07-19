package com.example.tallerintegrador.service;

import com.example.tallerintegrador.DTO.CreateUserRequest;
import com.example.tallerintegrador.DTO.UserResponseDTO;
import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.entidades.mongodb.BackupBD;
import com.example.tallerintegrador.repository.mongo.BackupBDRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final BackupBDRepository backupBDRepository;

    @Value("${app.dominio-institucional}")
    private String dominioInstitucional;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @Transactional(readOnly = true)
    public List<UserResponseDTO> obtenerTodosLosUsuarios() {
        return userRepository.findAll().stream()
                .map(u -> new UserResponseDTO(u.getId(), u.getNombre(), u.getCorreo(), u.getRol().name(), u.isCuentaBloqueada()))
                .toList();
    }

    private String procesarCorreoInstitucional(String emailInput) {
        if (emailInput == null || emailInput.isBlank()) {
            return emailInput;
        }
        String emailLimpio = emailInput.trim();
        if (!emailLimpio.contains("@")) {
            String dominio = (dominioInstitucional != null && !dominioInstitucional.isBlank()) 
                    ? dominioInstitucional 
                    : "gmail.com";
            if (!dominio.startsWith("@")) {
                dominio = "@" + dominio;
            }
            return emailLimpio + dominio;
        }
        return emailLimpio;
    }

    @Transactional
    public UserResponseDTO registrarUsuario(CreateUserRequest req) {
        String emailFinal = procesarCorreoInstitucional(req.email());
        if (userRepository.findByCorreo(emailFinal).isPresent()) {
            throw new RuntimeException("El correo ya está registrado en el sistema: " + emailFinal);
        }

        if (req.password() == null || req.password().trim().isEmpty()) {
            throw new RuntimeException("La contraseña no puede estar vacía.");
        }

        Usuario nuevoUsuario = new Usuario();
        nuevoUsuario.setNombre(req.name());
        nuevoUsuario.setCorreo(emailFinal);
        nuevoUsuario.setRol(req.role().equalsIgnoreCase("teacher") ? Rol.TEACHER : Rol.STUDENT);

        nuevoUsuario.setPassword(passwordEncoder.encode(req.password()));
        nuevoUsuario.setRequiresPasswordSetup(true);

        Usuario guardado = userRepository.save(nuevoUsuario);

        return new UserResponseDTO(
                guardado.getId(),
                guardado.getNombre(),
                guardado.getCorreo(),
                guardado.getRol().name(),
                guardado.isCuentaBloqueada()
        );
    }

    @Transactional
    public void eliminarUsuario(Long id) {
        if (!userRepository.existsById(id)) {
            throw new RuntimeException("Usuario no encontrado");
        }
        userRepository.deleteById(id);
    }

    @Transactional
    public List<UserResponseDTO> registrarUsuariosMasivo(List<CreateUserRequest> requests) {
        return requests.stream().map(req -> {
            String emailFinal = procesarCorreoInstitucional(req.email());
            if (userRepository.findByCorreo(emailFinal).isPresent()) {
                throw new RuntimeException("El correo ya está registrado en el sistema: " + emailFinal);
            }
            Usuario nuevoUsuario = new Usuario();
            nuevoUsuario.setNombre(req.name());
            nuevoUsuario.setCorreo(emailFinal);
            nuevoUsuario.setRol(req.role().equalsIgnoreCase("teacher") ? Rol.TEACHER : Rol.STUDENT);
            
            // Password genérico temporal y requiere setup
            nuevoUsuario.setPassword(passwordEncoder.encode(req.password() != null && !req.password().trim().isEmpty() ? req.password() : "123456"));
            nuevoUsuario.setRequiresPasswordSetup(true);

            Usuario guardado = userRepository.save(nuevoUsuario);
            return new UserResponseDTO(guardado.getId(), guardado.getNombre(), guardado.getCorreo(), guardado.getRol().name(), guardado.isCuentaBloqueada());
        }).toList();
    }

    @Transactional
    public void desbloquearUsuario(Long id) {
        Usuario usuario = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        usuario.setIntentosFallidos(0);
        usuario.setCuentaBloqueada(false);
        userRepository.save(usuario);
    }

    public Object obtenerMetricasAdmin() {
        long totalEstudiantes = userRepository.findAll().stream().filter(u -> u.getRol() == Rol.STUDENT).count();
        long totalDocentes = userRepository.findAll().stream().filter(u -> u.getRol() == Rol.TEACHER).count();
        long usuariosActivos = userRepository.findAll().stream().filter(u -> !u.isCuentaBloqueada()).count();
        
        return java.util.Map.of(
            "totalEstudiantes", totalEstudiantes,
            "totalDocentes", totalDocentes,
            "usuariosActivos", usuariosActivos
        );
    }

    public boolean toggleBlockUsuario(Long id) {
        Usuario usuario = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado con ID: " + id));
        usuario.setCuentaBloqueada(!usuario.isCuentaBloqueada());
        // Si lo estamos desbloqueando, reseteamos los intentos
        if (!usuario.isCuentaBloqueada()) {
            usuario.setIntentosFallidos(0);
        }
        userRepository.save(usuario);
        return usuario.isCuentaBloqueada();
    }

    @Transactional
    public void cambiarPasswordUsuario(Long id, String nuevaPassword) {
        if (nuevaPassword == null || nuevaPassword.trim().isEmpty()) {
            throw new RuntimeException("La contraseña no puede estar vacía.");
        }
        Usuario usuario = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado con ID: " + id));
        usuario.setPassword(passwordEncoder.encode(nuevaPassword));
        userRepository.save(usuario);
    }

    @Transactional
    public byte[] generarYGuardarBackup() {
        String pgDumpPath = findPgDumpPath();
        
        String host = "localhost";
        String port = "5432";
        String dbName = "colegio_db";
        
        try {
            if (dbUrl != null && dbUrl.startsWith("jdbc:postgresql://")) {
                String cleanUrl = dbUrl.substring("jdbc:postgresql://".length());
                if (cleanUrl.contains("?")) {
                    cleanUrl = cleanUrl.substring(0, cleanUrl.indexOf("?"));
                }
                String[] parts = cleanUrl.split("/");
                if (parts.length > 0) {
                    String hostPort = parts[0];
                    if (parts.length > 1) {
                        dbName = parts[1];
                    }
                    if (hostPort.contains(":")) {
                        String[] hp = hostPort.split(":");
                        host = hp[0];
                        port = hp[1];
                    } else {
                        host = hostPort;
                    }
                }
            }
        } catch (Exception e) {
            // Ignorar y usar fallbacks por defecto
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                pgDumpPath,
                "-h", host,
                "-p", port,
                "-U", dbUsername,
                "-F", "p",
                dbName
            );
            pb.environment().put("PGPASSWORD", dbPassword);
            
            Process process = pb.start();
            
            java.io.InputStream is = process.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }
            
            java.io.InputStream es = process.getErrorStream();
            java.io.ByteArrayOutputStream errorBos = new java.io.ByteArrayOutputStream();
            while ((bytesRead = es.read(buffer)) != -1) {
                errorBos.write(buffer, 0, bytesRead);
            }
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String errorMsg = errorBos.toString("UTF-8");
                throw new RuntimeException("pg_dump falló con código " + exitCode + ". Detalle: " + errorMsg);
            }
            
            byte[] sqlData = bos.toByteArray();
            if (sqlData.length == 0) {
                throw new RuntimeException("El archivo de backup generado está vacío.");
            }
            
            String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
            String nombreArchivo = dbName + "_backup_" + timestamp + ".sql";
            
            BackupBD backup = new BackupBD();
            backup.setNombreArchivo(nombreArchivo);
            backup.setFechaCreacion(LocalDateTime.now());
            backup.setData(sqlData);
            
            backupBDRepository.save(backup);
            
            return sqlData;
        } catch (Exception e) {
            throw new RuntimeException("Error al generar la copia de seguridad: " + e.getMessage(), e);
        }
    }

    private String findPgDumpPath() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"pg_dump", "--version"});
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return "pg_dump";
            }
        } catch (Exception e) {
            // Ignorar y probar rutas por defecto
        }

        File pgDir = new File("C:\\Program Files\\PostgreSQL");
        if (pgDir.exists() && pgDir.isDirectory()) {
            File[] versions = pgDir.listFiles();
            if (versions != null) {
                for (File version : versions) {
                    File pgDump = new File(version, "bin\\pg_dump.exe");
                    if (pgDump.exists() && pgDump.isFile()) {
                        return pgDump.getAbsolutePath();
                    }
                }
            }
        }

        return "pg_dump";
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void backupProgramado() {
        log.info("[BACKUP] Iniciando copia de seguridad programada automática a las 2:00 AM...");
        try {
            byte[] backup = generarYGuardarBackup();
            log.info("[BACKUP] Copia de seguridad programada exitosa. Tamaño: {} bytes", backup.length);
        } catch (Exception e) {
            log.error("[BACKUP] Error en la copia de seguridad programada: {}", e.getMessage(), e);
        }
    }
}