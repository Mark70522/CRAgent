package com.company.cragent.config;

import com.company.cragent.tools.CockpitTools;
import com.company.cragent.tools.InventoryTools;
import com.company.cragent.tools.KnowledgeTools;
import com.company.cragent.tools.ServiceNowTools;
import com.company.cragent.tools.TemplateTools;
import com.company.cragent.tools.ValidationTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers every @Tool method as an MCP tool. */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider crTools(InventoryTools inventory, ServiceNowTools serviceNow, TemplateTools template,
                                        ValidationTools validation, KnowledgeTools knowledge, CockpitTools cockpit) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(inventory, serviceNow, template, validation, knowledge, cockpit)
                .build();
    }
}
