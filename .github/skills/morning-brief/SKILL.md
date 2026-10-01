---
name: morning-brief
description: Start the day. When the user says 早 / 早安 / 早上好 / 开工 / morning / good morning / start my day (a bare 早 is enough), open the cockpit page, read yesterday's leftovers, open tasks, what is waiting or scheduled, and relevant knowledge; propose the three things that matter today with reasons, lay out a timeline, and after confirmation write the plan with plan_day.
---

# morning-brief

Goal: in two minutes the user knows what today is for, what is merely being watched, and the plan is written down.
A single word from the user ("早") is the whole trigger: do not ask what they mean, start.

## 1. Read

0. `open_cockpit` (view morning) so the page is on screen while you work. Once; never again in the same brief.
1. `get_day` (today). It returns: today's file (may be empty), carried-over tasks, all open tasks,
   `attention` (waiting / scheduled / overdueRun / withChange), the previous day's file (summary,
   tomorrow list, notes), the last 7 days' stats and the knowledge topics.
2. For each candidate task that has a knowledge topic or task notes worth checking, `task_notes` /
   `search_knowledge` - only when it changes the advice (a past pitfall, a real duration).

## 2. Think

**Today's three** - at most three things to *do* today, in order. Candidates are actionable tasks only
(status todo / doing). Prefer: previous day's `tomorrow` list, then carried-over P1, then due-soon,
then P1 by priority. Everything else is "if time allows".

For each, write one line of *why* in the user's own history ("上次这台机器回退花了 40 分钟,不是 20")
- reasons come from the notes and knowledge, never invented. If there is nothing in the history, say so plainly.

Estimate the day: sum the est of the three (an `estBy=ai` estimate is a guess; say so); mention if it
exceeds a realistic 5-6 working hours.

**Watch list** - from `attention`, not part of the three, never nagged as "to do":
- `overdueRun`: the scheduled time has passed and the task is still open. Ask: did it run? If yes →
  done (and is the CR closed?). If it slipped → new `scheduledAt`.
- `scheduled` within 3 days: a pre-check line - is the CR created (`cr` empty → offer create-cr),
  approved (ask; the user knows), is the window confirmed. A run tomorrow night is a today item.
- `waiting`: one line each with what it waits for. Ask only if it has waited more than a week.
- `withChange` otherwise: just list number + title.

## 3. Show and confirm

Present:
- the three things, each with est, due / scheduledAt and the one-line why
- the watch list (short; skip the heading if empty)
- carried-over tasks not in the three, one line each, with a suggestion (do / drop / re-date / waiting)
- a timeline draft: HH:mm slots from now, tasks back to back, a gap for interruptions

Ask the user to confirm or reorder. Do not write anything yet.

## 4. Write

After confirmation: `plan_day` with the task ids in order, `brief` = the text shown (3-5 sentences,
the three things and why, plus one sentence on the watch list), `timeline` = the slots. Tasks the user
chose to drop: `update_task` status=dropped. Re-dated: due=... . Now waiting on something: waitingOn=... .

Reply with one line: what was written and the cockpit page address from `get_day.cockpitUrl`.
