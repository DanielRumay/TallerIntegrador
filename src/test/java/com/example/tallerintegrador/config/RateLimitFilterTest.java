package com.example.tallerintegrador.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * El presupuesto es de cada alumno, no del aula.
 *
 * Antes se contaba por IP. En un colegio los veinte alumnos salen por el mismo router, asi que
 * compartian un unico presupuesto: el quinto en empezar su evaluacion recibia un 429 sin haber
 * hecho nada raro. Y los preflight CORS tambien se contaban, gastando el doble de rapido.
 */
class RateLimitFilterTest {

    private RateLimitFilter filtro;
    private FilterChain cadena;

    @BeforeEach
    void setUp() {
        filtro = new RateLimitFilter();
        ReflectionTestUtils.setField(filtro, "maxRequestsPerMinute", 2);
        cadena = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    private void sesionDe(String correo) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(correo, null,
                        Collections.singletonList(new SimpleGrantedAuthority("STUDENT"))));
    }

    private int pedir(String metodo, String ruta, String ip) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(metodo, ruta);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filtro.doFilter(req, res, cadena);
        return res.getStatus();
    }

    @Test
    @DisplayName("Dos alumnos tras la MISMA IP no se gastan el presupuesto el uno al otro")
    void elAulaNoCompartePresupuesto() throws Exception {
        sesionDe("ana@colegio.edu.pe");
        assertThat(pedir("GET", "/adaptive/evaluacion", "190.1.1.1")).isEqualTo(200);
        assertThat(pedir("GET", "/adaptive/evaluacion", "190.1.1.1")).isEqualTo(200);
        assertThat(pedir("GET", "/adaptive/evaluacion", "190.1.1.1")).isEqualTo(429); // Ana agoto el suyo

        // Luis, desde el MISMO router del colegio, debe poder trabajar igual.
        sesionDe("luis@colegio.edu.pe");
        assertThat(pedir("GET", "/adaptive/evaluacion", "190.1.1.1")).isEqualTo(200);
    }

    @Test
    @DisplayName("Los preflight OPTIONS no gastan presupuesto: son protocolo, no trabajo")
    void elPreflightNoCuenta() throws Exception {
        sesionDe("ana@colegio.edu.pe");
        for (int i = 0; i < 10; i++) {
            assertThat(pedir("OPTIONS", "/adaptive/evaluacion", "190.1.1.1")).isEqualTo(200);
        }
        // Tras diez preflights su presupuesto sigue intacto.
        assertThat(pedir("GET", "/adaptive/evaluacion", "190.1.1.1")).isEqualTo(200);
    }

    @Test
    @DisplayName("Las rutas que no son de IA no consumen presupuesto")
    void soloSeLimitaLaIA() throws Exception {
        sesionDe("ana@colegio.edu.pe");
        for (int i = 0; i < 10; i++) {
            assertThat(pedir("GET", "/cursos/estudiante/7", "190.1.1.1")).isEqualTo(200);
        }
        assertThat(pedir("GET", "/adaptive/evaluacion", "190.1.1.1")).isEqualTo(200);
    }
}
