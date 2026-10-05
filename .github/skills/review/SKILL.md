---
name: review
description: Look back and learn. When the user says 复盘 / 周报 / 总结一下这周 / 这个月做了什么 / review / retro (or says yes to "要做本周复盘吗?" at the Friday close), summarise the period from the data, then turn what repeats into lasting knowledge - playbooks per kind of work, calibrated estimates, recurring tasks, rules - each change only after the user agrees.
---

# review

Goal: what the user did becomes what the agent knows. Scattered notes become one playbook per kind of
work; estimates get calibrated from actual times; work that comes back gets scheduled; pitfalls that
repeat become rules. Every number comes from the `review` tool - never estimate or invent one.

## 1. Read

1. `review` with the period the user means (default: the last 7 days; "这个月" = from the 1st; "上周" =
   last Monday to Sunday). It returns the totals, `kinds` (per kind of work), `stuck`, `missingSpent`,
   `unclassified` and `suggestions`.
2. For each kind you will talk about that has a playbook, `get_playbook` (only those).

## 2. Show (short)

- One paragraph: done / created / dropped, plan completion %, "实际用时是预计的 X 倍" when `estRatio` is
  known. Name the 2-3 kinds that took the most of the period.
- Per kind that had work in the period, one line: typical time, estimate bias, "约每 N 天一次,下次 …",
  number of pitfalls. Skip kinds with nothing new.
- Stuck tasks, one line each with `why`, asking: 催 / 改期 / 放弃?

## 3. Fill the gaps first (they make everything else work)

- `missingSpent`: "这几个各花了多久?(不记得就跳过)" → `update_task` spent for the ones answered.
- `unclassified`: propose a kind for each from its title, reusing kinds that already exist in `kinds`
  ("T-0021 整理周报 → 周报?"). Apply with `update_task` kind=… after the user confirms the list.
- After filling, call `review` again if something changed, so the numbers below are right.

## 4. Go through the suggestions, one at a time

Ask about each, apply only on yes:

| type | what to do on yes |
|---|---|
| `estimate` | Remember it: `remember` kind=preference "「os-patch」预计按 1.5 倍估" - capture and the morning brief then apply it. |
| `repeat` | Create the next occurrence: `add_tasks` with the kind, `repeat` (weekly / monthly / quarterly, the closest to `everyDays`) and `due` = `nextExpected`. |
| `playbook` | Draft the playbook (section 5), show it, `save_playbook` on yes. |
| `playbook-update` | `get_playbook`, merge the new pitfalls / knowledge into it (section 5), show the diff in a few lines, `save_playbook` on yes. |
| `pitfalls` | A pitfall that came back twice or touches a CR: offer a hard rule (learn-rules skill) or a fixed task step in the template (`knowledge/templates/<kind>.json`). |
| `stuck` | Per task: `update_task` (waitingOn, due, or status=dropped) as the user decides. |

## 5. Writing a playbook

One file per kind, markdown, written for the user's future self. Sections, in this order, each short:

```
# <kind>
## 什么时候做        trigger / frequency ("每月第二个周日窗口", from everyDays and the tasks' dates)
## 步骤              numbered, concrete, in the order they were really done (from task notes, CR tasks)
## 用时              typical minutes from the data; "以往预计偏低 X%" when estRatio says so
## 检查 / 验证        how the user confirmed it worked
## 坑                every pitfall from `kinds[].pitfalls` and the knowledge entries, one line each,
                     with the fix; newest first; drop exact duplicates
## 相关              template name, CR numbers, knowledge topics, people / teams
```

Sources, only these: the review data, `task_notes` / `task_history` of the kind's tasks, the knowledge
entries listed under the kind (`read_knowledge`), the CR template. When updating, **merge**: keep every
step and pitfall that is still true, fold in the new ones, remove what the user says is outdated - never
drop content silently. Nothing from general knowledge; a gap stays a gap ("验证:待补").

## 6. Close

`capture_note` one line: "复盘 <period>: <what was decided>". Reply with what was written (playbooks,
rules, recurring tasks, kinds filled in) in at most five lines. The page 复盘 (cockpitUrl + "app/review")
shows the same numbers.
