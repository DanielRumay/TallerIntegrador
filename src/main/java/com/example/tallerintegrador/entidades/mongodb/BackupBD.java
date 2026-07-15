package com.example.tallerintegrador.entidades.mongodb;

import org.springframework.data.annotation.Id;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "backups_bd")
public class BackupBD {

    @Id
    private String id;

    private String nombreArchivo;

    private LocalDateTime fechaCreacion;

    private byte[] data;
}
