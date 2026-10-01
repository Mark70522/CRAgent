package com.company.cragent.knowledge;

import com.company.cragent.model.Violation;
import com.company.cragent.validation.RuleEngine;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Runs the hard rules over every archived change and says whether the rule set still agrees with history:
 *   - an approved change must produce no error-level violation (otherwise a rule is too strict: false positive)
 *   - a rejected change must trip at least one rule, and every rule listed in expected_rules
 *     (otherwise the rules do not catch what the approvers rejected: a gap)
 * Run it after every rule change. Also executed by mvn test (KnowledgeRegressionTest).
 */
@Component
public class Regression {

    public record Result(String file, String number, String outcome, boolean ok, String detail) {}

    public record Report(int approvedChecked, int rejectedChecked, List<Result> failures) {
        public boolean ok() { return failures.isEmpty(); }
    }

    private final ExampleStore examples;
    private final RuleEngine rules;

    public Regression(ExampleStore examples, RuleEngine rules) {
        this.examples = examples;
        this.rules = rules;
    }

    public Report run() {
        List<Result> failures = new ArrayList<>();
        int a = 0, r = 0;
        for (ExampleStore.Example e : examples.all()) {
            List<Violation> v = rules.validate(e.fields(), e.tasks());
            List<String> errors = v.stream().filter(x -> "error".equalsIgnoreCase(x.severity())).map(Violation::ruleId).distinct().toList();
            String rel = e.relative(examples.root());
            if (e.approved()) {
                a++;
                if (!errors.isEmpty()) failures.add(new Result(rel, e.number(), "approved", false,
                        "approved change now fails " + errors + " - a rule is too strict or the example is outdated"));
            } else {
                r++;
                List<String> all = v.stream().map(Violation::ruleId).distinct().toList();
                List<String> missing = e.expectedRules().stream().filter(x -> !all.contains(x)).toList();
                if (all.isEmpty()) failures.add(new Result(rel, e.number(), "rejected", false,
                        "rejected change passes every hard rule - the rejection reason is not covered: " + e.reason()));
                else if (!missing.isEmpty()) failures.add(new Result(rel, e.number(), "rejected", false,
                        "expected rules " + missing + " did not fire (fired: " + all + ")"));
            }
        }
        return new Report(a, r, failures);
    }

    public static String summary(Report rep) {
        StringBuilder sb = new StringBuilder();
        sb.append(rep.ok() ? "OK" : "FAIL").append(": ").append(rep.approvedChecked()).append(" approved, ")
          .append(rep.rejectedChecked()).append(" rejected checked, ").append(rep.failures().size()).append(" problem(s)");
        for (Result f : rep.failures()) sb.append("\n  - ").append(f.file()).append(": ").append(f.detail());
        return sb.toString();
    }

    public static String list(List<Result> rs) { return rs.stream().map(r -> r.file() + ": " + r.detail()).collect(Collectors.joining("\n")); }
}
