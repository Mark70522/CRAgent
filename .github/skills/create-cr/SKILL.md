---
name: create-cr
description: Create a ServiceNow change request from a short instruction (servers, what, when). Drafts from a template, fills fields from the CMDB and similar historical changes, lays out change tasks with times, validates against the rules, and creates the CR only after the user confirms. Use when the user asks to create / draft / raise a CR or change request.
---

# create-cr

Goal: a change request that passes approval the first time. Follow the steps in order; do not skip validation.

## 1. Understand the request

Extract: servers (CI names or IPs), what is being changed, desired start time, anything special the user
mentioned (ticket numbers, patch names, versions, business confirmation). If the start time is missing, ask.

## 2. Ground the facts

1. `read_rules` – load the current hard and soft rules.
2. Resolve the servers from the inventory:
   - user named servers → `lookup_ci` with all of them;
   - user named a service/application ("Order Portal prod") → `lookup_service` with service and environment,
     then show the server list and let the user confirm the scope before going on.
   If a server is missing or ambiguous, stop and ask; never guess a server.
3. `list_templates` – pick the template whose keywords match. If none fits, tell the user and offer the closest.
4. `find_similar_changes` – same CI or same keyword, approved only, 3 results. Read their description,
   task list and durations: they are the reference for wording and level of detail.
5. `history_field_stats` – when unsure about assignment_group / risk / category for this CI, use the
   value that was approved most often.
6. `get_change_windows` – check the requested time. The usual window is Sunday 00:00-06:00, but the user
   may shift it. If the requested time is outside it, say so in one line and ask the user to confirm;
   keep their time if they confirm (HR-018 is a warning, not a blocker). Never move the time silently.
   If the user gives no time, propose the next Sunday 01:00 and ask.

## 3. Build the draft

1. `build_draft` with the template, CI names, planned start and a one-line summary.
2. Replace every `<TODO: ...>` in the description with concrete content. Use the section hints, the
   user's words and the similar changes. Keep the numbered section layout.
3. Adjust task durations or add tasks if the similar changes or the user indicate so; keep tasks
   back-to-back and inside the window.
4. Write justification, implementation_plan, backout_plan, test_plan concretely (see soft rules
   SR-002, SR-003). Template defaults are starting points, not final text.
5. If several servers are involved, list all of them in the description; cmdb_ci holds the first.

## 4. Validate and self-review

1. `validate_draft`. Fix every error and re-run until `passed=true`. Max 3 loops; if still failing,
   show the remaining violations to the user. Warnings: fix them unless the user already chose that
   value on purpose (e.g. a time outside the usual window they confirmed); then just mention it.
2. Walk through each soft rule and fix what does not comply.

## 5. Show and confirm

Present the draft as a table of fields plus the task timeline, and a short "sources" list: which fields
came from the CMDB, the template, history, or the user, and which are assumptions. Ask the user to confirm
or correct. Apply corrections and re-validate.

## 6. Create

Only when the user explicitly says to create: `create_change` with `confirmed=true`. Report the CHG number,
the link, and the task numbers. Remind the user that the change is in New state and still needs to be
submitted for approval by them.

## 7. Learn

If the user corrected something that looks like a general rule ("we always ...", "the boss wants ..."),
offer to record it with `add_hard_rule` / `add_soft_rule` (see the learn-rules skill).
