package com.company.cragent.config;

import com.company.cragent.tools.ChangeTools;
import com.company.cragent.tools.CmdbTools;
import com.company.cragent.tools.HistoryTools;
import com.company.cragent.tools.KnowledgeTools;
import com.company.cragent.tools.TemplateTools;
import com.company.cragent.tools.ValidationTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers every @Tool method as an MCP tool. Add a new tool class here to expose it. */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider crTools(CmdbTools cmdb,
                                        ChangeTools change,
                                        TemplateTools template,
                                        ValidationTools validation,
                                        HistoryTools history,
                                        KnowledgeTools knowledge) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(cmdb, change, template, validation, history, knowledge)
                .build();
    }
}
