# Soft rules

Rules that need judgement rather than a regex. The review-cr skill checks every one of these
by reading the draft. When a rule becomes precise enough to code, move it to hard-rules.yaml.

Format per rule: id, the rule, why it exists, date. Add new ones at the end (add_soft_rule does this).

### SR-001
The description follows the numbered section layout of the template (object, content, impact, steps,
verification, backout). A reader who has never seen the system must understand what will happen and
what breaks if it fails.

_Why:_ Approvers reject one-line descriptions. (2026-09-25)

### SR-002
Impact must name the business application and the user-visible effect with a duration
("Order Portal checkout unavailable for about 45 minutes"), not just "some downtime".

_Why:_ CAB asks for this every time it is missing. (2026-09-25)

### SR-003
The backout plan states a concrete action (restore snapshot X, rollback patch Y, switch slot back)
and the time it takes. Saying "rollback if needed" is not a backout plan.

_Why:_ Rejected changes CHG0030004 style. (2026-09-25)

### SR-004
Task titles are verbs ("Apply Windows patches"), each task has one owner group, and the last task
is always a validation or closure step.

_Why:_ Matches how the approved history is written. (2026-09-25)

### SR-005
The usual window is Sunday 00:00-06:00 (HR-018 warns when a change is outside it). The user decides the
final time: if their requested time is outside the window, point it out once and ask them to confirm, then
keep their time. Do not move it silently and do not refuse. A server whose inventory row carries its own
window (e.g. dev boxes) uses that window as the reference instead.

_Why:_ The window floats around Sunday depending on the change; the team decides case by case. (2026-09-28)
