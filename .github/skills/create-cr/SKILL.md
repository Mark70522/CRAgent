---
name: create-cr
description: Create a ServiceNow change request from a short instruction (servers or service, what, when). Drafts from a template, fills fields from the inventory and past changes, lays out tasks with times, validates against the rules, and creates the CR through the configured endpoint only after the user confirms. Use when the user asks to create / draft / raise a CR.
---

# create-cr

Goal: a change request that passes approval the first time. Follow the steps in order; do not skip validation.

## 1. Understand

Extract: servers or service name, what is being changed, desired start time, anything specific the user
gave (ticket numbers, patch names, versions, business confirmation). No start time → ask.

Is this CR for one of the user's cockpit tasks? `list_tasks` (open) and match by wording ("给 CCS PROD
打补丁" ↔ a task about CCS PROD patching). If one matches, remember its id and use its `scheduledAt`
as the default start time; if the task has a `cr` already, say so and stop - review-cr is the skill then.

## 2. Ground the facts

1. `read_rules`.
2. Servers: `lookup_ci` for named servers, `lookup_service` for a service name. Show the list and let the
   user confirm scope. A server missing from the inventory → stop and ask; never guess.
   The result includes each server's maintenance window; the usual one is Sunday 00:00-06:00. If the
   requested time is outside it, say so once and ask the user to confirm (HR-018 only warns).
3. `list_templates` → pick by keywords; none fits → say so and offer the closest.
4. `list_examples` → read one or two archived examples of the same category (`read_example`) and use their
   wording, field values and task sequence as the reference. If the user names an earlier change,
   `get_change` it instead.

## 3. Build

1. `build_draft` with template, servers, planned start, one-line summary.
2. Replace every `<TODO: ...>` in the description field with concrete content from the user's words and
   the examples. Keep the numbered sections.
3. Adjust task durations if the user or the examples say so; tasks stay back to back inside the window.
4. Write justification / implementation / backout / test plans concretely (soft rules SR-002, SR-003).
   Template defaults are starting points. Field names stay exactly as the template gives them.

## 4. Validate and self-review

1. `validate_draft`; fix every error, re-run until `passed=true` (max 3 loops, then show what remains).
   Warnings: fix unless the user chose that value on purpose.
2. Walk through the soft rules.

## 5. Show and confirm

Table of fields plus the task timeline, and which fields came from inventory / template / examples /
the user / assumptions. Ask to confirm or correct. Apply corrections, re-validate.

## 6. Create

Only when the user explicitly says to create: `create_change` with `confirmed=true` and `taskId` when a
cockpit task matched in step 1 (the task then carries the number and the morning brief tracks it).
Report what the endpoint returned (the number if it is in the response). Remind the user that
submitting for approval is still done by them; once submitted they can say "T-xxxx 等审批" and the
task moves to waiting.

**ICE.** Every CR also has to be registered in ICE. If `status` shows ICE configured: right after the CR
is created, `draft_ice` with the new number - it derives the ICE fields from the CR by the rules in
cr-agent.yml (`ice.from-change`), the same way every time. Show the fields, fill any `emptyFields` from
the user's words, apply corrections, and on the user's word `create_ice` with `confirmed=true`, the CR
number and the same `taskId`. The task then shows both numbers. If `draft_ice` returns a `hint`
(from-change not configured), compose the fields by hand and say which CR fields you used. If ICE is
not configured at all, say once that the ICE step is still manual.

## 7. Learn

If a correction looks like a general rule ("we always ...", "the boss wants ..."), offer to record it
with `add_hard_rule` / `add_soft_rule` (see learn-rules).
