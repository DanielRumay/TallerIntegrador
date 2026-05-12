package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.mongodb.ArchivoPrompt;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ArchivoPromptRepository
        extends MongoRepository<ArchivoPrompt, String> {
}