package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.agents.AgentJudgeAgent;
import com.example.tallerintegrador.entidades.postgres.RondaCustodiada;
import com.example.tallerintegrador.repository.MaterialRepository;
import com.example.tallerintegrador.repository.MatriculaRepository;
import com.example.tallerintegrador.repository.RondaCustodiadaRepository;
import com.example.tallerintegrador.repository.SemanaRepository;
import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.service.academico.IntentoService;
import com.example.tallerintegrador.service.ia.AudienciaService;
import com.example.tallerintegrador.service.util.IdHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Lo que un alumno podría intentar forzar desde el navegador. */
class CustodiaRondaServiceTest {

    private static final Long ALUMNO = 7L;
    private final Map<String, RondaCustodiada> tabla = new HashMap<>();
    private RondaCustodiadaRepository rondas;
    private AgentJudgeAgent juez;
    private PoderesService poderes;
    private IntentoService intentos;
    private IdHasher idHasher;
    private CustodiaRondaService custodia;
    private final CustodiaRondaService.AccesoAlumno acceso = new CustodiaRondaService.AccesoAlumno(ALUMNO, 3L, 0);

    @BeforeEach
    void preparar() {
        rondas = mock(RondaCustodiadaRepository.class);
        juez = mock(AgentJudgeAgent.class);
        poderes = mock(PoderesService.class);
        intentos = mock(IntentoService.class);
        idHasher = mock(IdHasher.class);
        when(idHasher.encode(any())).thenReturn("hash");
        when(rondas.save(any())).thenAnswer(i -> {
            RondaCustodiada r = i.getArgument(0);
            tabla.put(r.getId(), r);
            return r;
        });
        when(rondas.findById(anyString())).thenAnswer(i -> Optional.ofNullable(tabla.get(i.<String>getArgument(0))));
        when(rondas.bloquear(anyString())).thenAnswer(i -> Optional.ofNullable(tabla.get(i.<String>getArgument(0))));
        when(juez.evaluarRespuestaUnitaria(any(), any(), any(), anyInt(), any(), any())).thenAnswer(i -> Map.of(
                "evaluacion", new HashMap<>(Map.of("esCorrecta", false, "puntaje", 0.0, "explicacion", "del juez"))));
        when(poderes.usar(any(), any(), any(), any())).thenReturn(Map.of("saldo", 50, "precio", 20, "restantes", 2));
        custodia = new CustodiaRondaService(rondas, mock(UserRepository.class), mock(MaterialRepository.class),
                mock(MatriculaRepository.class), mock(SemanaRepository.class), juez, poderes, intentos,
                mock(AudienciaService.class), idHasher, mock(PlatformTransactionManager.class));
    }

    private static Map<String, Object> pregunta(String enunciado) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("enunciado", enunciado);
        p.put("opciones_o_respuesta", List.of("A) uno", "B) dos", "C) tres", "D) cuatro"));
        p.put("respuesta_correcta", "B) dos");
        p.put("justificacion_pregunta", "porque sí");
        p.put("competencia_cneb", "Lee");
        return p;
    }

    private Map<String, Object> resultado(int n) {
        List<Object> lista = new ArrayList<>();
        for (int i = 0; i < n; i++) lista.add(pregunta("Pregunta " + i));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("preguntas", lista);
        r.put("metricas_objetivas", Map.of("x", 1));
        return r;
    }

    private String abrir(Map<String, Object> salida) {
        return (String) salida.get("ronda_id");
    }

    @Test
    void noSaleNingunaRespuestaHaciaElAlumno() throws Exception {
        Map<String, Object> salida = custodia.protegerGenerada(acceso, "OPCION_MULTIPLE", resultado(5));
        String json = new ObjectMapper().writeValueAsString(salida);
        assertFalse(json.contains("respuesta_correcta"));
        assertFalse(json.contains("justificacion"));
        assertFalse(json.contains("porque sí"));
        assertFalse(json.contains("metricas"));
        assertTrue(json.contains("A) uno"));
    }

    @Test
    void laRubricaDeUnaAbiertaNoSeEnvia() throws Exception {
        Map<String, Object> r = resultado(5);
        @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) ((List<?>) r.get("preguntas")).get(0);
        p.put("opciones_o_respuesta", List.of("Rubrica: criterio secreto"));
        String json = new ObjectMapper().writeValueAsString(custodia.protegerGenerada(acceso, "ABIERTA", r));
        assertFalse(json.contains("criterio secreto"));
    }

    @Test
    void lasAlternativasDelBancoEstaticoPierdenLaMarca() throws Exception {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", 1);
        p.put("enunciado", "E");
        p.put("tipo_pregunta", "Opcion_Multiple");
        p.put("opciones", List.of(Map.of("texto", "a", "esCorrecta", true), Map.of("texto", "b", "esCorrecta", false)));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tipo_evaluacion", "FORMATIVA");
        r.put("preguntas_json", Map.of("preguntas", List.of(p)));
        String json = new ObjectMapper().writeValueAsString(custodia.protegerAdaptativa(ALUMNO, 3L, r));
        assertFalse(json.contains("esCorrecta"));
        assertTrue(json.contains("\"opciones_o_respuesta\":[\"a\",\"b\"]"));
    }

    @Test
    void laPreguntaCerradaLaDecideLaComparacionNoElModelo() {
        String id = abrir(custodia.protegerGenerada(acceso, "OPCION_MULTIPLE", resultado(5)));
        Map<String, Object> r = custodia.responder(ALUMNO, id, 0, "B) dos");
        Map<?, ?> ev = (Map<?, ?>) r.get("evaluacion");
        assertEquals(true, ev.get("esCorrecta"));
        assertEquals(4.0, ev.get("puntaje"));
        assertEquals("porque sí", ev.get("explicacion"));
        assertEquals("B) dos", ((Map<?, ?>) r.get("revelado")).get("respuesta_correcta"));
    }

    @Test
    void noSePuedeCambiarUnaRespuestaYaCalificada() {
        String id = abrir(custodia.protegerGenerada(acceso, "OPCION_MULTIPLE", resultado(5)));
        custodia.responder(ALUMNO, id, 0, "A) uno");
        Map<String, Object> segunda = custodia.responder(ALUMNO, id, 0, "B) dos");
        assertEquals(false, ((Map<?, ?>) segunda.get("evaluacion")).get("esCorrecta"));
        verify(juez, times(1)).evaluarRespuestaUnitaria(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void unaRondaAjenaNoExiste() {
        String id = abrir(custodia.protegerGenerada(acceso, "OPCION_MULTIPLE", resultado(5)));
        assertThrows(NoSuchElementException.class, () -> custodia.responder(99L, id, 0, "B) dos"));
    }

    @Test
    void laAyudaBajaElPuntajeEnElServidor() {
        String id = abrir(custodia.protegerGenerada(acceso, "OPCION_MULTIPLE", resultado(5)));
        custodia.comodin(ALUMNO, id, 0, PoderesService.Poder.PISTA);
        Map<?, ?> ev = (Map<?, ?>) custodia.responder(ALUMNO, id, 0, "B) dos").get("evaluacion");
        assertEquals(2.0, ev.get("puntaje"));
    }

    @Test
    void elCincuentaNuncaOcultaLaCorrectaNiSeRepite() {
        String id = abrir(custodia.protegerGenerada(acceso, "OPCION_MULTIPLE", resultado(5)));
        List<?> ocultas = (List<?>) custodia.comodin(ALUMNO, id, 0, PoderesService.Poder.MITAD).get("ocultas");
        assertEquals(2, ocultas.size());
        assertFalse(ocultas.contains("B) dos"));
        assertThrows(IllegalStateException.class, () -> custodia.comodin(ALUMNO, id, 0, PoderesService.Poder.MITAD));
        verify(poderes, times(1)).usar(any(), any(), any(), any());
    }

    @Test
    void noHayComodinesSobreUnaPreguntaYaRespondida() {
        String id = abrir(custodia.protegerGenerada(acceso, "OPCION_MULTIPLE", resultado(5)));
        custodia.responder(ALUMNO, id, 0, "A) uno");
        assertThrows(IllegalStateException.class, () -> custodia.comodin(ALUMNO, id, 0, PoderesService.Poder.PISTA));
        assertThrows(IllegalStateException.class, () -> custodia.comodin(ALUMNO, id, 0, PoderesService.Poder.MITAD));
    }

    @Test
    void siFallaElCobroElCincuentaQuedaLibre() {
        String id = abrir(custodia.protegerGenerada(acceso, "OPCION_MULTIPLE", resultado(5)));
        when(poderes.usar(any(), any(), any(), any())).thenThrow(new IllegalStateException("sin monedas"));
        assertThrows(IllegalStateException.class, () -> custodia.comodin(ALUMNO, id, 0, PoderesService.Poder.MITAD));
        doReturn(Map.of("saldo", 1)).when(poderes).usar(any(), any(), any(), any());
        assertNotNull(custodia.comodin(ALUMNO, id, 0, PoderesService.Poder.MITAD).get("ocultas"));
    }

    @Test
    void noSePuedeFinalizarConPreguntasPendientes() {
        String id = abrir(custodia.protegerGenerada(acceso, "OPCION_MULTIPLE", resultado(5)));
        custodia.responder(ALUMNO, id, 0, "B) dos");
        assertThrows(IllegalStateException.class, () -> custodia.finalizar(ALUMNO, id, false));
        verifyNoInteractions(intentos);
    }

    @Test
    void laNotaLaCalculaElServidorYSeGuardaUnaSolaVez() {
        String id = abrir(custodia.protegerGenerada(acceso, "OPCION_MULTIPLE", resultado(5)));
        for (int i = 0; i < 5; i++) custodia.responder(ALUMNO, id, i, i < 3 ? "B) dos" : "A) uno");
        assertEquals(12.0, custodia.finalizar(ALUMNO, id, false).get("notaFinal"));
        assertEquals(12.0, custodia.finalizar(ALUMNO, id, false).get("notaFinal"));
        var cap = ArgumentCaptor.forClass(com.example.tallerintegrador.DTO.GuardarIntentoRequest.class);
        verify(intentos, times(1)).guardarIntentoCompleto(cap.capture(), any());
        assertEquals(12.0, cap.getValue().notaFinal());
        assertEquals(5, cap.getValue().respuestas().size());
    }

    @Test
    void elBonoCuentaSoloLoQueLlegoATiempo() {
        String id = abrir(custodia.protegerGenerada(acceso, "OPCION_MULTIPLE", resultado(5)));
        custodia.responder(ALUMNO, id, 0, "B) dos");
        // La segunda pregunta tarda diez minutos: no es rápida y no debe contar.
        tabla.get(id).setUltimaMarca(LocalDateTime.now().minusMinutes(10));
        for (int i = 1; i < 5; i++) {
            custodia.responder(ALUMNO, id, i, "B) dos");
            if (i < 4) tabla.get(id).setUltimaMarca(LocalDateTime.now());
        }
        custodia.finalizar(ALUMNO, id, true);
        verify(poderes).cobrarBono(eq(ALUMNO), eq(id), eq(4), eq(5));
    }

    @Test
    void siLaIaFallaEnUnaAbiertaSePuedeReintentar() {
        when(juez.evaluarRespuestaUnitaria(any(), any(), any(), anyInt(), any(), any())).thenReturn(
                Map.of("evaluacion", Map.of("fallo", true, "esCorrecta", false, "puntaje", 0.0, "explicacion", "reintenta")));
        String id = abrir(custodia.protegerGenerada(acceso, "ABIERTA", resultado(5)));
        assertNull(custodia.responder(ALUMNO, id, 0, "mi texto").get("revelado"));
        when(juez.evaluarRespuestaUnitaria(any(), any(), any(), anyInt(), any(), any())).thenReturn(
                Map.of("evaluacion", Map.of("esCorrecta", true, "puntaje", 3.0, "explicacion", "bien")));
        assertEquals(3.0, ((Map<?, ?>) custodia.responder(ALUMNO, id, 0, "mi texto").get("evaluacion")).get("puntaje"));
    }

    @Test
    void elTutorMideSobreElTotalYNoAdmiteUnaSolaPregunta() {
        var ronda = custodia.registrarPreguntaTutor(acceso, null, 1, "P1");
        String id = (String) ronda.get("ronda_id");
        assertEquals(5, tabla.get(id).getTotalTurnos());
        custodia.registrarVeredicto(id, 0, "respuesta", new com.example.tallerintegrador.agents.TutorConversacionalAgent.VeredictoTurno(true, 4, "AVANZAR"));
        assertEquals(4.0, custodia.finalizar(ALUMNO, id, false).get("notaFinal"));
    }

    @Test
    void elEscalonDelTutorLoLlevaElServidor() {
        String id = (String) custodia.registrarPreguntaTutor(acceso, null, 5, "P1").get("ronda_id");
        var turnoVeredicto = new com.example.tallerintegrador.agents.TutorConversacionalAgent.VeredictoTurno(false, null, "REPREGUNTA");
        assertEquals(1, custodia.turnoTutor(ALUMNO, id, 0).escalon());
        custodia.registrarVeredicto(id, 0, "x", turnoVeredicto);
        assertEquals(2, custodia.turnoTutor(ALUMNO, id, 0).escalon());
    }

    @Test
    void laEvaluacionAdaptativaSeCierraUnaVezYCorrigeElServidor() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tipo_evaluacion", "FORMATIVA");
        r.put("tipo_pregunta", "OPCION_MULTIPLE");
        r.put("preguntas_json", Map.of("preguntas", List.of(pregunta("E"))));
        String id = abrir(custodia.protegerAdaptativa(ALUMNO, 3L, r));
        var enviadas = List.of(new CustodiaRondaService.RespuestaEnviada("B) dos"));
        var cerrada = custodia.cerrarAdaptativa(ALUMNO, id, RondaCustodiada.Clase.ADAPTATIVA, enviadas);
        assertTrue(cerrada.respuestas().get(0).esCorrecta());
        assertThrows(IllegalStateException.class,
                () -> custodia.cerrarAdaptativa(ALUMNO, id, RondaCustodiada.Clase.ADAPTATIVA, enviadas));
    }

    @Test
    void unaRondaAdaptativaNoSirveDeOraculo() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tipo_evaluacion", "UBICACION");
        r.put("preguntas_json", Map.of("preguntas", List.of(pregunta("E"))));
        String id = abrir(custodia.protegerAdaptativa(ALUMNO, 3L, r));
        assertThrows(IllegalArgumentException.class, () -> custodia.responder(ALUMNO, id, 0, "B) dos"));
    }

    @Test
    void laCantidadQuedaEntreCincoYDiez() {
        assertEquals(5, CustodiaRondaService.cantidadPermitida(1));
        assertEquals(10, CustodiaRondaService.cantidadPermitida(500));
        assertEquals(7, CustodiaRondaService.cantidadPermitida(7));
    }

    @Test
    void elTiempoCoincideConElRelojDelNavegador() {
        Map<String, Object> p = new HashMap<>();
        p.put("enunciado", "uno dos tres cuatro cinco");
        p.put("opciones_o_respuesta", List.of("a", "b", "c", "d"));
        assertEquals(15, CustodiaRondaService.segundosPara(p, "OPCION_MULTIPLE", "Recordar"));
        assertEquals(20, CustodiaRondaService.segundosPara(p, "OPCION_MULTIPLE", "Analizar"));
        p.put("opciones_o_respuesta", List.of("Rubrica: x"));
        assertEquals(55, CustodiaRondaService.segundosPara(p, "ABIERTA", "Recordar"));
    }

    @Test
    void reconoceLaAlternativaPorTextoLetraOSinPrefijo() {
        List<String> op = List.of("A) Lima", "B) Cusco");
        assertEquals(1, CustodiaRondaService.indiceDe(op, "B) Cusco"));
        assertEquals(1, CustodiaRondaService.indiceDe(op, "cusco"));
        assertEquals(1, CustodiaRondaService.indiceDe(op, "B"));
        assertEquals(-1, CustodiaRondaService.indiceDe(op, "Arequipa"));
    }
}
