package com.harnesslearn.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentApplication {
    public static void main(String[] args) throws Exception {
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("data"));
        SpringApplication.run(AgentApplication.class, args);
    }
}
