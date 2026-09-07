package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.Curso;
import com.example.tallerintegrador.entidades.postgres.DominioConceptoAlumno;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.CursoRepository;
import com.example.tallerintegrador.repository.DominioConceptoAlumnoRepository;
import com.example.tallerintegrador.service.util.NormalizadorConcepto;
import com.example.tallerintegrador.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Bayesian Knowledge Tracing (Corbett & Anderson, 1995) aplicado a la relación
 * alumno-concepto-nivelBloom.
 *
 * El mapa de calor original agregaba "% de aciertos" por curso/semana: un 60% sobre 5
 * preguntas se pintaba igual que un 60% sobre 40, y un acierto aislado en un concepto por
 * lo demás no dominado subía la media tanto como diez aciertos consistentes. BKT no
 * promedia: mantiene una probabilidad de dominio por concepto que se actualiza con cada
 * observación, penalizando el "guess" (acertar sin saber) y el "slip" (fallar sabiendo).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConocimientoBktService {

    private final DominioConceptoAlumnoRepository repository;
    private final com.example.tallerintegrador.repository.SemanaRepository semanaRepository;
    private final UserRepository userRepository;
    private final CursoRepository cursoRepository;

    /** Probabilidad de dominio antes de la primera observación de este concepto. */
    public static final double P_INIT = 0.30;
    /** Probabilidad de aprender el concepto entre una pregunta y la siguiente. */
    public static final double P_TRANSICION = 0.15;
    /** Probabilidad de fallar una pregunta pese a dominar el concepto (descuido). */
    public static final double P_DESLIZ = 0.10;
    /** Probabilidad de acertar una pregunta sin dominar el concepto (adivinar). */
    public static final double P_ADIVINANZA = 0.20;

    /**
     * Aplica un paso de BKT: dado el dominio previo y si la respuesta fue correcta,
     * calcula el dominio posterior. Pura función matemática, sin efectos de lado — se
     * puede probar sin base de datos ni mocks.
     */
    public double actualizarProbabilidad(double dominioPrevio, boolean acierto) {
        double posteriorCondicionalAObservacion;
        if (acierto) {
            double pAciertoSiDomina = 1 - P_DESLIZ;
            double pAciertoSiNoDomina = P_ADIVINANZA;
            double numerador = dominioPrevio * pAciertoSiDomina;
            double denominador = numerador + (1 - dominioPrevio) * pAciertoSiNoDomina;
            posteriorCondicionalAObservacion = denominador == 0 ? dominioPrevio : numerador / denominador;
        } else {
            double pFalloSiDomina = P_DESLIZ;
            double pFalloSiNoDomina = 1 - P_ADIVINANZA;
            double numerador = dominioPrevio * pFalloSiDomina;
            double denominador = numerador + (1 - dominioPrevio) * pFalloSiNoDomina;
            posteriorCondicionalAObservacion = denominador == 0 ? dominioPrevio : numerador / denominador;
        }
        // El alumno pudo aprender entre esta pregunta y la anterior, independientemente
        // de si acertó o no (P_TRANSICION es la probabilidad de ese salto).
        return posteriorCondicionalAObservacion + (1 - posteriorCondicionalAObservacion) * P_TRANSICION;
    }

    /**
     * @param concepto nombre en texto libre tal como lo devolvió el modelo. Se canoniza aquí
     *                 dentro; quien llama no tiene que saber de normalizacion.
     */
    @Transactional
    public void actualizar(Long usuarioId, Long cursoId, String concepto, String nivelBloom, boolean acierto) {
        actualizar(usuarioId, cursoId, null, concepto, nivelBloom, acierto);
    }

    /** @param semanaId semana en que se practico; solo sirve para situar el concepto en el mapa. */
    @Transactional
    public void actualizar(Long usuarioId, Long cursoId, Long semanaId, String concepto,
                           String nivelBloom, boolean acierto) {
        try {
            // Sin esto, "Fotosintesis", "La fotosintesis" y "fotosintesis" abrian tres filas
            // distintas y la evidencia del alumno quedaba repartida en tres trozos, ninguno
            // de los cuales llegaba al umbral de dominio.
            String claveConcepto = NormalizadorConcepto.canonizar(concepto);
            if (claveConcepto.isEmpty()) {
                log.debug("[BKT] Concepto vacio o sin contenido util ('{}'), se omite", concepto);
                return;
            }

            DominioConceptoAlumno registro = repository
                    .findByUsuarioIdAndConceptoAndNivelBloom(usuarioId, claveConcepto, nivelBloom)
                    .orElseGet(() -> nuevoRegistro(usuarioId, cursoId, claveConcepto, nivelBloom));

            // La etiqueta se fija con la primera forma legible que se vea y no se
            // sobrescribe despues: si cada intento la reescribiera, el nombre mostrado
            // bailaria entre "Fotosintesis" y "FOTOSINTESIS" segun lo que devolviera el
            // modelo en esa llamada.
            if (registro.getConceptoEtiqueta() == null || registro.getConceptoEtiqueta().isBlank()) {
                registro.setConceptoEtiqueta(NormalizadorConcepto.paraMostrar(concepto));
            }

            // Un mismo concepto no debería cambiar de curso entre una llamada y otra, pero
            // si el registro se creó antes de que existiera esta columna, se completa aquí.
            if (registro.getCurso() == null && cursoId != null) {
                cursoRepository.findById(cursoId).ifPresent(registro::setCurso);
            }

            // Se sobrescribe siempre: interesa DONDE se practico por ultima vez, para que el
            // concepto aparezca en la columna correcta del mapa temporal.
            if (semanaId != null) {
                semanaRepository.findById(semanaId).ifPresent(registro::setSemana);
            }

            double nuevaProbabilidad = actualizarProbabilidad(registro.getProbabilidadDominio(), acierto);
            registro.setProbabilidadDominio(nuevaProbabilidad);
            registro.setObservaciones(registro.getObservaciones() + 1);
            registro.setFechaActualizacion(LocalDateTime.now());
            repository.save(registro);
        } catch (Exception e) {
            // El dominio bayesiano es analítica, no el registro académico: un fallo aquí
            // nunca debe impedir que se guarde el intento del alumno.
            log.warn("[BKT] No se pudo actualizar dominio para usuario={}, concepto='{}': {}",
                    usuarioId, concepto, e.getMessage());
        }
    }

    private DominioConceptoAlumno nuevoRegistro(Long usuarioId, Long cursoId, String concepto, String nivelBloom) {
        Usuario usuario = userRepository.findById(usuarioId)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado: " + usuarioId));
        DominioConceptoAlumno registro = new DominioConceptoAlumno();
        registro.setUsuario(usuario);
        if (cursoId != null) {
            cursoRepository.findById(cursoId).ifPresent(registro::setCurso);
        }
        registro.setConcepto(concepto);
        registro.setNivelBloom(nivelBloom);
        registro.setProbabilidadDominio(P_INIT);
        registro.setObservaciones(0);
        return registro;
    }
}
