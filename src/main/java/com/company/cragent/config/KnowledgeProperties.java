package com.company.cragent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "cr")
public record KnowledgeProperties(Path knowledgeDir) {
    public Path rulesDir()     { return knowledgeDir.resolve("rules"); }
    public Path templatesDir() { return knowledgeDir.resolve("templates"); }
    public Path examplesDir()  { return knowledgeDir.resolve("examples"); }
    public Path rejectedDir()  { return knowledgeDir.resolve("rejected"); }
}
