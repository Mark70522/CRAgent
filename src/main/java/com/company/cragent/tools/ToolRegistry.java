package com.company.cragent.tools;

import com.company.cragent.config.ToolsProperties;
import io.modelcontextprotocol.server.McpSyncServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Knows every tool, which group it belongs to, and which are currently registered with the MCP server.
 * In groups mode only the core set is registered at startup; use_tools / drop_tools add and remove whole
 * groups at run time and the server tells the client the list changed.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** Tool name -> group. Everything not listed here is core (always on). */
    static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();
    static {
        GROUPS.put("cr", List.of("lookup_ci", "lookup_service", "list_templates", "get_template", "build_draft", "draft_from_change", "validate_draft", "validate_change",
                "create_change", "update_change", "create_task", "cancel_task", "close_task",
                "read_rules", "list_examples", "read_example", "draft_ice", "get_ice", "create_ice", "update_ice", "ice_score"));
        GROUPS.put("learn", List.of("read_rules", "add_hard_rule", "add_soft_rule", "save_example", "save_rejected", "eval_rules", "list_examples", "read_example",
                "import_changes", "history_groups", "save_template_from_history"));
        GROUPS.put("cockpit", List.of("add_tasks", "list_tasks", "update_task", "task_notes", "plan_day", "capture_note", "close_day",
                "save_knowledge", "read_knowledge", "task_history", "cockpit_url"));
        GROUPS.put("integration", List.of("check_config", "probe", "save_fixture"));
    }
    static final Map<String, String> GROUP_HELP = Map.of(
            "cr", "create / review / update change requests and their ICE records (inventory, templates, rules, examples)",
            "learn", "turn rejections, approvals and what the boss says into rules and examples; run the rule regression",
            "cockpit", "daily tasks: add, plan, update, notes, close the day, knowledge files, task history",
            "integration", "first-day tools: check cr-agent.yml, probe an endpoint, save a fixture");

    /** Where enabled tools go: the live MCP server, or a recorder in tests. */
    public interface Sink {
        void add(ToolCallback tool);
        void remove(String name);
    }

    private final ToolsProperties props;
    private final Sink sink;
    private final Map<String, ToolCallback> all = new LinkedHashMap<>();
    private final Set<String> enabled = new LinkedHashSet<>();
    private final Set<String> enabledGroups = new LinkedHashSet<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ToolRegistry(ToolsProperties props, ObjectProvider<McpSyncServer> server) {
        this(props, new Sink() {
            public void add(ToolCallback t) { server.getObject().addTool(McpToolUtils.toSyncToolSpecification(t)); }
            public void remove(String name) { server.getObject().removeTool(name); }
        });
    }

    public ToolRegistry(ToolsProperties props, Sink sink) {
        this.props = props;
        this.sink = sink;
    }

    /** Called once by McpToolConfig with every audited callback; returns the ones to register at startup. */
    public synchronized List<ToolCallback> init(List<ToolCallback> tools) {
        all.clear(); enabled.clear(); enabledGroups.clear();
        for (ToolCallback t : tools) all.put(t.getToolDefinition().name(), t);
        // mode all + load: only the listed groups (and core); mode all alone: every group; mode groups: core only
        Set<String> atStart = props.groups() ? Set.of() : props.load().isEmpty() ? GROUPS.keySet() : new LinkedHashSet<>(props.load());
        List<String> unknown = atStart.stream().filter(g -> !GROUPS.containsKey(g)).toList();
        if (!unknown.isEmpty()) log.warn("tools.load names unknown group(s) {}; groups are {}", unknown, GROUPS.keySet());
        List<ToolCallback> startup = new ArrayList<>();
        for (ToolCallback t : tools) {
            String name = t.getToolDefinition().name();
            boolean inLoaded = atStart.stream().anyMatch(g -> GROUPS.getOrDefault(g, List.of()).contains(name));
            if (isCore(name) || inLoaded) { startup.add(t); enabled.add(name); }
        }
        atStart.stream().filter(GROUPS::containsKey).forEach(enabledGroups::add);
        log.info("tools mode={} load={} registered at startup: {} of {}", props.mode(), props.load(), startup.size(), all.size());
        return startup;
    }

    public boolean isCore(String name) {
        if (props.core().contains(name)) return true;
        return GROUPS.values().stream().noneMatch(g -> g.contains(name));
    }

    public synchronized Map<String, Object> enable(Collection<String> groups) {
        List<String> added = new ArrayList<>(), unknown = new ArrayList<>();
        for (String g : normalise(groups)) {
            List<String> names = GROUPS.get(g);
            if (names == null) { unknown.add(g); continue; }
            for (String n : names) {
                ToolCallback t = all.get(n);
                if (t == null || enabled.contains(n)) continue;
                sink.add(t);
                enabled.add(n);
                added.add(n);
            }
            enabledGroups.add(g);
        }
        return result(added, unknown);
    }

    public synchronized Map<String, Object> disable(Collection<String> groups) {
        List<String> removed = new ArrayList<>(), unknown = new ArrayList<>();
        for (String g : normalise(groups)) {
            List<String> names = GROUPS.get(g);
            if (names == null) { unknown.add(g); continue; }
            enabledGroups.remove(g);
            for (String n : names) {
                if (!enabled.contains(n) || isCore(n)) continue;
                // keep tools another enabled group still needs
                boolean stillNeeded = enabledGroups.stream().anyMatch(og -> GROUPS.get(og).contains(n));
                if (stillNeeded) continue;
                sink.remove(n);
                enabled.remove(n);
                removed.add(n);
            }
        }
        return result(removed, unknown);
    }

    public synchronized Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", props.mode());
        m.put("enabledTools", enabled.size() + " of " + all.size());
        m.put("enabledGroups", new ArrayList<>(enabledGroups));
        Map<String, String> groups = new LinkedHashMap<>();
        GROUPS.forEach((g, names) -> groups.put(g, (enabledGroups.contains(g) ? "on  " : "off ") + GROUP_HELP.get(g) + " (" + names.size() + " tools)"));
        m.put("groups", groups);
        return m;
    }

    public synchronized Set<String> enabledNames() { return new LinkedHashSet<>(enabled); }

    private Map<String, Object> result(List<String> changed, List<String> unknown) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("changed", changed);
        if (!unknown.isEmpty()) m.put("unknownGroups", unknown + " (groups: " + GROUPS.keySet() + ")");
        m.put("enabledGroups", new ArrayList<>(enabledGroups));
        m.put("enabledTools", new ArrayList<>(enabled));
        return m;
    }

    private static List<String> normalise(Collection<String> groups) {
        List<String> out = new ArrayList<>();
        if (groups == null) return out;
        for (String g : groups) if (g != null && !g.isBlank()) out.add(g.trim().toLowerCase());
        return out;
    }
}
