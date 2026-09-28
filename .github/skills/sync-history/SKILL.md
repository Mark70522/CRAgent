---
name: sync-history
description: Pull historical change requests from ServiceNow into the local history store and report what is there. Use when the user asks to sync / refresh / load history, or when find_similar_changes returns nothing.
---

# sync-history

1. `sync_history` with the date the user gives; with no date it continues from the last sync. First
   run: use a date 12 to 24 months back and a limit of 500, repeat until `fetched` is 0.
2. Report: records fetched this run, total in store, latest updated-on.
3. Sanity check with `history_field_stats` on `type` and `close_code`: if most records are not closed
   successful, tell the user the store may need a different state filter (see HistoryTools.syncHistory).
