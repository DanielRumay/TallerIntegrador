package com.example.tallerintegrador;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TallerIntegradorApplication {

    public static void main(String[] args) {

        SpringApplication.run(TallerIntegradorApplication.class, args);
    }

}