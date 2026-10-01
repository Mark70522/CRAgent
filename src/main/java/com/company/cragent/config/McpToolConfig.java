package com.company.cragent.config;

import com.company.cragent.audit.AuditLog;
import com.company.cragent.audit.AuditedToolCallback;
import com.company.cragent.tools.CockpitTools;
import com.company.cragent.tools.InventoryTools;
import com.company.cragent.tools.KnowledgeTools;
import com.company.cragent.tools.ServiceNowTools;
import com.company.cragent.tools.StatusTools;
import com.company.cragent.tools.TemplateTools;
import com.company.cragent.tools.ValidationTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/** Registers every @Tool method as an MCP tool, each wrapped so its calls are audited. */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider crTools(StatusTools status, InventoryTools inventory, ServiceNowTools serviceNow, TemplateTools template,
                                        ValidationTools validation, KnowledgeTools knowledge, CockpitTools cockpit, AuditLog audit) {
        ToolCallback[] raw = MethodToolCallbackProvider.builder()
                .toolObjects(status, inventory, serviceNow, template, validation, knowledge, cockpit)
                .build().getToolCallbacks();
        ToolCallback[] audited = Arrays.stream(raw).map(t -> (ToolCallback) new AuditedToolCallback(t, audit)).toArray(ToolCallback[]::new);
        return () -> audited;
    }
}
