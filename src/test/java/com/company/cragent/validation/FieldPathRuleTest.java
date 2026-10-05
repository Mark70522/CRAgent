package com.company.cragent.validation;

import com.company.cragent.model.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Rules can look inside lists and objects: paths, every item with [*], and item counts. */
class FieldPathRuleTest {

    @TempDir Path tmp;

    RuleEngine engine(String rules) throws Exception {
        Path f = tmp.resolve("hard-rules.yaml");
        Files.writeString(f, "rules:\n" + rules);
        return new RuleEngine(f);
    }

    static Map<String, Object> fields() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("short_description", "Patch");
        f.put("cmdb_ci", Map.of("value", "", "display_value", "srv-app-01"));
        f.put("servers", List.of("srv-app-01"));
        f.put("steps", List.of(Map.of("owner", "ops", "text", "stop"), Map.of("owner", "", "text", "patch")));
        f.put("env", Map.of("tier", "prod"));
        return f;
    }

    @Test
    void pathsItemsAndCounts() throws Exception {
        RuleEngine e = engine("""
                  - {id: R1, severity: error, type: required, field: cmdb_ci.value, description: CI id}
                  - {id: R2, severity: error, type: required, field: "steps[*].owner", description: step owner}
                  - {id: R3, severity: error, type: min_items, field: servers, min: 2, description: two servers}
                  - {id: R4, severity: error, type: max_items, field: steps, max: 5, description: few steps}
                  - {id: R5, severity: error, type: enum, field: env.tier, values: [prod, uat], description: tier}
                  - {id: R6, severity: error, type: required, field: "servers[0]", description: first server}
                  - {id: R7, severity: error, type: required, field: short_description, description: plain field}
                  - {id: R8, severity: warn, type: required, field: backout_plan, description: only for prod, when: {field: env.tier, equals: prod}}
                """);
        List<Violation> v = e.validateValues(fields(), List.of());
        assertThat(v).extracting(Violation::ruleId).containsExactlyInAnyOrder("R1", "R2", "R3", "R8");
        assertThat(v).filteredOn(x -> x.ruleId().equals("R2")).extracting(Violation::field).containsExactly("steps[1].owner");
        assertThat(v).filteredOn(x -> x.ruleId().equals("R3")).first().extracting(Violation::message).asString().contains("has 1");
    }

    @Test
    void textViewOfStoredExamplesStillResolvesPaths() throws Exception {
        // archived examples are text: lists / objects are JSON strings there
        RuleEngine e = engine("""
                  - {id: R3, severity: error, type: min_items, field: servers, min: 2, description: two servers}
                  - {id: R5, severity: error, type: enum, field: env.tier, values: [uat], description: tier}
                """);
        Map<String, String> text = Map.of("servers", "[\"a\",\"b\"]", "env", "{\"tier\":\"prod\"}");
        assertThat(e.validate(text, List.of())).extracting(Violation::ruleId).containsExactly("R5");
    }
}
