// src/main/java/com/example/tallerintegrador/config/VectorConverter.java
package com.example.tallerintegrador.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Converter
public class VectorConverter implements AttributeConverter<List<Float>, String> {

    @Override
    public String convertToDatabaseColumn(List<Float> vector) {
        if (vector == null || vector.isEmpty()) return null;
        // Convierte [0.1, 0.2, 0.3] → "[0.1,0.2,0.3]" (formato pgvector)
        String valores = vector.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        return "[" + valores + "]";
    }

    @Override
    public List<Float> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return List.of();
        // Convierte "[0.1,0.2,0.3]" → List<Float>
        String limpio = dbData.replace("[", "").replace("]", "");
        return Arrays.stream(limpio.split(","))
                .map(Float::parseFloat)
                .collect(Collectors.toList());
    }
}