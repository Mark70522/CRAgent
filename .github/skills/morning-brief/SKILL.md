---
name: morning-brief
description: Start the day. When the user says 早安 / 开工 / good morning / start my day, read yesterday's leftovers, open tasks and relevant knowledge, propose the three things that matter today with reasons, lay out a timeline, and after confirmation write the plan with plan_day.
---

# morning-brief

Goal: in two minutes the user knows what today is for, and the plan is written down.

## 1. Read

1. `get_day` (today). It returns: today's file (may be empty), carried-over tasks, all open tasks, the
   previous day's file (summary, tomorrow list, notes), the last 7 days' stats and the knowledge topics.
2. For each candidate task that has a knowledge topic or task notes worth checking, `task_notes` /
   `search_knowledge` - only when it changes the advice (a past pitfall, a real duration).

## 2. Think

Pick at most **three** things that matter today, in order. Prefer: previous day's `tomorrow` list,
then carried-over P1, then due-soon, then P1 by priority. Everything else is "if time allows".

For each, write one line of *why* in the user's own history ("上次这台机器回退花了 40 分钟,不是 20")
- reasons come from the notes and knowledge, never invented. If there is nothing in the history, say so plainly.

Estimate the day: sum the est of the three; mention if it exceeds a realistic 5-6 working hours.

## 3. Show and confirm

Present:
- the three things, each with est, due and the one-line why
- carried-over tasks not in the three, one line each, with a suggestion (do / drop / re-date)
- a timeline draft: HH:mm slots from now, tasks back to back, a gap for interruptions

Ask the user to confirm or reorder. Do not write anything yet.

## 4. Write

After confirmation: `plan_day` with the task ids in order, `brief` = the text shown (3-5 sentences,
the three things and why), `timeline` = the slots. Tasks the user chose to drop: `update_task`
status=dropped. Re-dated ones: `update_task` due=....

Reply with one line: what was written and the cockpit page address from `get_day.cockpitUrl`.
