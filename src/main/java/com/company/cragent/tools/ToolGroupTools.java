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

    @Tool(name = "use_tools", description = """
            Load a group of tools you need for the current task; they appear in your tool list right after.
            Groups: cr (create / review / update change requests, inventory, templates, rules, examples, ICE),
            learn (rules and examples from rejections / approvals, regression), cockpit (daily tasks, notes,
            knowledge, history), integration (check config, probe an endpoint, save a fixture).
            Call it as soon as the user's request needs one of these; several groups at once is fine.
            If the new tools are not visible yet, finish this reply with one line and they will be there next turn.""")
    public Map<String, Object> useTools(@ToolParam(description = "Group names, e.g. [\"cr\"]") List<String> groups) {
        Map<String, Object> out = new LinkedHashMap<>(registry.enable(groups));
        out.put("next", "Tools of " + groups + " are registered. Call them directly now; if your tool list does not show them, end this reply briefly - they will be available on the next message.");
        return out;
    }

    @Tool(name = "drop_tools", description = "Unload a group of tools when the task is finished, to keep the tool list short. Same group names as use_tools.")
    public Map<String, Object> dropTools(@ToolParam(description = "Group names") List<String> groups) {
        return registry.disable(groups);
    }
}
