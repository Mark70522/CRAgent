package com.company.cragent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Which tools Copilot sees.
 * <pre>
 * tools:
 *   mode: all        # all (default): tools are registered at startup
 *                    # groups: only the core set + use_tools / drop_tools; Copilot enables a group when it needs it
 *   load: []         # mode all only: the groups to register at startup, e.g. [cr, cockpit]; empty = every group.
 *                    # Fixed at startup, so it works with any client (nothing depends on tools/list_changed).
 *   core: []         # extra tool names to keep always on, e.g. [lookup_service]
 * </pre>
 * Groups: cr, learn, cockpit, integration (see ToolRegistry). Fewer tools = fewer tokens per prompt and fewer
 * wrong picks by a small model.
 */
@ConfigurationProperties(prefix = "tools")
public record ToolsProperties(String mode, List<String> core, List<String> load) {
    public String mode() { return mode == null || mode.isBlank() ? "all" : mode.trim().toLowerCase(); }
    public boolean groups() { return "groups".equals(mode()); }
    public List<String> core() { return core == null ? List.of() : core; }
    public List<String> load() { return load == null ? List.of() : load.stream().map(s -> s.trim().toLowerCase()).filter(s -> !s.isEmpty()).toList(); }
}
