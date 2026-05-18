package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.mongodb.ArchivoPrompt;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ArchivoPromptRepository
        extends MongoRepository<ArchivoPrompt, String> {
}