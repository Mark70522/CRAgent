# Examples

Approved change requests, one YAML file per change, in a folder per template (os-patch/, db-patch/, app-release/).
Added with the `save_example` tool; shaped like:

```yaml
number: CHG0012345
category: os-patch
outcome: approved
saved_on: 2026-10-01
fields: { short_description: ..., description: ..., ... }   # names as your interface returns them
tasks: [ { ... }, ... ]
```

They are used twice: Copilot reads them as writing examples, and `mvn test` / `eval_rules` checks that no
hard rule fails them. Keep 3 to 5 per category; remove ones that no longer match current practice.
