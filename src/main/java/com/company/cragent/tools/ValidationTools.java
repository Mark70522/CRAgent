package com.company.cragent.tools;

import com.company.cragent.model.ChangeDraft;
import com.company.cragent.model.Violation;
import com.company.cragent.validation.RuleEngine;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ValidationTools {

    private final RuleEngine rules;
    private final ServiceNowTools sn;

    public ValidationTools(RuleEngine rules, ServiceNowTools sn) {
        this.rules = rules;
        this.sn = sn;
    }

    public record ValidationResult(boolean passed, int errorCount, int warningCount, List<Violation> violations) {
        static ValidationResult of(List<Violation> v) {
            long errors = v.stream().filter(x -> "error".equalsIgnoreCase(x.severity())).count();
            return new ValidationResult(errors == 0, (int) errors, v.size() - (int) errors, v);
        }
    }

    @Tool(name = "validate_draft", description = "Check a draft against the hard rules (soft rules are yours to judge).")
    public ValidationResult validateDraft(ChangeDraft draft) {
        return ValidationResult.of(rules.validateValues(draft.fields(), draft.tasks()));
    }

    @Tool(name = "validate_change", description = "Check an existing CR against the hard rules.")
    public ValidationResult validateChange(String number) {
        @SuppressWarnings("unchecked")
        List<Violation> v = (List<Violation>) sn.getChange(number).get("violations");
        return ValidationResult.of(v);
    }
}
