# Rejected changes

Change requests that were rejected, one YAML file per change, with the rejection reason and (when known)
the hard rules that should catch it. Added with the `save_rejected` tool:

```yaml
number: CHG0012346
outcome: rejected
reason: backout plan has no duration; impact names no business owner
expected_rules: [HR-011]
fields: { ... }
tasks: [ ... ]
```

`mvn test` / `eval_rules` checks that the rules keep tripping on every one of them. The learn-rules skill
mines the reasons for new rules.
