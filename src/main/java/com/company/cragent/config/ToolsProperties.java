package com.company.cragent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Which tools Copilot sees.
 * <pre>
 * tools:
 *   mode: all        # all (default): every tool is registered at startup
 *                    # groups: only the core set + use_tools / drop_tools; Copilot enables a group when it needs it
 *   core: []         # extra tool names to keep always on in groups mode, e.g. [lookup_service]
 * </pre>
 * groups mode cuts the tool definitions sent with every prompt from ~40 to ~8; it relies on the client
 * honouring MCP's tools/list_changed notification (the JetBrains Copilot plugin refreshes its list on it).
 */
@ConfigurationProperties(prefix = "tools")
public record ToolsProperties(String mode, List<String> core) {
    public String mode() { return mode == null || mode.isBlank() ? "all" : mode.trim().toLowerCase(); }
    public boolean groups() { return "groups".equals(mode()); }
    public List<String> core() { return core == null ? List.of() : core; }
}
