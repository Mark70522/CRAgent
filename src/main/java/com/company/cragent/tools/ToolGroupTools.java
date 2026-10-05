package com.company.cragent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** use_tools / drop_tools: the two switches that exist only in groups mode. */
@Component
public class ToolGroupTools {

    private final ToolRegistry registry;

    public ToolGroupTools(ToolRegistry registry) { this.registry = registry; }

    @Tool(name = "use_tools", description = "Load tool groups: cr (CRs, ICE, inventory, templates), learn (rules, examples, history), cockpit (daily tasks, knowledge), integration (config, probe).")
    public Map<String, Object> useTools(@ToolParam(description = "e.g. [\"cr\"]") List<String> groups) {
        Map<String, Object> out = new LinkedHashMap<>(registry.enable(groups));
        out.put("next", "Tools of " + groups + " are registered. Call them directly now; if your tool list does not show them, end this reply briefly - they will be available on the next message.");
        return out;
    }

    @Tool(name = "drop_tools", description = "Unload tool groups no longer needed.")
    public Map<String, Object> dropTools(List<String> groups) {
        return registry.disable(groups);
    }
}
