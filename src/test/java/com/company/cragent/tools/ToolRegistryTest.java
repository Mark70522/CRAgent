package com.company.cragent.tools;

import com.company.cragent.config.ToolsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** groups mode: core at startup, groups added / removed on demand, shared tools kept while any group needs them. */
class ToolRegistryTest {

    static ToolCallback tool(String name) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() { return DefaultToolDefinition.builder().name(name).description(name).inputSchema("{}").build(); }
            public String call(String input) { return ""; }
        };
    }

    static List<ToolCallback> all() {
        List<ToolCallback> l = new ArrayList<>();
        for (String n : List.of("status", "remember", "search_knowledge", "get_change", "get_day", "open_cockpit", "use_tools", "drop_tools",
                "lookup_ci", "create_change", "read_rules", "list_examples", "add_hard_rule", "add_tasks", "probe")) l.add(tool(n));
        return l;
    }

    @Test
    void allModeRegistersEverythingAtStartup() {
        List<String> sink = new ArrayList<>();
        ToolRegistry r = new ToolRegistry(new ToolsProperties("all", null, null), recorder(sink));
        assertThat(r.init(all())).hasSize(15);
        assertThat(r.summary().get("enabledGroups")).asString().contains("cr", "learn", "cockpit", "integration");
        assertThat(sink).isEmpty();
    }

    @Test
    void loadPicksTheGroupsRegisteredAtStartup() {
        List<String> sink = new ArrayList<>();
        ToolRegistry r = new ToolRegistry(new ToolsProperties("all", null, List.of("CR", "cockpit")), recorder(sink));
        assertThat(r.init(all())).extracting(t -> t.getToolDefinition().name())
                .contains("status", "get_change", "lookup_ci", "create_change", "read_rules", "list_examples", "add_tasks")   // core + cr + cockpit
                .doesNotContain("add_hard_rule", "probe");                                                                     // learn-only, integration
        assertThat(r.summary().get("enabledGroups")).asString().isEqualTo("[cr, cockpit]");
        assertThat(sink).isEmpty();   // nothing changes at run time: works with any client
    }

    @Test
    void groupsModeStartsWithCoreAndSwitchesGroups() {
        List<String> sink = new ArrayList<>();
        ToolRegistry r = new ToolRegistry(new ToolsProperties("groups", List.of("lookup_ci"), null), recorder(sink));
        List<ToolCallback> startup = r.init(all());
        assertThat(startup).extracting(t -> t.getToolDefinition().name())
                .containsExactly("status", "remember", "search_knowledge", "get_change", "get_day", "open_cockpit", "use_tools", "drop_tools", "lookup_ci");

        Map<String, Object> on = r.enable(List.of("CR", "nope"));
        assertThat(String.valueOf(on.get("changed"))).isEqualTo("[create_change, read_rules, list_examples]");   // lookup_ci is core already
        assertThat(String.valueOf(on.get("unknownGroups"))).contains("nope");
        assertThat(sink).containsExactly("+create_change", "+read_rules", "+list_examples");

        r.enable(List.of("learn"));
        assertThat(sink).contains("+add_hard_rule");   // read_rules, already on from cr, is not added twice:
        assertThat(sink.stream().filter(s -> s.equals("+read_rules")).count()).isEqualTo(1);

        // dropping cr keeps what learn still needs
        Map<String, Object> off = r.disable(List.of("cr"));
        assertThat(String.valueOf(off.get("changed"))).isEqualTo("[create_change]");
        assertThat(sink).contains("-create_change").doesNotContain("-read_rules", "-list_examples", "-lookup_ci");
        assertThat(r.enabledNames()).contains("read_rules", "list_examples", "add_hard_rule", "lookup_ci").doesNotContain("create_change");

        r.disable(List.of("learn"));
        assertThat(r.enabledNames()).doesNotContain("read_rules", "add_hard_rule");
        assertThat(r.summary().get("enabledGroups")).asString().isEqualTo("[]");
    }

    static ToolRegistry.Sink recorder(List<String> sink) {
        return new ToolRegistry.Sink() {
            public void add(ToolCallback t) { sink.add("+" + t.getToolDefinition().name()); }
            public void remove(String name) { sink.add("-" + name); }
        };
    }
}
