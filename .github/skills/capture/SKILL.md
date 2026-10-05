---
name: capture
description: Turn what the user pastes or says into stored tasks and notes. Use when the user pastes an email, meeting notes or a chat message and says 记成任务 / 整理成任务 / add these tasks, or says 记一笔 / 记下 / note this, or asks 上次…怎么做的 / what did I do last time, or reports a task's state (做完了 / 等审批 / CHG 号 / 花了多久).
---

# capture

Three jobs, all quick. Never make the user fill a form. Never invent a fact the text does not contain.

## A. Pasted text → tasks ("记成任务")

1. Read the text. Extract every actionable item: a verb-first title (≤ 20 characters in Chinese,
   ≤ 8 words in English), a due date if stated, priority (P1 only when the text says urgent / today /
   blocker; default P2).
2. **Whose tasks?** A team email or meeting note names several people. Items clearly assigned to someone
   else are not the user's: list them in one line and ask "这几条是你的吗?" before storing. Store only
   the user's own items (or all of them if the user says so).
3. Fill only what the text says:
   - `est` only if a duration is stated ("大概两小时"). Otherwise leave it empty. If you still want to
     suggest one, pass `estBy=ai` through `update_task` *after* telling the user it is a guess.
   - `scheduledAt` when the text says when the work *runs* (周日凌晨一点, the maintenance window,
     a release slot) - that is different from `due` (when it must be finished). "下周六打补丁" means
     the window of that weekend: ask which exact time if the inventory window does not settle it.
   - `cr` when a change number (CHG...) appears.
   - `repeat` = monthly / weekly / quarterly when the text says 每月 / 每周 / 每季度 or it is obviously
     periodic work (monthly OS patching).
4. Put the original wording and who asked into `context` - that is what the user will search for later.
5. `kind`: the sort of work, short and lowercase (os-patch, oracle-ru, cab-meeting, 周报). **Reuse a
   known kind** - `knownKinds` comes back with every add_tasks, and get_day.kinds lists the open ones;
   invent a new one only when nothing fits. Same kind = the review can learn from it. Unsure → leave
   it empty rather than guess.
6. `add_tasks` with `source` = email / meeting / chat / paste. The result says:
   - `mergedInto`: tell the user in one line each ("和 T-0012 是同一件事,已合并").
   - `related`: finished tasks that look like a new one. For each, one line from the data:
     "上次 T-0007(9 月)花了 2.5h,CHG0012,坑:02 要等 01 验证完". Nothing from general knowledge.
   - `history` (per kind): one line, from the data only - "这类事通常 1.5h,你以往预计偏低 50%;有标准做法".
     When it has `playbook: true`, offer `get_playbook` once ("要看标准做法吗?"). When the user stated
     no est, propose `typicalMinutes` (× estRatio if they always under-estimate) as an `estBy=ai` guess.
7. Reply with a compact list: id, title, kind, scheduledAt or due, est (mark "AI 估" if you guessed). Nothing else.

## B. Quick note ("记一笔")

`capture_note` with the text. Set `kind` when the note is a decision (决定), a pitfall (坑) or a
lesson (学到); leave it empty for plain notes. If the note is clearly about one of today's tasks,
pass its `taskId` so it lands in that task's notes too. Reply with three words, not a paragraph.

## C. Questions about the past ("上次 … 怎么做的")

1. `search_knowledge` with the key term; `task_history` with the keyword for "when / how long / which CR";
   if a task id is mentioned, `task_notes`.
2. Answer only from what came back, and name the file (`knowledge/oracle-ru.md`, `task-notes/T-0012.md`).
3. If nothing was found, say that; do not guess from general knowledge - the point of this
   assistant is that it remembers *the user's* history.

## D. Updating a task - the words people actually say

| User says | `update_task` |
|---|---|
| "T-0012 做完了" | status=done |
| "改成明天" / "推到下周" | due=... |
| "花了 50 分钟" | spent=50 |
| "等审批" / "等 DBA 回复" / "等窗口" | waitingOn="CAB 审批" (status becomes waiting; it leaves today's list until you say it moved) |
| "批了" / "回复了" / "可以做了" | status=doing (or todo) - waitingOn is cleared |
| "CR 是 CHG0012345" | cr=CHG0012345 |
| "周日凌晨 1 点执行" | scheduledAt=yyyy-MM-dd 01:00 |
| "这个每个月都有" | repeat=monthly |
| "这是补丁类的" / "归到 oracle-ru" | kind=os-patch / kind=oracle-ru |

Always echo what changed in one line. A task with `cr` is not done until the user says the CR is
closed too - ask once when they mark it done.
