---
name: review-cr
description: Review an existing ServiceNow change request (by CHG number) or a draft against the hard and soft rules, report each violation with a concrete fix, and optionally apply the fixes. Use when the user asks to review / check / audit / fix a CR, or says a CR was rejected.
---

# review-cr

## 1. Load

1. `read_rules`.
2. `get_change` for the number (or use the draft the user pasted).
3. `find_similar_changes` for the same CI/type, 2 approved examples, to compare the level of detail.

## 2. Check

1. `validate_change` (or `validate_draft`) – hard rules.
2. Read the change against every soft rule.
3. Compare with the approved examples: is anything present there and missing here (patch list,
   business confirmation, rollback time, validation owner, task granularity)?

## 3. Report

One table: rule id | severity | field | problem | proposed fix (the actual replacement text, not "improve").
Then a verdict: ready to submit / needs the fixes above.

## 4. Apply (only if the user asks)

- `update_change` with the corrected fields, `add_change_tasks` if tasks are missing.
- Re-run `validate_change` and show the result.

## 5. Learn from a rejection

If the user says the change was rejected, ask for (or read from the approvals) the rejection reason, then:

1. `save_rejected` with the reason.
2. For each reason, decide whether it is already covered by a rule. If not, propose a new rule
   (hard if it can be checked mechanically, otherwise soft) and, once the user agrees, add it with
   `add_hard_rule` / `add_soft_rule`.
3. Fix the change (step 4) so it can be resubmitted.
