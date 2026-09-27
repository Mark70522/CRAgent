package com.company.cragent;

import com.company.cragent.config.InventoryProperties;
import com.company.cragent.config.KnowledgeProperties;
import com.company.cragent.config.ServiceNowProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ServiceNowProperties.class, KnowledgeProperties.class, InventoryProperties.class})
public class CrAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(CrAgentApplication.class, args);
    }
}
