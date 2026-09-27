package com.company.cragent.tools;

import com.company.cragent.model.ChangeDraft;
import com.company.cragent.model.ChangeRecord;
import com.company.cragent.model.Violation;
import com.company.cragent.servicenow.ServiceNowGateway;
import com.company.cragent.validation.RuleEngine;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ValidationTools {

    private final RuleEngine rules;
    private final ServiceNowGateway sn;

    public ValidationTools(RuleEngine rules, ServiceNowGateway sn) {
        this.rules = rules;
        this.sn = sn;
    }

    public record ValidationResult(boolean passed, int errorCount, int warningCount, List<Violation> violations) {
        static ValidationResult of(List<Violation> v) {
            long errors = v.stream().filter(x -> "error".equalsIgnoreCase(x.severity())).count();
            return new ValidationResult(errors == 0, (int) errors, v.size() - (int) errors, v);
        }
    }

    @Tool(name = "validate_draft", description = """
            Run the hard rules (knowledge/rules/hard-rules.yaml) against a draft. Returns each violation with the
            rule id, field, message and a suggestion. Fix every error and re-validate until passed=true; warnings
            should be fixed too unless the user says otherwise. Soft rules (soft-rules.md) are NOT checked here,
            review them yourself.""")
    public ValidationResult validateDraft(@ToolParam(description = "The draft to validate") ChangeDraft draft) {
        return ValidationResult.of(rules.validate(draft.toServiceNowFields(), ChangeTools.taskRows(draft.tasks())));
    }

    @Tool(name = "validate_change", description = """
            Run the hard rules against an existing change request in ServiceNow (by number). Use it to review
            changes written by people, or to re-check after update_change.""")
    public ValidationResult validateChange(@ToolParam(description = "Change number") String number) {
        ChangeRecord r = sn.getChange(number.trim());
        return ValidationResult.of(rules.validate(r.fields(), r.tasks()));
    }
}
