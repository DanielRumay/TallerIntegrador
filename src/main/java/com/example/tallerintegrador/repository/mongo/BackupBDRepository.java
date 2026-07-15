package com.example.tallerintegrador.repository.mongo;

import com.example.tallerintegrador.entidades.mongodb.BackupBD;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BackupBDRepository extends MongoRepository<BackupBD, String> {
}
