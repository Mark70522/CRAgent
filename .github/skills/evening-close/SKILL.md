---
name: evening-close
description: End the day. When the user says 收工 / 下班 / close the day / wrap up, tally what got done, pull the notes worth keeping out of today's log and save the ones the user confirms to the knowledge files, roll recurring work forward, propose tomorrow, and close the day with close_day.
---

# evening-close

Goal: nothing learned today is lost, and tomorrow starts pre-planned.

## 1. Tally

1. `get_day` (today). Check the plan tasks: which are done. Ask about any planned task with no
   status change ("T-0012 做了吗?") and `update_task` accordingly: done / still open / dropped /
   **waiting** (waitingOn=... when it is now blocked on approval, a reply or a window).
   For tasks done today without `spent`, ask once, all in one line: "今天做完的 T-0012、T-0015 各花了多久?
   (不记得就跳过)" - actual times are what makes estimates learnable. Never ask twice.
2. A task marked done that has a `cr`: ask once whether the change request is closed too. If not,
   keep the task open as waiting (waitingOn="关 CHG...") instead of done.
3. A task marked done that has `repeat`: propose the next occurrence (same title, next month / week /
   quarter as `due`, same `repeat`, context = "上一次 T-xxxx") and `add_tasks` it only if the user agrees.
4. Summarise in one sentence: done/planned, the most important thing finished, what slips.

## 2. Keep what matters

1. Go through today's notes (`get_day.day.notes`). Candidates are notes with a kind (decision /
   pitfall / learned / rule / fact / preference) and any plain note that clearly states a fact worth
   reusing. Notes with `auto=true` were picked out of conversations by you during the day: show them
   with their `source` so the user sees where each came from, and treat them exactly like the others -
   nothing is kept without a yes.
2. For each candidate propose: **topic** (an existing one from `knowledgeTopics` when it fits, else a
   new short name), **title** (one line) and **content** (1-5 sentences, the user's wording,
   plus the concrete number or step that makes it reusable). Kind decides where it goes:
   - `rule` → the learn-rules skill: `add_hard_rule` if checkable, else `add_soft_rule`; also
     `save_knowledge` under topic "审批要求" so the wording is kept.
   - `preference` → `save_knowledge` under topic "我的习惯"; create-cr and the other skills read it.
   - everything else → `save_knowledge` under the fitting topic.
3. Show the list; the user says which to keep. `save_knowledge` for each confirmed one with
   `noteIndex` so the note is marked saved. Do not save anything the user did not confirm; what they
   decline stays in the day log only and is never proposed again (it will be a duplicate).
4. If a pitfall changes how a template or rule should be (e.g. the os-patch task order), say so and
   offer to change `knowledge/templates/*.json` or add a rule via the learn-rules skill.
5. A kept pitfall or lesson belongs to a task with a `kind` that has a playbook: offer to add it to the
   playbook too (`get_playbook`, merge the one line into its "坑" or "步骤" section, `save_playbook` on yes).

## 3. Tomorrow

Propose tomorrow's first three: unfinished P1 today, then anything in `attention.scheduled` that runs
within two days (pre-checks), then due-soon, then the previous `tomorrow` list. Waiting tasks are not
candidates. Keep it to ids and titles; the morning brief will do the reasoning.

## 4. Close

`close_day` with the one-line summary and the tomorrow ids. Reply with: the summary, how many
notes were kept and into which topics, and tomorrow's first item. On a Friday (or the last workday of
the week) add one line: "要做本周复盘吗?" - yes runs the review skill. Then stop - no pep talk.
