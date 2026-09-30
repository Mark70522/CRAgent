# cr-agent: Copilot instructions

This workspace is an agent for creating and reviewing ServiceNow change requests (CRs). The MCP server
`cr-agent` exposes the tools; the skills under `.github/skills/` define the workflows; `knowledge/` holds
the rules, templates and examples that encode what our approvers expect.

The same server also runs the daily task cockpit (skills `morning-brief`, `capture`, `evening-close`;
tools `add_tasks`, `get_day`, `plan_day`, `capture_note`, `close_day`, `save_knowledge`, `search_knowledge`).
Answers about the user's past come only from `search_knowledge` / `task_notes`, with the file named.

Ground rules, always:

- Never call `create_change` with `confirmed=true` until the user has seen the complete final draft and
  explicitly said to create it. Never submit a change for approval; humans do that in ServiceNow.
- Every fact in a draft comes from a source: the user's words, the server inventory (`lookup_ci` /
  `lookup_service`, backed by knowledge/inventory.xlsx), a template, a similar historical change, or the rules. If a fact is missing, ask or leave a clearly marked gap; do not invent
  patch numbers, ticket ids, durations or business confirmations.
- Read `read_rules` before drafting or reviewing. Hard rules are enforced by `validate_draft`; soft rules
  you check yourself.
- Reply in the language the user writes in. Field values sent to ServiceNow follow the language and
  conventions of the historical examples.
- Times are `yyyy-MM-dd HH:mm:ss` in the instance's user timezone.
