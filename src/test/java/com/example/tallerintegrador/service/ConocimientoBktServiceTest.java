package com.example.tallerintegrador.service;
import com.example.tallerintegrador.service.analitica.ConocimientoBktService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El algoritmo BKT es matemática pura (Corbett & Anderson, 1995): dado un dominio previo y
 * un acierto/fallo, calcula el dominio posterior. Se prueba sin base de datos porque no la
 * necesita — actualizarProbabilidad() no toca ningún repositorio.
 */
@ExtendWith(MockitoExtension.class)
class ConocimientoBktServiceTest {

    @Mock
    private com.example.tallerintegrador.repository.DominioConceptoAlumnoRepository repository;
    @Mock
    private com.example.tallerintegrador.repository.SemanaRepository semanaRepository;
    @Mock
    private com.example.tallerintegrador.repository.UserRepository userRepository;
    @Mock
    private com.example.tallerintegrador.repository.CursoRepository cursoRepository;

    private ConocimientoBktService bkt;

    @BeforeEach
    void setUp() {
        bkt = new ConocimientoBktService(repository, semanaRepository, userRepository, cursoRepository);
    }

    @Test
    @DisplayName("Un acierto siempre incrementa la probabilidad de dominio")
    void aciertoIncrementaDominio() {
        double previo = 0.30;
        double posterior = bkt.actualizarProbabilidad(previo, true);
        assertTrue(posterior > previo, "El dominio debe subir tras un acierto");
    }

    @Test
    @DisplayName("Un fallo siempre reduce la probabilidad de dominio (antes de sumar la transición)")
    void falloReduceDominioRespectoDelAcierto() {
        double previo = 0.50;
        double posteriorAcierto = bkt.actualizarProbabilidad(previo, true);
        double posteriorFallo = bkt.actualizarProbabilidad(previo, false);
        assertTrue(posteriorFallo < posteriorAcierto,
                "Fallar debe dejar un dominio menor que acertar, partiendo del mismo previo");
    }

    @Test
    @DisplayName("La probabilidad nunca sale del rango [0, 1]")
    void probabilidadSiempreEnRangoValido() {
        double p = 0.05;
        for (int i = 0; i < 50; i++) {
            p = bkt.actualizarProbabilidad(p, i % 3 == 0); // mezcla de aciertos y fallos
            assertTrue(p >= 0.0 && p <= 1.0, "p fuera de rango en la iteración " + i + ": " + p);
        }
    }

    @Test
    @DisplayName("Aciertos sostenidos convergen hacia un dominio alto")
    void aciertosSostenidosConvergenAAlto() {
        double p = ConocimientoBktService.P_INIT;
        for (int i = 0; i < 10; i++) {
            p = bkt.actualizarProbabilidad(p, true);
        }
        assertTrue(p > 0.85, "Tras 10 aciertos seguidos el dominio debía superar 0.85, fue " + p);
    }

    @Test
    @DisplayName("Fallos sostenidos mantienen el dominio bajo, pero nunca en cero por P_TRANSICION")
    void fallosSostenidosMantienenDominioBajo() {
        double p = ConocimientoBktService.P_INIT;
        for (int i = 0; i < 10; i++) {
            p = bkt.actualizarProbabilidad(p, false);
        }
        assertTrue(p < 0.40, "Tras 10 fallos seguidos el dominio debía mantenerse bajo, fue " + p);
        assertTrue(p > 0.0, "P_TRANSICION garantiza que el dominio nunca colapsa exactamente a 0");
    }

    @Test
    @DisplayName("Un solo acierto no basta para declarar dominio: sigue lejos del umbral DOMINADO (0.75)")
    void unSoloAciertoNoEsSuficienteParaDominioAlto() {
        double p = bkt.actualizarProbabilidad(ConocimientoBktService.P_INIT, true);
        assertTrue(p < 0.75,
                "Un acierto aislado no debe bastar para cruzar el umbral de dominio — " +
                "esto es justamente lo que el % de aciertos crudo no podía distinguir");
    }
}
