import dayjs from 'dayjs'

export const TASK_TIME_FORMAT = 'YYYY-MM-DD HH:mm:ss'

/** order = 10, 20, 30 … in the current row order; text if the rows had text orders (templates do), else numbers. */
export function renumber(tasks) {
  const asText = tasks.some((t) => typeof t.order === 'string')
  return tasks.map((t, i) => ({ ...t, order: asText ? String((i + 1) * 10) : (i + 1) * 10 }))
}

/** Move the row at `from` to index `to` (splice semantics), then renumber. */
export function moveRow(tasks, from, to) {
  if (from == null || to == null || from === to) return tasks
  const n = [...tasks]
  const [x] = n.splice(from, 1)
  n.splice(to, 0, x)
  return renumber(n)
}

/**
 * Lay the tasks back to back from the first valid start, each keeping its own duration (30 min when it has
 * none). Returns null when no task has a start yet.
 */
export function reflowTimes(tasks, startKey, endKey, fmt = TASK_TIME_FORMAT) {
  // dayjs(undefined) is "now": an empty field must count as no time, not as the current moment
  const at = (v) => (v ? dayjs(v, fmt) : dayjs(NaN))
  const first = tasks.map((t) => at(t[startKey])).find((d) => d.isValid())
  if (!first) return null
  let cursor = first
  return tasks.map((t) => {
    const s = at(t[startKey]), e = at(t[endKey])
    const minutes = s.isValid() && e.isValid() && e.isAfter(s) ? e.diff(s, 'minute') : 30
    const next = { ...t, [startKey]: cursor.format(fmt), [endKey]: cursor.add(minutes, 'minute').format(fmt) }
    cursor = cursor.add(minutes, 'minute')
    return next
  })
}
