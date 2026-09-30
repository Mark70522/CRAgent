package com.company.cragent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/** Daily task cockpit: where the task files live and on which local port the page is served. */
@ConfigurationProperties(prefix = "cockpit")
public record CockpitProperties(Path dir, Integer port, Boolean web) {
    public Path dir()          { return dir == null ? Path.of("./cockpit") : dir; }
    public int portNumber()    { return port == null ? 7777 : port; }
    public boolean webEnabled(){ return web == null || web; }
    public Path backlog()  { return dir().resolve("backlog.json"); }
    public Path days()     { return dir().resolve("days"); }
    public Path notes()    { return dir().resolve("task-notes"); }
    public Path knowledge(){ return dir().resolve("knowledge"); }
    public Path stats()    { return dir().resolve("stats.json"); }
}
