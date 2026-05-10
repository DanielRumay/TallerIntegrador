package com.example.tallerintegrador.service;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@Service
public class TikaExtractorService {

    private final Tika tika = new Tika();

    public String extractText(MultipartFile archivo) throws IOException, TikaException {
        try (InputStream is = archivo.getInputStream()) {
            // tika.parseToString ya usa PDFBox internamente para PDFs
            String textoCompleto = tika.parseToString(is);

            // Limpiar saltos de línea excesivos
            textoCompleto = textoCompleto
                    .replaceAll("\\n{3,}", "\n\n")
                    .trim();

            // Limitar para no saturar el contexto del modelo
            if (textoCompleto.length() > 5000) {
                return textoCompleto.substring(0, 5000)
                        + "\n\n[...texto truncado a 5000 caracteres para la prueba...]";
            }

            return textoCompleto;
        }
    }
}
