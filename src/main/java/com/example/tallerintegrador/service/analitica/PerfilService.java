package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.Matricula;
import com.example.tallerintegrador.entidades.postgres.MovimientoMonedas;
import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.IntentoRepository;
import com.example.tallerintegrador.repository.MatriculaRepository;
import com.example.tallerintegrador.repository.MovimientoMonedasRepository;
import com.example.tallerintegrador.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Perfil de juego que un alumno puede ver de un compañero: su Aria, su nivel y sus rachas.
 *
 * Sigue la misma regla que la liga: los compañeros ven el APODO, nunca el nombre, y nunca notas
 * (son menores de edad y su desempeño es dato personal, Ley 29733). Solo se ve el perfil de
 * alguien con quien se comparte un curso del mismo colegio, y cada alumno puede ocultar el suyo.
 */
@Service
@RequiredArgsConstructor
public class PerfilService {

    static final Set<String> VARIANTES = Set.of("clasica", "nocturna", "perro");

    private final UserRepository userRepository;
    private final MatriculaRepository matriculaRepository;
    private final ProgresoAlumnoService progresoAlumnoService;
    private final IntentoRepository intentoRepository;
    private final MovimientoMonedasRepository movimientoRepository;
    private final TiendaService tiendaService;

    /**
     * Perfil de {@code otroId} visto por {@code yoId}. Vacío si no comparten curso: así no se
     * distingue un id inexistente de uno ajeno.
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> perfil(Long yoId, Long otroId) {
        if (otroId == null) return Optional.empty();
        Usuario otro = userRepository.findById(otroId).orElse(null);
        if (otro == null || otro.getRol() != Rol.STUDENT) return Optional.empty();
        boolean esTu = otroId.equals(yoId);
        if (!esTu && !compartenCurso(yoId, otro)) return Optional.empty();

        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("apodo", GamificacionService.apodoDe(otro));
        salida.put("esTu", esTu);
        boolean visible = !Boolean.FALSE.equals(otro.getPerfilVisible());
        salida.put("visible", visible);
        if (!visible && !esTu) {
            salida.put("privado", true);
            return Optional.of(salida);
        }
        salida.put("privado", false);

        // Del progreso solo sale actividad; mejorNota, medias y última nota se quedan fuera.
        Map<String, Object> progreso = progresoAlumnoService.progresoDe(otroId);
        int xp = (Integer) progreso.get("puntos");
        int racha = (Integer) progreso.get("racha");
        salida.put("nivel", GamificacionService.nivelDe(xp));
        salida.put("titulo", progreso.get("rango"));
        salida.put("tituloEmoji", progreso.get("rangoEmoji"));
        salida.put("racha", racha);
        salida.put("mejorRacha", Math.max(racha, MonedasService.rachaMasLarga(intentoRepository.findByUsuarioIdOrderByFechaDesc(otroId))));
        salida.put("conceptosDominados", progreso.get("conceptosDominados"));
        salida.put("practicas", progreso.get("evaluacionesCompletadas"));
        salida.put("medallas", medallasDe(otroId));

        Set<String> coleccion = tiendaService.accesoriosDe(otroId);
        Map<String, Object> aria = new LinkedHashMap<>();
        // Sin elección guardada, el de su etapa, igual que en su propia pantalla.
        String etapa = GamificacionService.etapaDe(otro.getGrado() == null ? null : otro.getGrado().getNombre());
        aria.put("variante", otro.getAriaVariante() != null ? otro.getAriaVariante()
                : "SECUNDARIA".equals(etapa) ? "nocturna" : "clasica");
        aria.put("equipado", TiendaService.equipadoDe(otro));
        aria.put("coleccion", TiendaService.ACCESORIOS.stream().filter(a -> coleccion.contains(a.id())).map(TiendaService.Articulo::id).toList());
        aria.put("totalColeccion", TiendaService.ACCESORIOS.size());
        aria.put("animo", animoDeAria(racha));
        salida.put("aria", aria);
        return Optional.of(salida);
    }

    /**
     * Cómo se ve su Aria según lo bien que la cuida (su racha). Sin práctica reciente se queda
     * dormida, nunca triste: el perfil lo ven otros y no debe exponer a nadie.
     */
    static String animoDeAria(int racha) {
        if (racha >= 7) return "orgullosa";
        if (racha >= 1) return "feliz";
        return "durmiendo";
    }

    /** Podios de la liga semanal, contados desde los premios cobrados (clave "podio:..."). */
    Map<String, Integer> medallasDe(Long usuarioId) {
        Map<String, Integer> m = new LinkedHashMap<>(Map.of("oro", 0, "plata", 0, "bronce", 0));
        for (MovimientoMonedas mov : movimientoRepository.findByUsuarioIdAndClaveStartingWith(usuarioId, "podio:")) {
            String motivo = mov.getMotivo() == null ? "" : mov.getMotivo();
            if (motivo.startsWith("1.")) m.merge("oro", 1, Integer::sum);
            else if (motivo.startsWith("2.")) m.merge("plata", 1, Integer::sum);
            else if (motivo.startsWith("3.")) m.merge("bronce", 1, Integer::sum);
        }
        return m;
    }

    private boolean compartenCurso(Long yoId, Usuario otro) {
        Set<Long> misCursos = new HashSet<>();
        Long miColegio = null;
        for (Matricula m : matriculaRepository.findByUsuarioId(yoId)) {
            misCursos.add(m.getCurso().getId());
            if (m.getCurso().getColegio() != null) miColegio = m.getCurso().getColegio().getId();
        }
        if (!Objects.equals(miColegio, otro.colegioId())) return false;
        return matriculaRepository.findByUsuarioId(otro.getId()).stream()
                .anyMatch(m -> misCursos.contains(m.getCurso().getId()));
    }

    /** Guarda lo que el alumno decide de su propio perfil: si se ve y el estilo de su Aria. */
    @Transactional
    public Map<String, Object> ajustar(Long usuarioId, Boolean visible, String variante) {
        if (variante != null && !VARIANTES.contains(variante)) throw new IllegalArgumentException("Ese estilo de Aria no existe.");
        Usuario u = userRepository.findById(usuarioId).orElseThrow();
        if (visible != null) u.setPerfilVisible(visible);
        if (variante != null) u.setAriaVariante(variante);
        userRepository.save(u);
        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("visible", !Boolean.FALSE.equals(u.getPerfilVisible()));
        salida.put("variante", u.getAriaVariante());
        return salida;
    }
}
