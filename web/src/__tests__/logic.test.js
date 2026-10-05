import { describe, expect, it } from 'vitest'
import { guessType, same, toPayload } from '../components/fieldTypes'
import { moveRow, reflowTimes, renumber } from '../components/tasksLogic'
import { placeInPlan, removeFromPlan, taskChanges } from '../features/cockpit/planLogic'
import { kindLine } from '../features/cockpit/cockpitApi'

describe('kindLine: what the history says about a kind, in one line', () => {
  it('shows time, estimate bias, recurrence', () => {
    expect(kindLine({ typicalMinutes: 90, estRatio: 1.5, ratioSamples: 3, everyDays: 30, done: 3 }))
      .toBe('通常 1.5h · 预计偏低 50%(3 次) · 约每 30 天一次 · 做过 3 次')
  })
  it('leaves out a bias that is small or from one sample', () => {
    expect(kindLine({ typicalMinutes: 30, estRatio: 1.1, ratioSamples: 5, done: 5 })).toBe('通常 30m · 做过 5 次')
    expect(kindLine({ estRatio: 2, ratioSamples: 1, done: 1 })).toBe('做过 1 次')
  })
})

describe('guessType: a field not in the catalog is shown by its value (same rules as the server)', () => {
  it.each([
    [{ value: 'abc', display_value: 'srv-01' }, 'reference'],
    [{ env: 'prod', tier: 2 }, 'object'],
    [{ owner: { name: 'x' } }, 'json'],
    [['a', 'b'], 'list'],
    [[{ no: 1, text: 'a' }], 'table'],
    [['a', { b: 1 }], 'json'],
    [true, 'boolean'],
    [3, 'number'],
    ['2026-10-05', 'date'],
    ['2026-10-05 01:30:00', 'datetime'],
    ['01:30', 'time'],
    ['ops@example.com', 'email'],
    ['https://wiki/x', 'url'],
    ['x'.repeat(130), 'textarea'],
    ['hello', 'text'],
  ])('%j -> %s', (v, t) => expect(guessType('k', v)).toBe(t))

  it('an empty value of a *_date field is a datetime', () => expect(guessType('planned_start_date', '')).toBe('datetime'))
})

describe('same: lists and objects compare by content', () => {
  it('works for structured values', () => {
    expect(same(['a', 'b'], ['a', 'b'])).toBe(true)
    expect(same(['a', 'b'], ['b', 'a'])).toBe(false)
    expect(same(undefined, '')).toBe(true)
  })
})

describe('toPayload: what goes to the interface', () => {
  const ref = { value: 'abc', display_value: 'srv-01' }
  it('sends a reference the way its catalog entry says (id by default)', () => {
    expect(toPayload([{ key: 'ci', type: 'reference' }], { ci: ref }).ci).toBe('abc')
    expect(toPayload([{ key: 'ci', type: 'reference', send: 'display_value' }], { ci: ref }).ci).toBe('srv-01')
    expect(toPayload([{ key: 'ci', type: 'reference', send: 'object' }], { ci: ref }).ci).toEqual(ref)
  })
  it('leaves everything else as it is, lists included', () => {
    expect(toPayload([], { servers: ['a'], n: 3 })).toEqual({ servers: ['a'], n: 3 })
  })
})

describe('tasks: order and times', () => {
  it('renumbers 10, 20, 30 and keeps text orders as text', () => {
    expect(renumber([{ order: '30' }, { order: '10' }, {}]).map((t) => t.order)).toEqual(['10', '20', '30'])
    expect(renumber([{ order: 5 }, {}]).map((t) => t.order)).toEqual([10, 20])
  })
  it('moving a row renumbers', () => {
    const r = moveRow([{ n: 'a', order: 10 }, { n: 'b', order: 20 }, { n: 'c', order: 30 }], 2, 0)
    expect(r.map((t) => `${t.n}${t.order}`)).toEqual(['c10', 'a20', 'b30'])
  })
  it('reflow lays tasks back to back, each keeping its duration', () => {
    const r = reflowTimes([
      { s: '2026-10-11 01:00:00', e: '2026-10-11 01:30:00' },
      { s: '2026-10-11 09:00:00', e: '2026-10-11 10:00:00' },
      {},
    ], 's', 'e')
    expect(r.map((t) => `${t.s.slice(11, 16)}-${t.e.slice(11, 16)}`)).toEqual(['01:00-01:30', '01:30-02:30', '02:30-03:00'])
  })
  it('reflow needs a start', () => expect(reflowTimes([{}, {}], 's', 'e')).toBeNull())
})

describe("today's plan", () => {
  it('drops into the plan, before a row or at the end, and reorders', () => {
    expect(placeInPlan(['A', 'B'], 'C', null)).toEqual(['A', 'B', 'C'])
    expect(placeInPlan(['A', 'B'], 'C', 'A')).toEqual(['C', 'A', 'B'])
    expect(placeInPlan(['A', 'B', 'C'], 'C', 'A')).toEqual(['C', 'A', 'B'])
    expect(removeFromPlan(['A', 'B'], 'A')).toEqual(['B'])
  })
  it('dropping a row on itself changes nothing', () => {
    const plan = ['A', 'B']
    expect(placeInPlan(plan, 'A', 'A')).toBe(plan)
  })
})

describe('task drawer: only what changed is sent', () => {
  const task = { id: 'T-1', title: 'x', est: 180, tags: ['a'], status: 'waiting', waitingOn: '审批' }
  it('sends changed fields as text, tags as a list, nothing else', () => {
    expect(taskChanges(task, { ...task, est: 120, tags: ['a', 'b'] })).toEqual({ est: '120', tags: ['a', 'b'] })
    expect(taskChanges(task, { ...task })).toEqual({})
  })
  it('puts waitingOn after status', () => {
    const f = taskChanges(task, { ...task, status: 'todo', waitingOn: '' })
    expect(Object.keys(f)).toEqual(['status', 'waitingOn'])
  })
})
