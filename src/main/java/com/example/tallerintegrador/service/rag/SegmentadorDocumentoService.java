package com.example.tallerintegrador.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parte un documento largo en secciones logicas antes de trocearlo.
 *
 * POR QUE EXISTE. Hasta ahora el pipeline hacia dos cosas sobre el documento ENTERO: pedirle
 * a Gemini "3 a 5 subtemas" y cortar cada 2000 caracteres. Con un PDF de clase da igual. Con
 * una obra completa, el resumen de 300 paginas devuelve algo como "el amor, la guerra, el
 * destino" — funciona y devuelve basura, que es peor que fallar porque nadie se entera.
 *
 * Con secciones, cada capitulo recibe su propia extraccion de subtemas y sus fragmentos
 * quedan etiquetados con la seccion a la que pertenecen.
 *
 * CASCADA, de mas fiable a ultimo recurso. Es el mismo patron que la escalera de umbrales de
 * RagRetrieverService: se intenta lo bueno y se degrada dejando constancia de por donde se
 * cayo, en vez de fallar o de fingir que todo fue bien.
 *
 *   ENCABEZADOS  -> se detectaron titulos reales ("CAPITULO III", "PARTE SEGUNDA", "1.2 ...")
 *   TIPOGRAFICO  -> no hay titulos, pero si lineas cortas aisladas que actuan como tal
 *   VENTANAS     -> no hay estructura; se corta en bloques de tamano fijo
 *
 * El ultimo nivel importa mas de lo que parece: aunque no se detecte ninguna estructura real,
 * CUALQUIER unidad menor que el libro entero ya arregla el defecto grave. Una segmentacion
 * aproximada es incomparablemente mejor que ninguna.
 *
 * Es una funcion pura sobre el texto: no llama al modelo ni toca la base de datos, asi que se
 * puede probar de forma exhaustiva.
 */
@Slf4j
@Service
public class SegmentadorDocumentoService {

    /** Por debajo de esto no vale la pena segmentar: es un documento de clase normal. */
    static final int UMBRAL_DOCUMENTO_LARGO = 20_000;

    /** Tamano objetivo de una seccion cuando hay que cortar por ventanas. */
    static final int VENTANA_CARACTERES = 12_000;

    /** Una seccion mas corta que esto se pega a la anterior en vez de quedar suelta. */
    static final int MINIMO_SECCION = 400;

    public enum Metodo { ENCABEZADOS, TIPOGRAFICO, VENTANAS, DOCUMENTO_COMPLETO }

    public record Seccion(String titulo, String texto, int orden) {}

    public record Segmentacion(List<Seccion> secciones, Metodo metodo) {
        public boolean degradada() {
            return metodo == Metodo.VENTANAS;
        }
    }

    /**
     * Titulos explicitos. `(?im)` = sin distinguir mayusculas y evaluando linea a linea, para
     * que `^` case con el inicio de cada renglon y no solo del documento.
     */
    private static final Pattern ENCABEZADO_EXPLICITO = Pattern.compile(
            "(?im)^\\s{0,6}(" +
            "cap[ií]tulo\\s+[\\dIVXLCDM]+|" +
            "cap[ií]tulo\\s+(primero|segundo|tercero|cuarto|quinto|sexto|s[eé]ptimo|octavo|noveno|d[eé]cimo)|" +
            "parte\\s+[\\dIVXLCDM]+|" +
            "parte\\s+(primera|segunda|tercera|cuarta|quinta)|" +
            "unidad\\s+[\\dIVXLCDM]+|" +
            "secci[óo]n\\s+[\\dIVXLCDM]+|" +
            "tema\\s+\\d+|" +
            "\\d{1,2}\\.\\s+[A-ZÁÉÍÓÚÑ][^\\n]{3,80}" +
            ")\\s*$");

    /**
     * Heuristica tipografica: una linea corta, sin punto final, rodeada de lineas en blanco.
     * Es como se ve un titulo cuando el PDF perdio el formato al extraerse.
     */
    private static final Pattern ENCABEZADO_TIPOGRAFICO = Pattern.compile(
            "(?m)^\\s{0,6}([A-ZÁÉÍÓÚÑ][^\\n]{2,70}[^.\\s])\\s*$");

    public Segmentacion segmentar(String texto) {
        if (texto == null || texto.isBlank()) {
            return new Segmentacion(List.of(), Metodo.DOCUMENTO_COMPLETO);
        }

        // Un documento corto NO se segmenta. Partir un PDF de ocho paginas en trozos solo
        // añade llamadas al modelo y empeora los subtemas, que en ese caso ya eran buenos.
        if (texto.length() < UMBRAL_DOCUMENTO_LARGO) {
            log.info("[SEGMENTADOR] Documento de {} caracteres: se trata como una sola seccion",
                    texto.length());
            return new Segmentacion(
                    List.of(new Seccion(null, texto, 0)), Metodo.DOCUMENTO_COMPLETO);
        }

        List<Seccion> porExplicitos = cortarPor(texto, ENCABEZADO_EXPLICITO);
        if (porExplicitos.size() >= 2) {
            log.info("[SEGMENTADOR] {} secciones por encabezados explicitos", porExplicitos.size());
            return new Segmentacion(porExplicitos, Metodo.ENCABEZADOS);
        }

        List<Seccion> porTipografia = cortarPor(texto, ENCABEZADO_TIPOGRAFICO);
        // Se exige un minimo de 3 y un maximo razonable: si la heuristica dispara cientos de
        // veces es que esta marcando lineas normales, no titulos, y su resultado no vale.
        int maximoRazonable = Math.max(4, texto.length() / 3_000);
        if (porTipografia.size() >= 3 && porTipografia.size() <= maximoRazonable) {
            log.info("[SEGMENTADOR] {} secciones por heuristica tipografica", porTipografia.size());
            return new Segmentacion(porTipografia, Metodo.TIPOGRAFICO);
        }

        List<Seccion> porVentanas = cortarPorVentanas(texto);
        log.warn("[SEGMENTADOR] Sin estructura detectable: {} secciones por ventanas fijas. " +
                 "Los subtemas por seccion seran menos precisos.", porVentanas.size());
        return new Segmentacion(porVentanas, Metodo.VENTANAS);
    }

    private List<Seccion> cortarPor(String texto, Pattern patron) {
        Matcher m = patron.matcher(texto);

        List<Integer> inicios = new ArrayList<>();
        List<String> titulos = new ArrayList<>();
        while (m.find()) {
            inicios.add(m.start());
            titulos.add(m.group(1).trim());
        }
        if (inicios.isEmpty()) return List.of();

        List<Seccion> secciones = new ArrayList<>();

        // Todo lo que va ANTES del primer titulo (prologo, indice, portada) no se descarta:
        // se conserva como seccion inicial sin titulo. Tirarlo perderia texto del documento.
        if (inicios.get(0) > MINIMO_SECCION) {
            secciones.add(new Seccion(null, texto.substring(0, inicios.get(0)).strip(), 0));
        }

        for (int i = 0; i < inicios.size(); i++) {
            int fin = (i + 1 < inicios.size()) ? inicios.get(i + 1) : texto.length();
            String cuerpo = texto.substring(inicios.get(i), fin).strip();

            if (cuerpo.length() < MINIMO_SECCION && !secciones.isEmpty()) {
                // Un titulo sin apenas contenido debajo (portadilla, pagina de cortesia) se
                // funde con la seccion anterior en vez de generar una seccion vacia que luego
                // produciria subtemas inventados.
                Seccion previa = secciones.remove(secciones.size() - 1);
                secciones.add(new Seccion(previa.titulo(), previa.texto() + "\n\n" + cuerpo, previa.orden()));
                continue;
            }
            secciones.add(new Seccion(titulos.get(i), cuerpo, secciones.size()));
        }

        return renumerar(secciones);
    }

    private List<Seccion> cortarPorVentanas(String texto) {
        List<Seccion> secciones = new ArrayList<>();
        int inicio = 0;
        int n = 1;

        while (inicio < texto.length()) {
            int fin = Math.min(inicio + VENTANA_CARACTERES, texto.length());

            // Se busca un salto de parrafo cerca del corte para no partir una idea a la mitad.
            if (fin < texto.length()) {
                int parrafo = texto.lastIndexOf("\n\n", fin);
                if (parrafo > inicio + VENTANA_CARACTERES / 2) {
                    fin = parrafo;
                }
            }

            String cuerpo = texto.substring(inicio, fin).strip();
            if (!cuerpo.isBlank()) {
                secciones.add(new Seccion("Bloque " + n++, cuerpo, secciones.size()));
            }
            inicio = fin;
        }
        return secciones;
    }

    private List<Seccion> renumerar(List<Seccion> secciones) {
        List<Seccion> salida = new ArrayList<>(secciones.size());
        for (int i = 0; i < secciones.size(); i++) {
            Seccion s = secciones.get(i);
            salida.add(new Seccion(s.titulo(), s.texto(), i));
        }
        return salida;
    }
}
