package com.company.cragent;

import com.company.cragent.config.CockpitProperties;
import com.company.cragent.config.IceProperties;
import com.company.cragent.config.InventoryProperties;
import com.company.cragent.config.KnowledgeProperties;
import com.company.cragent.config.ServiceNowProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ServiceNowProperties.class, IceProperties.class, KnowledgeProperties.class, InventoryProperties.class, CockpitProperties.class})
public class CrAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(CrAgentApplication.class, args);
    }
}
