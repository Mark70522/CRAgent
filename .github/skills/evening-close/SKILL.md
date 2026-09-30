---
name: evening-close
description: End the day. When the user says 收工 / 下班 / close the day / wrap up, tally what got done, pull the notes worth keeping out of today's log and save the ones the user confirms to the knowledge files, propose tomorrow, and close the day with close_day.
---

# evening-close

Goal: nothing learned today is lost, and tomorrow starts pre-planned.

## 1. Tally

1. `get_day` (today). Check the plan tasks: which are done. Ask about any planned task with no
   status change ("T-0012 做了吗?") and `update_task` accordingly (done / still open / dropped).
   Ask for actual time only if the user mentions it; never nag.
2. Summarise in one sentence: done/planned, the most important thing finished, what slips.

## 2. Keep what matters

1. Go through today's notes (`get_day.day.notes`). Candidates are notes with a kind (decision /
   pitfall / learned) and any plain note that clearly states a fact worth reusing.
2. For each candidate propose: **topic** (an existing one from `knowledgeTopics` when it fits, else a
   new short name), **title** (one line) and **content** (1-5 sentences, the user's wording,
   plus the concrete number or step that makes it reusable).
3. Show the list; the user says which to keep. `save_knowledge` for each confirmed one with
   `noteIndex` so the note is marked saved. Do not save anything the user did not confirm.
4. If a pitfall changes how a template or rule should be (e.g. the os-patch task order), say so and
   offer to change `knowledge/templates/*.yaml` or add a rule via the learn-rules skill.

## 3. Tomorrow

Propose tomorrow's first three: unfinished P1 today, then due-soon, then the previous
`tomorrow` list. Keep it to ids and titles; the morning brief will do the reasoning.

## 4. Close

`close_day` with the one-line summary and the tomorrow ids. Reply with: the summary, how many
notes were kept and into which topics, and tomorrow's first item. Then stop - no pep talk.
