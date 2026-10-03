---
name: change-ops
description: Day-to-day operations on an existing change request and its ICE record - add a task, cancel a task, close a task, read or update the ICE record, check the ICE score. Use when the user says things like 给 CHG… 加个 task / 取消 task / 关掉 task / 这张单的 ICE 分数 / 更新 ICE 的窗口.
---

# change-ops

Small, exact operations. Each one is a separate interface call (as the company wires them), each write
is confirmed once, and every result is kept locally so the React UI (http://127.0.0.1:7777/app/) shows it.

## Tasks of a change

1. `get_change` for the number: it returns the tasks the interface knows. Task ids come from the
   response (`task-id-field`, usually sys_id). Show them as a short numbered list: id, title, state.
2. **Add** ("加一个 task 做 X"): fields named as the task form expects - at least `short_description`,
   `order` (next after the existing ones), `assignment_group` (same as the change unless told),
   `planned_start_date` / `planned_end_date` inside the change window and after the previous task.
   Show the task, on the user's word `create_task` with `confirmed=true`. Report the id.
3. **Cancel** ("取消第 3 个 / 取消 TASK…"): resolve which task by order, title or id; repeat it back;
   `cancel_task` with `confirmed=true` and a `reason` field if the user gave one.
4. **Close** ("关掉 pre-check 那个"): same resolution; `close_task` with `confirmed=true` and
   `close_notes` when the user says what was done.
5. If the operation's endpoint is not configured the tool says so: tell the user which endpoint
   (`create-task` / `cancel-task` / `close-task`) is missing in cr-agent.yml and stop.

Never cancel or close more than one task per confirmation unless the user explicitly listed them.

## ICE of a change

- "这张单的 ICE": `get_change` returns `task.ice` or `iceId` when known; else ask for the ICE id.
  `get_ice` shows it; `ice_score` reads the score (needs `ice-score` configured).
- "ICE 窗口改成周日 2 点": show the field change, `update_ice` with `confirmed=true`, then `ice_score`
  and report whether the score moved.
- No ICE yet: `draft_ice` + `create_ice` as in create-cr.

## Reporting

One line per operation: what was done, the id, the new state or score. Then `remember` anything that
will still be true tomorrow (a task that always gets cancelled, a score threshold the boss wants).
