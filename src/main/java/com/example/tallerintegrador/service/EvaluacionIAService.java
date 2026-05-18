package com.example.tallerintegrador.service;

import com.example.tallerintegrador.entidades.mongodb.ArchivoPrompt;
import com.example.tallerintegrador.entidades.mongodb.Prompt;
import com.example.tallerintegrador.entidades.postgres.*;

import com.example.tallerintegrador.repository.ArchivoPromptRepository;
import com.example.tallerintegrador.repository.DescripcionArchivoRepository;
import lombok.RequiredArgsConstructor;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;
import java.io.IOException;
import java.util.List;

@RequiredArgsConstructor
@Service
public class EvaluacionIAService {

    private Prompt prompt;

    private ArchivoPrompt archivoPrompt;

    private Usuario usuario;

    private Pregunta pregunta;

    private Respuesta respuesta;

    private TikaExtractorService tikaExtractorService;

    private DescripcionArchivoRepository descripcionArchivo;

    private ArchivoPromptRepository archivoRepository;



    public void guardarArchivos(List<MultipartFile> archivos)
            throws IOException, TikaException, SAXException {

        for (MultipartFile file : archivos) {

            // =========================
            // GUARDAR EN MONGODB
            // =========================

            ArchivoPrompt archivo = new ArchivoPrompt();

            archivo.setNombre(file.getOriginalFilename());
            archivo.setTipo(file.getContentType());
            archivo.setUrl("/uploads/" + file.getOriginalFilename());

            archivoRepository.save(archivo);

            // =========================
            // EXTRAER TEXTO CON TIKA
            // =========================

            String contenido =
                    tikaExtractorService.extractTextFromMultipleFiles(
                            List.of(file)
                    );

            // =========================
            // GUARDAR TEXTO EN POSTGRES
            // =========================

            Semana descripcion = new Semana();

            descripcion.setNombre_PDF(file.getOriginalFilename());
            descripcion.setInformacion_PDF(contenido);

            descripcionArchivo.save(descripcion);
        }
    }



}