---
name: learn-rules
description: Turn rejections, approved examples and things the boss says into rule entries under knowledge/rules. Use when the user states a new rule ("from now on CRs must ..."), asks to learn from rejected or approved changes, or asks to extract rules.
---

# learn-rules

Rules live in files, not in the model. This skill turns evidence into entries that create-cr and review-cr use from then on.

## A. Record a rule the user states

1. Restate it precisely: which field, which condition, what is required.
2. Hard (checkable: required, regex, enum, length, dates, task counts, window) or soft (judgement)?
3. `read_rules` to check nothing already covers it; if something does, say which and propose editing it by hand.
4. After the user confirms: `add_hard_rule` with a YAML snippet, or `add_soft_rule`, with the reason.

## B. Mine rejections

1. `list_examples` for archived rejections (`save_rejected` adds more), and anything the user pastes.
2. Extract each concrete expectation from the rejection comments; group identical ones; count.
3. For each group not yet covered, draft a rule with a one-line rationale and the change numbers as evidence.
4. Present ranked by frequency; add only what the user confirms.

## C. Mine approvals

1. `list_examples` / `read_example` for approved examples per template category, or `get_change` on numbers the user gives.
2. Look for consistent patterns: title format, description sections, always-filled fields, task sequence.
3. Propose template adjustments (knowledge/templates/*.yaml, edited by hand) and soft rules.
4. Offer to `save_example` the best ones.

## Quality bar

One rule, one check. The description must make sense without reading the YAML. `add_hard_rule` runs the
regression (`eval_rules`) and returns it: an approved example failing means the rule is too strict - soften
it or mark the example outdated; a rejected example no longer tripping means a rule was lost. Never leave
the regression red.
