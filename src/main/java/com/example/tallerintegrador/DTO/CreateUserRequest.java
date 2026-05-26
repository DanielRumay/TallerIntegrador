package com.example.tallerintegrador.DTO;

public record CreateUserRequest(
        String name,
        String email,
        String role,
        String password
) {}