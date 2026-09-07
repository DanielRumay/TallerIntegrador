package com.example.tallerintegrador.agents.committee;
import com.example.tallerintegrador.service.analitica.AdaptiveLearningService;

import com.example.tallerintegrador.entidades.postgres.Intento;
import com.example.tallerintegrador.entidades.postgres.RespuestaUsuario;
import com.example.tallerintegrador.entidades.postgres.TipoEvaluacion;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.IntentoRepository;
import com.example.tallerintegrador.repository.RespuestaUsuarioRepository;
import com.example.tallerintegrador.repository.UserRepository;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Herramientas reales que el comité de agentes puede invocar durante la deliberación.
 *
 * Antes de este cambio, cada agente del debate recibía sus "datos" ya calculados e
 * inyectados en un String.format por AdaptiveLearningService — no consultaba nada por sí
 * mismo, así que técnicamente no razonaba sobre evidencia, redactaba sobre un resumen que
 * ya le habían entregado hecho. Con @Tool, el propio LLM decide qué necesita consultar y
 * cita datos que él mismo fue a buscar, lo cual es la diferencia real entre un prompt que
 * describe datos y un agente que los consulta.
 *
 * Cada método está deliberadamente acotado a lo que YA existe en el esquema (no se inventa
 * ninguna columna nueva para esto): notas e intentos, preguntas falladas recientes, y el
 * perfil declarado del alumno.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HerramientasComite {

    private final IntentoRepository intentoRepository;
    private final RespuestaUsuarioRepository respuestaUsuarioRepository;
    private final UserRepository userRepository;

    public record IntentoResumen(String fecha, Double nota, String tipoEvaluacion, Integer tiempoSegundos) {}

    @Tool({"Devuelve el historial de las últimas N evaluaciones formativas del alumno",
           "(excluye la prueba diagnóstica ACRA), con fecha, nota sobre 20 y tiempo empleado.",
           "Úsalo para fundamentar cualquier afirmación sobre el rendimiento reciente del alumno."})
    public List<IntentoResumen> historialDeNotas(
            @P("id del alumno") long usuarioId,
            @P("cantidad máxima de intentos a devolver, del más reciente al más antiguo") int n) {
        log.info("[HERRAMIENTA-COMITE] historialDeNotas(usuarioId={}, n={})", usuarioId, n);
        return intentoRepository.findByUsuarioIdOrderByFechaDesc(usuarioId).stream()
                .filter(i -> i.getTipoEvaluacion() != TipoEvaluacion.DIAGNOSTICA)
                .limit(Math.max(1, n))
                .map(i -> new IntentoResumen(
                        i.getFecha() != null ? i.getFecha().toString() : null,
                        i.getNota(),
                        i.getTipoEvaluacion() != null ? i.getTipoEvaluacion().name() : null,
                        i.getTiempoEmpleadoSegundos()))
                .toList();
    }

    @Tool({"Devuelve el texto de las últimas N preguntas que el alumno respondió de forma",
           "INCORRECTA, de la más reciente a la más antigua. Úsalo para identificar patrones",
           "de error concretos en vez de generalizar sin evidencia."})
    public List<String> preguntasFalladasRecientes(
            @P("id del alumno") long usuarioId,
            @P("cantidad máxima de preguntas falladas a devolver") int n) {
        log.info("[HERRAMIENTA-COMITE] preguntasFalladasRecientes(usuarioId={}, n={})", usuarioId, n);
        return respuestaUsuarioRepository.findByUsuarioIdAndCorrectaFalseOrderByFechaCreacionDesc(usuarioId).stream()
                .filter(r -> r.getPregunta() != null && r.getPregunta().getPregunta() != null)
                .limit(Math.max(1, n))
                .map(r -> r.getPregunta().getPregunta())
                .toList();
    }

    public record PerfilAlumno(String nivelConocimientoActual, String dificultadesDetectadasPrevias) {}

    @Tool({"Devuelve el perfil pedagógico declarado del alumno: su nivel de conocimiento",
           "vigente y las dificultades conceptuales detectadas en evaluaciones anteriores."})
    public PerfilAlumno perfilDelAlumno(@P("id del alumno") long usuarioId) {
        log.info("[HERRAMIENTA-COMITE] perfilDelAlumno(usuarioId={})", usuarioId);
        Usuario usuario = userRepository.findById(usuarioId).orElse(null);
        if (usuario == null) {
            return new PerfilAlumno("DESCONOCIDO", null);
        }
        return new PerfilAlumno(
                usuario.getNivelConocimiento() != null ? usuario.getNivelConocimiento().name() : "SIN_DIAGNOSTICO",
                usuario.getDificultadesDetectadas());
    }
}
