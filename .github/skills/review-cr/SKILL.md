---
name: review-cr
description: Review an existing change request (by number) or a draft against the hard and soft rules, report each violation with a concrete fix. Use when the user asks to review / check / audit a CR, or says a CR was rejected.
---

# review-cr

## 1. Load

1. `read_rules`.
2. `get_change` for the number (returns fields, tasks and the hard-rule result), or use the pasted draft.
3. `list_examples` / `read_example`: one or two archived approved examples of the same category, to compare detail.

## 2. Check

1. Hard-rule violations from `get_change` / `validate_draft`.
2. Read the change against every soft rule.
3. Compare with the examples: what do they have that this one lacks (patch list, business confirmation,
   rollback time, validation owner, task granularity)?

## 3. Report

One table: rule id | severity | field | problem | proposed fix (the actual replacement text).
Then a verdict: ready to submit / needs the fixes above.

## 4. Apply (only if the user asks)

Show exactly which fields will change and the new text; after the user agrees, `update_change` with
`confirmed=true`. Then `get_change` again and show the new hard-rule result.

## 5. Learn from a rejection

If the user says it was rejected: ask for the reason, then for each reason decide whether a rule already
covers it. `save_rejected` with the reason and the ids of the rules that should catch it (expectedRules).
Reasons no rule covers: propose one (hard if checkable, else soft) and add it with `add_hard_rule` /
`add_soft_rule` once the user agrees. `add_hard_rule` returns the regression result: if an approved
example now fails, the rule is too strict - adjust it before moving on.
