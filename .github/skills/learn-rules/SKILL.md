---
name: learn-rules
description: Mine historical change requests (rejections and approvals) to derive the rules approvers actually apply, propose them, and record confirmed rules into knowledge/rules. Also used to record a new rule the boss just stated. Use when the user asks to learn from history, extract rules, or says "from now on CRs must ...".
---

# learn-rules

Rules live in files, not in the model. This skill turns evidence into rule entries that create-cr and
review-cr will use from then on.

## A. Record a rule the user states

1. Restate the rule precisely: which field, which condition, what is required.
2. Decide: hard (checkable: required, regex, enum, length, dates, task counts) or soft (judgement).
3. Check `read_rules` for an existing rule that already covers it; if so, propose editing that one
   (tell the user the file and id; edits to existing rules are done by hand).
4. After the user confirms: `add_hard_rule` with a YAML snippet, or `add_soft_rule`. Give the reason
   (who asked, date, triggering change if any).

## B. Mine rejections

1. `find_rejected_changes` since a date (default: 12 months), or `list_examples` for archived rejections.
2. For each rejection comment, extract the concrete expectations (one comment often holds several).
3. Group identical expectations; count how often each appears.
4. For each group not yet covered by `read_rules`, draft a rule (hard if possible) with a one-line
   rationale and the change numbers as evidence.
5. Present the candidate list ranked by frequency. Add only the ones the user confirms.

## C. Mine approvals

1. `find_similar_changes` (approved only) per template category, 10 results.
2. Look for consistent patterns: title format, description sections, which fields are always filled,
   typical task sequence and durations, typical risk/category per CI type.
3. Propose: template adjustments (knowledge/templates/*.yaml, edited by hand) and soft rules.
4. Offer to `save_example` the 3 best changes per category as gold examples.

## Rule quality bar

- One rule, one check. Do not bundle.
- The description must let a colleague understand the rule without reading the YAML.
- Hard rules must not produce false positives on the archived examples: after adding one, run
  `validate_change` on a couple of approved examples and remove or soften the rule if they fail.
