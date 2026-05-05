package com.example.tallerintegrador.service;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Service
public class TikaExtractorService {

    private String extractText(MultipartFile archivo) throws IOException, TikaException, SAXException {
        try (InputStream is = archivo.getInputStream()) {
            AutoDetectParser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(-1); // -1 para quitar el límite de caracteres de Tika
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();

            // Configuración para PDFs con imágenes adentro
            PDFParserConfig pdfConfig = new PDFParserConfig();
            pdfConfig.setExtractInlineImages(true); // Activa la extracción de imágenes dentro del PDF

            context.set(PDFParserConfig.class, pdfConfig);

            // Tika usará tesseract para poder extraer los caracteres que están en el documento en el caso de que tenga imágenes.
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