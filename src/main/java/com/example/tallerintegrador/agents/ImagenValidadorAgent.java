package com.example.tallerintegrador.agents;

import com.example.tallerintegrador.entidades.postgres.EventoMetricaIA;
import com.example.tallerintegrador.entidades.postgres.TipoEventoIA;
import com.example.tallerintegrador.service.ia.GeminiService;
import com.example.tallerintegrador.service.metricas.TelemetriaIAService;
import com.example.tallerintegrador.service.util.JsonParsingUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Base64;

/**
 * ImagenValidadorAgent — patrón generador-crítico (generate-then-verify) para las imágenes
 * pedagógicas.
 *
 * generarImagenConImagen3() solo comprueba que la API de Gemini devolvió ALGÚN byte de
 * imagen; nunca comprueba que esa imagen contenga lo que el enunciado dice que contiene.
 * El síntoma reportado —preguntas tipo "observa el diagrama y responde según lo indicado"
 * sobre una imagen genérica o sin relación— viene de ahí: el generador de imágenes y el
 * generador de preguntas son independientes y nadie cierra el lazo entre ambos.
 *
 * Este agente cierra ese lazo con una segunda llamada multimodal, más barata que regenerar
 * a ciegas: le muestra al modelo la imagen ya generada junto con el enunciado que la
 * referencia y le pide un veredicto binario y explicado. Es el mismo principio que ya se
 * aplica al reactivo textual con el AgentJudgeAgent, aplicado ahora a la imagen.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImagenValidadorAgent {

    private final GeminiService geminiService;
    private final TelemetriaIAService telemetriaIAService;
    private final ObjectMapper mapper = new ObjectMapper();

    public record Veredicto(boolean valida, String motivo) {
        static Veredicto valida(String motivo) { return new Veredicto(true, motivo); }
        static Veredicto invalida(String motivo) { return new Veredicto(false, motivo); }
    }

    /**
     * @param promptImagenSolicitado la descripción que se le pidió al generador de imágenes
     * @param enunciadoPregunta      el enunciado que hace referencia a la imagen ante el alumno
     * @param base64Imagen           la imagen ya generada, en base64
     */
    public Veredicto validar(String promptImagenSolicitado, String enunciadoPregunta, String base64Imagen, Long usuarioId) {
        if (base64Imagen == null || base64Imagen.isBlank()) {
            registrar(usuarioId, "SIN_IMAGEN");
            return Veredicto.invalida("No se generó ninguna imagen para validar.");
        }

        String prompt = """
                Actúa como revisor de material educativo. Se te muestra una imagen generada por IA
                y el contexto pedagógico en el que se usará.

                DESCRIPCIÓN SOLICITADA AL GENERADOR DE IMÁGENES:
                "%s"

                ENUNCIADO QUE EL ALUMNO LEERÁ JUNTO A ESTA IMAGEN:
                "%s"

                Evalúa ÚNICAMENTE tres cosas:
                1. Si la imagen contiene, de forma reconocible, los elementos visuales que el enunciado
                   exige observar para responder (por ejemplo: si el enunciado dice "la flecha marcada
                   con un signo de interrogación", esa flecha debe estar presente y ser identificable).
                2. Si la imagen NO contiene texto en inglés visible ni texto ilegible o corrupto.
                3. Si la imagen NO REVELA LA RESPUESTA. Es inválida cuando muestra la situación ya
                   resuelta junto a la original: composiciones 'antes y después', dípticos
                   comparativos, paneles divididos, versiones 'correcta vs incorrecta', o
                   etiquetas del tipo 'ANTES'/'DESPUÉS'. Si el enunciado pregunta qué debería
                   cambiar o cómo se corregiría, dibujar el resultado corregido convierte la
                   pregunta en un ejercicio de describir el segundo panel, y deja de medir si el
                   alumno entendió el concepto.

                No evalúes el estilo artístico ni la composición. Si la imagen es genérica, borrosa,
                irrelevante al enunciado, o si el enunciado exige un elemento que no aparece, marca
                la imagen como inválida.

                Responde ÚNICAMENTE con JSON, sin markdown:
                {"valida": boolean, "motivo": "una oración explicando qué elemento falta o confirma"}
                """.formatted(promptImagenSolicitado, enunciadoPregunta);

        try {
            byte[] bytes = Base64.getDecoder().decode(base64Imagen);
            var respuesta = geminiService.askGeminiConImagen(prompt, bytes, "image/png");
            String limpio = JsonParsingUtils.cleanJsonString(respuesta.text());
            JsonNode root = mapper.readTree(limpio);

            boolean valida = root.path("valida").asBoolean(true);
            String motivo = root.path("motivo").asText("Sin motivo especificado.");

            registrar(usuarioId, valida ? "VALIDA" : "INVALIDA");
            return new Veredicto(valida, motivo);
        } catch (Exception e) {
            log.warn("[ImagenValidador] No se pudo verificar la imagen, se acepta por defecto: {}", e.getMessage());
            registrar(usuarioId, "ERROR_VALIDACION");
            // Fallar abierto: un error del validador no debe bloquear la entrega del reactivo,
            // pero queda registrado para no confundir "validado" con "no se pudo validar".
            return Veredicto.valida("No se pudo ejecutar la validación automática; aceptada por defecto.");
        }
    }

    private void registrar(Long usuarioId, String etiqueta) {
        EventoMetricaIA evento = EventoMetricaIA.de(TipoEventoIA.VALIDACION_IMAGEN, etiqueta);
        evento.setUsuarioId(usuarioId);
        telemetriaIAService.registrar(evento);
    }
}
