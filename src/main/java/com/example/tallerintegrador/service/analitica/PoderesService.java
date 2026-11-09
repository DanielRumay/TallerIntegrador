package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.Intento;
import com.example.tallerintegrador.repository.IntentoRepository;
import com.example.tallerintegrador.repository.MovimientoMonedasRepository;
import com.example.tallerintegrador.service.ia.GeminiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Poderes del modo contrarreloj y bono de la partida.
 *
 * Un "poder" se compra con monedas DURANTE una práctica (una "partida", que es el id de la ronda). Todos los límites se aplican aquí y no en la interfaz,
 * porque la interfaz se puede manipular:
 *  - máximo MAX_PODERES_POR_PARTIDA por partida;
 *  - la pista de Aria sube de precio dentro de la misma partida (10, 20, 40).
 *
 * Por qué limitar las pistas: en los tutores inteligentes, pedir ayuda una y otra vez sin leer
 * ("hint abuse") es la forma más común de "jugar con el sistema" y no aprender (Baker y otros;
 * en un tutor de geometría llegó al 36 % de las acciones). Un precio creciente y un tope la
 * desincentivan sin prohibirla.
 *
 * La pista NUNCA revela la respuesta: se le pide así a la IA y, además, se comprueba aquí que
 * el texto no la contenga; si la contiene, se usa una pista genérica.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PoderesService {

    private final MonedasService monedasService;
    private final MovimientoMonedasRepository movimientoRepository;
    private final IntentoRepository intentoRepository;
    private final GeminiService geminiService;
    private final com.example.tallerintegrador.service.metricas.TelemetriaIAService telemetria;

    public enum Poder { TIEMPO, CONGELAR, MITAD, PISTA }

    static final int MAX_PODERES_POR_PARTIDA = 3;
    static final int[] PRECIO_PISTA = {10, 20, 40};
    static final Map<Poder, Integer> PRECIO = Map.of(Poder.TIEMPO, 10, Poder.CONGELAR, 15, Poder.MITAD, 20);

    /** Bono de la partida: solo si hay un intento guardado hace poco, y a lo más 5 al día. */
    static final int MINUTOS_PARA_COBRAR = 15;
    static final int PARTIDAS_CON_BONO_POR_DIA = 5;
    static final int BONO_MAXIMO = 10;

    private static final Pattern ID_PARTIDA = Pattern.compile("[A-Za-z0-9-]{8,40}");

    public record Pregunta(String enunciado, List<String> opciones, String respuestaCorrecta, String concepto) {}

    /** Precio del siguiente uso de este poder en la partida (para mostrarlo antes de comprar). */
    public int precioDe(Long usuarioId, String partidaId, Poder poder) {
        validarPartida(partidaId);
        if (poder != Poder.PISTA) return PRECIO.get(poder);
        long pistas = movimientoRepository.findByUsuarioIdAndClaveStartingWith(usuarioId, clavePartida(partidaId)).stream()
                .filter(m -> m.getMotivo().startsWith("Pista de Aria")).count();
        return PRECIO_PISTA[(int) Math.min(pistas, PRECIO_PISTA.length - 1)];
    }

    /**
     * Cobra el poder y, si es una pista, la genera. La pista se genera ANTES de cobrar: si la IA
     * falla, se usa la genérica y el alumno igual recibe ayuda por lo que pagó.
     */
    public Map<String, Object> usar(Long usuarioId, String partidaId, Poder poder, Pregunta pregunta) {
        validarPartida(partidaId);
        int usados = movimientoRepository.findByUsuarioIdAndClaveStartingWith(usuarioId, clavePartida(partidaId)).size();
        if (usados >= MAX_PODERES_POR_PARTIDA) {
            throw new IllegalStateException("Ya usaste los " + MAX_PODERES_POR_PARTIDA + " comodines de esta práctica.");
        }
        int precio = precioDe(usuarioId, partidaId, poder);
        if (monedasService.saldo(usuarioId) < precio) {
            throw new IllegalStateException("No te alcanzan las monedas: necesitas " + precio + ".");
        }

        String pista = poder == Poder.PISTA ? generarPista(pregunta) : null;
        String motivo = switch (poder) {
            case TIEMPO -> "Déjame pensar (+15 s)";
            case CONGELAR -> "Dilo de otra forma";
            case MITAD -> "Déjame dos ideas";
            case PISTA -> "Pista de Aria";
        };
        int saldo = monedasService.gastar(usuarioId, precio, motivo, clavePartida(partidaId) + (usados + 1));

        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("saldo", saldo);
        salida.put("precio", precio);
        salida.put("restantes", MAX_PODERES_POR_PARTIDA - usados - 1);
        if (pista != null) salida.put("pista", pista);
        return salida;
    }

    String generarPista(Pregunta p) {
        String generica = pistaGenerica(p);
        if (p == null || p.enunciado() == null || p.enunciado().isBlank()) return generica;
        String prompt = """
                Eres Aria, una gatita tutora de un colegio peruano. Un estudiante de secundaria te pide una pista.
                Pregunta: %s
                Alternativas: %s
                Respuesta correcta (SOLO para ti, NO la digas): %s
                Escribe UNA pista breve, de máximo 25 palabras, en español, con tono cálido y de tú.
                La pista orienta a pensar (qué fijarse, qué descartar, qué recordar) pero NO dice la respuesta,
                ni la copia, ni la parafrasea. Responde solo con el texto de la pista, sin comillas.
                """.formatted(p.enunciado(), p.opciones() == null ? "(pregunta abierta)" : String.join(" | ", p.opciones()),
                Objects.toString(p.respuestaCorrecta(), ""));
        try {
            long inicio = System.currentTimeMillis();
            var generada = geminiService.askGemini(prompt);
            telemetria.registrarFraseAria("PISTA", generada, System.currentTimeMillis() - inicio);
            String texto = generada.text();
            if (texto == null) return generica;
            texto = texto.trim().replaceAll("^[\"«]|[\"»]$", "");
            // El simulador local devuelve JSON de preguntas; y una pista larga ya no es "breve".
            if (texto.isBlank() || texto.startsWith("{") || texto.startsWith("[") || texto.split("\\s+").length > 45) return generica;
            if (revelaLaRespuesta(texto, p.respuestaCorrecta())) {
                log.warn("[PISTA] La IA reveló la respuesta; se usa la pista genérica.");
                return generica;
            }
            return texto;
        } catch (Exception e) {
            log.warn("[PISTA] No se pudo generar con la IA: {}", e.getMessage());
            return generica;
        }
    }

    static String pistaGenerica(Pregunta p) {
        String concepto = p == null || p.concepto() == null || p.concepto().isBlank() ? null : p.concepto().trim();
        return concepto != null
                ? "Piensa en lo que sabes sobre " + concepto + ". Relee la pregunta y descarta primero lo que claramente no encaja."
                : "Relee la pregunta despacio y subraya la palabra clave. Luego descarta las opciones que contradicen el texto.";
    }

    /** ¿La pista contiene la respuesta correcta? Sin tildes ni mayúsculas, y sin la letra "A)" del inicio. */
    static boolean revelaLaRespuesta(String pista, String correcta) {
        if (correcta == null || correcta.isBlank()) return false;
        String limpia = normalizar(correcta.replaceFirst("^\\s*[A-Da-d][).]\\s*", ""));
        return limpia.length() >= 4 && normalizar(pista).contains(limpia);
    }

    private static String normalizar(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    // ── Bono de la partida ──────────────────────────────────────────────────

    /**
     * Bono en monedas por responder rápido y encadenar aciertos. Las cifras las calcula
     * CustodiaRondaService con sus propios tiempos. Tope de 10 monedas, solo con un intento guardado en los últimos 15 minutos, una
     * vez por partida y a lo más 5 partidas con bono al día.
     */
    public int cobrarBono(Long usuarioId, String partidaId, int rapidas, int comboMaximo) {
        validarPartida(partidaId);
        Optional<Intento> ultimo = intentoRepository.findByUsuarioIdOrderByFechaDesc(usuarioId).stream().findFirst();
        if (ultimo.isEmpty() || ultimo.get().getFecha() == null
                || ultimo.get().getFecha().isBefore(LocalDateTime.now().minusMinutes(MINUTOS_PARA_COBRAR))) {
            throw new IllegalStateException("Primero termina y guarda la práctica.");
        }
        String hoy = LocalDate.now().toString();
        long deHoy = movimientoRepository.findByUsuarioIdAndClaveStartingWith(usuarioId, "partida:").stream()
                .filter(m -> m.getFecha().toLocalDate().toString().equals(hoy)).count();
        if (deHoy >= PARTIDAS_CON_BONO_POR_DIA) return 0;

        int bono = bonoDe(rapidas, comboMaximo);
        if (bono == 0) return 0;
        boolean cobrado = monedasService.premiar(usuarioId, bono,
                "Bono de contrarreloj (" + rapidas + " rápidas, combo x" + comboMaximo + ")", "partida:" + partidaId);
        return cobrado ? bono : 0;
    }

    static int bonoDe(int rapidas, int comboMaximo) {
        int bono = Math.max(0, Math.min(rapidas, 5)) + (comboMaximo >= 3 ? 2 : 0) + (comboMaximo >= 5 ? 3 : 0);
        return Math.min(bono, BONO_MAXIMO);
    }

    private static String clavePartida(String partidaId) {
        return "poder:" + partidaId + ":";
    }

    private static void validarPartida(String partidaId) {
        if (partidaId == null || !ID_PARTIDA.matcher(partidaId).matches()) {
            throw new IllegalArgumentException("Partida no válida.");
        }
    }
}
