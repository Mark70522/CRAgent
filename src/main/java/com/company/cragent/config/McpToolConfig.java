package com.company.cragent.config;

import com.company.cragent.audit.AuditLog;
import com.company.cragent.audit.AuditedToolCallback;
import com.company.cragent.tools.CockpitTools;
import com.company.cragent.tools.IceTools;
import com.company.cragent.tools.IntegrationTools;
import com.company.cragent.tools.InventoryTools;
import com.company.cragent.tools.KnowledgeTools;
import com.company.cragent.tools.ServiceNowTools;
import com.company.cragent.tools.StatusTools;
import com.company.cragent.tools.TemplateTools;
import com.company.cragent.tools.ToolGroupTools;
import com.company.cragent.tools.ToolRegistry;
import com.company.cragent.tools.ValidationTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * Registers the @Tool methods as MCP tools, each wrapped so its calls are audited. In tools.mode=all every
 * tool is registered now; in groups mode only the core ones, and ToolRegistry adds the rest on use_tools.
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider crTools(StatusTools status, IntegrationTools integration, InventoryTools inventory, ServiceNowTools serviceNow, IceTools ice,
                                        TemplateTools template, ValidationTools validation, KnowledgeTools knowledge, CockpitTools cockpit,
                                        ToolGroupTools groups, ToolRegistry registry, ToolsProperties props, AuditLog audit) {
        Object[] objects = props.groups()
                ? new Object[]{status, groups, integration, inventory, serviceNow, ice, template, validation, knowledge, cockpit}
                : new Object[]{status, integration, inventory, serviceNow, ice, template, validation, knowledge, cockpit};
        ToolCallback[] raw = MethodToolCallbackProvider.builder().toolObjects(objects).build().getToolCallbacks();
        List<ToolCallback> audited = Arrays.stream(raw).map(t -> (ToolCallback) new AuditedToolCallback(t, audit)).toList();
        ToolCallback[] startup = registry.init(audited).toArray(ToolCallback[]::new);
        return () -> startup;
    }
}
