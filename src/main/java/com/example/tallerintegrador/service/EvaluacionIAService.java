package com.example.tallerintegrador.service;

import com.example.tallerintegrador.entidades.mongodb.ArchivoPrompt;
// Importamos clases imaginarias para los fragmentos que tendrás que crear
import com.example.tallerintegrador.entidades.postgres.FragmentoPDF;
import com.example.tallerintegrador.repository.ArchivoPromptRepository;
import com.example.tallerintegrador.repository.FragmentoPDFRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

// Librerías de LangChain4j para el chunking inteligente
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Service
public class EvaluacionIAService {

    private final TikaExtractorService tikaExtractorService;
    private final ArchivoPromptRepository archivoRepository;

    // Repositorio para guardar los pedazos de texto y sus vectores
    private final FragmentoPDFRepository fragmentoPDFRepository;

    // Servicio que se conectará a la API de Gemini (lo implementaremos después)
    private final GeminiEmbeddingService geminiEmbeddingService;

    public void guardarArchivos(List<MultipartFile> archivos) {

        for (MultipartFile file : archivos) {
            try {
                // 1. GUARDAR METADATOS EN MONGO (Esto lo tienes bien)
                ArchivoPrompt archivo = new ArchivoPrompt();
                archivo.setNombre(file.getOriginalFilename());
                archivo.setTipo(file.getContentType());
                archivo.setUrl("/uploads/" + file.getOriginalFilename());
                archivoRepository.save(archivo);
                log.info("Archivo metadata guardado en Mongo: {}", file.getOriginalFilename());

                // 2. EXTRAER TODO EL TEXTO DEL PDF
                String contenidoBruto = tikaExtractorService.extractTextFromMultipleFiles(List.of(file));

                // 3. CHUNKING INTELIGENTE (Aquí está la magia de los 500)
                // Corta de 500 en 500 tokens, pero repite 50 tokens entre cada corte para no perder contexto
                DocumentSplitter splitter = DocumentSplitters.recursive(500, 50);
                Document documentoLangchain = Document.from(contenidoBruto);
                List<TextSegment> fragmentos = splitter.split(documentoLangchain);

                log.info("PDF dividido en {} fragmentos", fragmentos.size());

                // 4. EMBEDDINGS Y GUARDADO EN POSTGRES
                for (TextSegment fragmento : fragmentos) {

                    // A. Convertir el texto a números usando Gemini
                    List<Float> vector = geminiEmbeddingService.obtenerVector(fragmento.text());

                    // B. Guardar el fragmento y su vector en PostgreSQL
                    FragmentoPDF nuevoFragmento = new FragmentoPDF();
                    nuevoFragmento.setNombreArchivo(file.getOriginalFilename());
                    nuevoFragmento.setTexto(fragmento.text());
                    nuevoFragmento.setEmbedding(vector); // Esta columna debe ser tipo pgvector en BD

                    fragmentoPDFRepository.save(nuevoFragmento);
                }

                log.info("Fragmentos vectorizados y guardados en Postgres para: {}", file.getOriginalFilename());

            } catch (Exception e) {
                log.error("Error al procesar el archivo {}: {}", file.getOriginalFilename(), e.getMessage());
            }
        }
    }
}