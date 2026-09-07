package com.example.tallerintegrador.service.rag;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.sax.BodyContentHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Slf4j
@Service
public class TikaExtractorService {

    /** Por encima de 2 MB se asume documento largo y no se decodifican sus imágenes. */
    private static final long UMBRAL_IMAGENES_BYTES = 2L * 1024 * 1024;

    /**
     * OCR apagado por defecto. Solo tiene efecto si además está instalado el ejecutable
     * `tesseract`; sin él, Tika lo omite sin avisar.
     */
    @Value("${ocr.habilitado:false}")
    private boolean ocrHabilitado;

    /** Código de idioma de Tesseract. "spa" necesita el paquete de idioma español. */
    @Value("${ocr.idioma:spa}")
    private String ocrIdioma;

    /** 300 ppp es el punto habitual entre calidad de reconocimiento y tiempo de proceso. */
    @Value("${ocr.dpi:300}")
    private int ocrDpi;

    /** Tope por documento, para que un escaneado enorme no deje el hilo ocupado sin fin. */
    @Value("${ocr.tiempo-limite-segundos:300}")
    private int ocrTiempoLimiteSegundos;


    public String extractText(MultipartFile archivo) throws IOException, TikaException, SAXException {
        try (InputStream is = archivo.getInputStream()) {
            AutoDetectParser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(-1); // -1 para quitar el límite de caracteres de Tika
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();

            // Extracción de las imágenes incrustadas en el PDF, SOLO en documentos pequeños.
            //
            // QUÉ HACE REALMENTE: PDFBox decodifica cada imagen del PDF a un BufferedImage en
            // memoria. Todo ocurre dentro de este proceso Java; no se llama a ningún servicio
            // externo ni se envía nada por red.
            //
            // SOBRE EL OCR: Tika intentaría además pasar esas imágenes por Tesseract, pero
            // Tesseract es un ejecutable APARTE que debe estar instalado en el sistema. Si no
            // está, Tika lo omite en silencio. En esta máquina NO está instalado, así que
            // hasta ahora el OCR nunca llegó a ejecutarse — el comentario anterior, que
            // afirmaba que Tika usaba Tesseract, describía algo que no ocurría.
            //
            // POR QUÉ SE LIMITA IGUALMENTE: aunque no haya OCR, decodificar las imágenes de un
            // documento de cientos de páginas consume memoria y tiempo antes incluso de llegar
            // al troceo. En un PDF de clase es inofensivo; en una obra ilustrada, no. Y como
            // un documento largo casi siempre trae capa de texto, se pierde poco.
            PDFParserConfig pdfConfig = new PDFParserConfig();
            boolean documentoGrande = archivo.getSize() > UMBRAL_IMAGENES_BYTES;
            pdfConfig.setExtractInlineImages(!documentoGrande);
            if (documentoGrande) {
                log.info("[TIKA] Documento de {} KB: se omite la extracción de imágenes incrustadas",
                        archivo.getSize() / 1024);
            }

            // OCR de página completa: lo único que permite leer un PDF ESCANEADO, donde cada
            // página es una imagen y no hay texto que extraer.
            //
            // Requiere el ejecutable `tesseract` instalado en el sistema; sin él Tika lo omite
            // en silencio. Por eso está detrás de una propiedad y APAGADO por defecto: activarlo
            // sin el binario no rompe nada pero tampoco hace nada, y dejarlo encendido "por si
            // acaso" haría creer que el sistema lee escaneados cuando no es cierto. Se activa
            // con `ocr.habilitado=true` una vez comprobado que Tesseract responde.
            //
            // OCR_AND_TEXT_EXTRACTION y no OCR_ONLY: así un PDF que SÍ trae capa de texto usa
            // esa capa —más fiel y mucho más rápida— y el OCR solo aporta lo que falte.
            if (ocrHabilitado) {
                pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.OCR_AND_TEXT_EXTRACTION);
                pdfConfig.setOcrDPI(ocrDpi);
                log.info("[TIKA] OCR activado (idioma '{}', {} ppp). El proceso será notablemente más lento.",
                        ocrIdioma, ocrDpi);

                TesseractOCRConfig ocrConfig = new TesseractOCRConfig();
                ocrConfig.setLanguage(ocrIdioma);
                // Sin este tope, un escaneado de 500 páginas puede dejar el hilo ocupado
                // indefinidamente y bloquear la ingesta entera.
                ocrConfig.setTimeoutSeconds(ocrTiempoLimiteSegundos);

                // NOTA SOBRE LA RUTA DEL EJECUTABLE. En Tika 2.x la ruta de Tesseract ya no
                // se configura aquí: `TesseractOCRConfig` no tiene `setTesseractPath`, se
                // movió al parser. En la práctica esto significa que **Tesseract debe estar
                // en el PATH del proceso**.
                //
                // Dentro del contenedor lo está (el Dockerfile lo instala con apk). En
                // Windows, el instalador lo deja en "C:\Program Files\Tesseract-OCR" SIN
                // añadirlo al PATH, así que ahí hay que añadirlo a mano o el OCR se omitirá
                // en silencio — que es el fallo más engañoso de todos: parece funcionar.
                context.set(TesseractOCRConfig.class, ocrConfig);
            }

            context.set(PDFParserConfig.class, pdfConfig);

            parser.parse(is, handler, metadata, context);

            String textoCompleto = handler.toString();
            // Limpiar saltos de línea excesivos
            return textoCompleto.replaceAll("\\n{3,}", "\n\n").trim();
        }
    }


    //Metodo para lista de archivos
    public String extractTextFromMultipleFiles(List<MultipartFile> archivos) throws IOException, TikaException, SAXException {
        StringBuilder textoCombinado = new StringBuilder();

        for (MultipartFile archivo : archivos) {
            textoCombinado.append("--- Inicio del documento: ")
                    .append(archivo.getOriginalFilename())
                    .append(" ---\n");

            textoCombinado.append(extractText(archivo));

            textoCombinado.append("\n--- Fin del documento ---\n\n");
        }

        String textoFinal = textoCombinado.toString();

        // Quizás debas aumentar este límite dependiendo de qué modelo (LLM) estés usando.
        if (textoFinal.length() > 15000) {
            return textoFinal.substring(0, 15000)
                    + "\n\n[...texto truncado para la prueba...]";
        }

        return textoFinal;
    }

}

