---
name: capture
description: Turn what the user pastes or says into stored tasks and notes. Use when the user pastes an email, meeting notes or a chat message and says 记成任务 / 整理成任务 / add these tasks, or says 记一笔 / 记下 / note this, or asks 上次…怎么做的 / what did I do last time.
---

# capture

Three jobs, all quick. Never make the user fill a form.

## A. Pasted text → tasks ("记成任务")

1. Read the text. Extract every actionable item: a verb-first title (≤ 20 characters in Chinese,
   ≤ 8 words in English), an estimate in minutes if you can infer one, a due date if stated,
   priority (P1 only when the text says urgent / today / blocker; default P2).
2. Put the original wording and who asked into `context` - that is what the user will search for later.
3. `add_tasks` with `source` = email / meeting / chat / paste. The result says which were merged into
   existing tasks: tell the user in one line each ("和 T-0012 是同一件事,已合并").
4. Reply with a compact list: id, title, est, due. Nothing else.

## B. Quick note ("记一笔")

`capture_note` with the text. Set `kind` when the note is a decision (决定), a pitfall (坑) or a
lesson (学到); leave it empty for plain notes. If the note is clearly about one of today's tasks,
pass its `taskId` so it lands in that task's notes too. Reply with three words, not a paragraph.

## C. Questions about the past ("上次 … 怎么做的")

1. `search_knowledge` with the key term; if a task id is mentioned, `task_notes`.
2. Answer only from what came back, and name the file (`knowledge/oracle-ru.md`, `task-notes/T-0012.md`).
3. If nothing was found, say that; do not guess from general knowledge - the point of this
   assistant is that it remembers *the user's* history.

## Updating a task

"T-0012 做完了" → `update_task` status=done. "改成明天" → due. "花了 50 分钟" → spent=50.
Always echo what changed in one line.
