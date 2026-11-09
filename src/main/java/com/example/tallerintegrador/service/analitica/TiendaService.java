package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.MovimientoMonedas;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.MovimientoMonedasRepository;
import com.example.tallerintegrador.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * Tienda de monedas.
 *
 * QUÉ SE PUEDE COMPRAR Y QUÉ NO. Solo cosas que ayudan a aprender o que son estéticas: proteger
 * la racha y vestir a Aria. NUNCA nota, XP de la liga ni saltarse una evaluación: comprar
 * ventaja convertiría la liga en "gana quien más ahorró" y la nota dejaría de medir lo que el
 * alumno sabe.
 *
 * Lo comprado vive en el libro de movimientos con una clave ("accesorio:gorro",
 * "protector:2026-11-03"): la clave hace que no se pueda comprar dos veces lo mismo.
 */
@Service
@RequiredArgsConstructor
public class TiendaService {

    private final MonedasService monedasService;
    private final MovimientoMonedasRepository movimientoRepository;
    private final UserRepository userRepository;

    public enum Ranura { CABEZA, CUELLO, PELAJE }

    public record Articulo(String id, String nombre, String descripcion, int precio, Ranura ranura) {}

    /** "Aria dorada" es el lujo caro: absorbe las monedas de quien acumula mucho. */
    static final List<Articulo> ACCESORIOS = List.of(
            new Articulo("bufanda", "Bufanda", "Abrigada para las tardes de estudio.", 40, Ranura.CUELLO),
            new Articulo("gorro", "Gorro de lana", "Con pompón, reemplaza al birrete.", 60, Ranura.CABEZA),
            new Articulo("audifonos", "Audífonos", "Para concentrarse con su música.", 80, Ranura.CABEZA),
            new Articulo("corona", "Corona", "Para quien reina en su práctica.", 150, Ranura.CABEZA),
            new Articulo("dorada", "Aria dorada", "El pelaje más brillante de Semantika.", 1000, Ranura.PELAJE));

    static final int PRECIO_PROTECTOR = 50;

    /** Catálogo con lo que el alumno ya tiene y lleva puesto, más los días protegidos. */
    @Transactional(readOnly = true)
    public Map<String, Object> catalogo(Long usuarioId) {
        Set<String> mios = accesoriosDe(usuarioId);
        Set<String> puestos = equipadoDe(userRepository.findById(usuarioId).orElseThrow());
        List<Map<String, Object>> articulos = ACCESORIOS.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("nombre", a.nombre());
            m.put("descripcion", a.descripcion());
            m.put("precio", a.precio());
            m.put("ranura", a.ranura().name());
            m.put("comprado", mios.contains(a.id()));
            m.put("puesto", puestos.contains(a.id()));
            return m;
        }).toList();

        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("saldo", monedasService.saldo(usuarioId));
        salida.put("accesorios", articulos);
        salida.put("precioProtector", PRECIO_PROTECTOR);
        salida.put("diasProtegidos", diasProtegidos(usuarioId).stream().map(LocalDate::toString).sorted().toList());
        salida.put("mananaProtegido", diasProtegidos(usuarioId).contains(LocalDate.now().plusDays(1)));
        return salida;
    }

    @Transactional
    public int comprarAccesorio(Long usuarioId, String id) {
        Articulo a = ACCESORIOS.stream().filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Ese artículo no existe."));
        return monedasService.gastar(usuarioId, a.precio(), "Compraste: " + a.nombre(), "accesorio:" + a.id());
    }

    /**
     * Protege la racha de MAÑANA: si ese día no practica, la racha no se corta. Solo mañana,
     * no días lejanos, para que sea un salvavidas y no una forma de dejar de practicar.
     */
    @Transactional
    public int protegerManana(Long usuarioId) {
        LocalDate manana = LocalDate.now().plusDays(1);
        return monedasService.gastar(usuarioId, PRECIO_PROTECTOR,
                "Protector de racha para el " + manana, "protector:" + manana);
    }

    /** Pone los accesorios indicados (uno por ranura) y quita el resto. */
    @Transactional
    public Set<String> equipar(Long usuarioId, Collection<String> ids) {
        Set<String> mios = accesoriosDe(usuarioId);
        Set<Ranura> ocupadas = EnumSet.noneOf(Ranura.class);
        LinkedHashSet<String> puestos = new LinkedHashSet<>();
        for (String id : ids) {
            Articulo a = ACCESORIOS.stream().filter(x -> x.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Ese artículo no existe."));
            if (!mios.contains(id)) throw new IllegalArgumentException("Primero tienes que comprar " + a.nombre() + ".");
            if (!ocupadas.add(a.ranura())) throw new IllegalArgumentException("Solo un accesorio por lugar.");
            puestos.add(id);
        }
        Usuario u = userRepository.findById(usuarioId).orElseThrow();
        u.setAriaEquipado(puestos.isEmpty() ? null : String.join(",", puestos));
        userRepository.save(u);
        return puestos;
    }

    Set<String> accesoriosDe(Long usuarioId) {
        Set<String> ids = new HashSet<>();
        for (MovimientoMonedas m : movimientoRepository.findByUsuarioIdAndClaveStartingWith(usuarioId, "accesorio:")) {
            ids.add(m.getClave().substring("accesorio:".length()));
        }
        return ids;
    }

    public Set<LocalDate> diasProtegidos(Long usuarioId) {
        Set<LocalDate> dias = new HashSet<>();
        for (MovimientoMonedas m : movimientoRepository.findByUsuarioIdAndClaveStartingWith(usuarioId, "protector:")) {
            dias.add(LocalDate.parse(m.getClave().substring("protector:".length())));
        }
        return dias;
    }

    public static Set<String> equipadoDe(Usuario u) {
        if (u.getAriaEquipado() == null || u.getAriaEquipado().isBlank()) return Set.of();
        return new LinkedHashSet<>(Arrays.asList(u.getAriaEquipado().split(",")));
    }
}
