package com.example.tallerintegrador.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * El token solo vale en la cabecera Authorization.
 *
 * POR QUE EXISTE ESTA PRUEBA. El filtro aceptaba el token tambien como parametro de consulta
 * (?token=...), para que EventSource pudiera autenticarse. El efecto secundario era que
 * cualquier endpoint quedaba accesible por la barra de direcciones, y el token terminaba en el
 * historial del navegador y en los registros de acceso. Si alguien reintroduce ese respaldo,
 * esta prueba falla.
 */
class JwtFilterTest {

    private static final String SECRETO = "una-clave-de-prueba-suficientemente-larga-para-hmac-sha256";

    private JwtFilter filtro;
    private FilterChain cadena;

    @BeforeEach
    void setUp() {
        filtro = new JwtFilter();
        ReflectionTestUtils.setField(filtro, "secretKey", SECRETO);
        cadena = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private String tokenValido() {
        return Jwts.builder()
                .setSubject("alumno@colegio.edu.pe")
                .claim("rol", "ESTUDIANTE")
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRETO.getBytes()))
                .compact();
    }

    @Test
    @DisplayName("Un token en la cabecera Authorization autentica")
    void cabeceraAutentica() throws Exception {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.addHeader("Authorization", "Bearer " + tokenValido());

        filtro.doFilter(peticion, new MockHttpServletResponse(), cadena);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("alumno@colegio.edu.pe");
    }

    @Test
    @DisplayName("El mismo token en la URL NO autentica")
    void parametroDeConsultaNoAutentica() throws Exception {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.setParameter("token", tokenValido());

        filtro.doFilter(peticion, new MockHttpServletResponse(), cadena);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Sin token no se autentica y la peticion sigue su curso")
    void sinTokenNoAutentica() throws Exception {
        filtro.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), cadena);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
