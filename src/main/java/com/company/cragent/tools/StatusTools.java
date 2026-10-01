package com.company.cragent.tools;

import com.company.cragent.audit.AuditLog;
import com.company.cragent.config.CockpitProperties;
import com.company.cragent.config.KnowledgeProperties;
import com.company.cragent.inventory.Inventory;
import com.company.cragent.knowledge.ExampleStore;
import com.company.cragent.knowledge.Regression;
import com.company.cragent.servicenow.ServiceNowClient;
import com.company.cragent.template.TemplateService;
import com.company.cragent.validation.RuleEngine;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/** One call that says whether this installation is ready: config, interface, inventory, rules, examples, cockpit. */
@Component
public class StatusTools {

    private final ServiceNowClient sn;
    private final Inventory inventory;
    private final RuleEngine rules;
    private final TemplateService templates;
    private final ExampleStore examples;
    private final Regression regression;
    private final KnowledgeProperties knowledge;
    private final CockpitProperties cockpit;
    private final AuditLog audit;

    public StatusTools(ServiceNowClient sn, Inventory inventory, RuleEngine rules, TemplateService templates, ExampleStore examples,
                       Regression regression, KnowledgeProperties knowledge, CockpitProperties cockpit, AuditLog audit) {
        this.sn = sn; this.inventory = inventory; this.rules = rules; this.templates = templates; this.examples = examples;
        this.regression = regression; this.knowledge = knowledge; this.cockpit = cockpit; this.audit = audit;
    }

    @Tool(name = "status", description = """
            Health check of this installation: which ServiceNow client is active and what it talks to, inventory rows,
            number of hard rules / templates / archived examples, whether the rule regression passes, cockpit page address,
            audit log location. Call it first on a new machine or when something seems off.""")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("serviceNow", safe(sn::describe));
        m.put("inventory", safe(() -> inventory.entries().size() + " servers from " + inventory.source()));
        m.put("knowledgeDir", knowledge.knowledgeDir().toAbsolutePath().normalize().toString());
        m.put("hardRules", safe(() -> rules.loadRules().size()));
        m.put("templates", safe(() -> templates.listTemplates().stream().map(t -> t.name()).toList()));
        m.put("examples", safe(() -> examples.approved().size() + " approved, " + examples.rejected().size() + " rejected"));
        m.put("ruleRegression", safe(() -> Regression.summary(regression.run())));
        m.put("cockpit", cockpit.webEnabled() ? "http://127.0.0.1:" + cockpit.portNumber() + "/" : "page disabled");
        m.put("auditLog", audit.file().toAbsolutePath().normalize() + (Files.exists(audit.file()) ? "" : " (not created yet)"));
        return m;
    }

    private static Object safe(java.util.function.Supplier<Object> s) {
        try { return s.get(); } catch (RuntimeException e) { return "ERROR: " + e.getMessage(); }
    }
}
