---
name: review-cr
description: Review an existing change request (by number) or a draft against the hard and soft rules, report each violation with a concrete fix. Use when the user asks to review / check / audit a CR, or says a CR was rejected.
---

# review-cr

## 1. Load

1. `read_rules`.
2. `get_change` for the number (returns fields, tasks and the hard-rule result), or use the pasted draft.
3. If `search-changes` is configured: two approved examples for the same server, to compare detail.

## 2. Check

1. Hard-rule violations from `get_change` / `validate_draft`.
2. Read the change against every soft rule.
3. Compare with the examples: what do they have that this one lacks (patch list, business confirmation,
   rollback time, validation owner, task granularity)?

## 3. Report

One table: rule id | severity | field | problem | proposed fix (the actual replacement text).
Then a verdict: ready to submit / needs the fixes above. Changing the record in ServiceNow is done
by the user unless an update endpoint is configured; then call it with `sn_call` only after confirmation.

## 4. Learn from a rejection

If the user says it was rejected: ask for the reason, `save_rejected`, then for each reason decide
whether a rule already covers it; if not, propose one (hard if checkable, else soft) and add it with
`add_hard_rule` / `add_soft_rule` once the user agrees.
