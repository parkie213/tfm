import assert from 'node:assert/strict'
import test from 'node:test'
import { trainStatus } from '../src/lib/trainStatus.ts'

test('Darwin Delayed remains late when no positive numeric delay is supplied', () => {
  for (const status of ['Delayed', 'delayed', 'DELAYED']) {
    for (const delayMinutes of [0, -1]) {
      assert.deepEqual(trainStatus({ status, delayMinutes }), { text: 'Delayed', tone: 'late' })
    }
  }
})

test('a known positive delay still shows its duration', () => {
  assert.deepEqual(trainStatus({ status: 'Delayed', delayMinutes: 7 }), {
    text: '7 min late', tone: 'late',
  })
})

test('cancellation takes priority over numeric delay', () => {
  for (const delayMinutes of [0, 7]) {
    assert.deepEqual(trainStatus({ status: 'Cancelled', delayMinutes }), {
      text: 'Cancelled', tone: 'cancelled',
    })
  }
})

test('an on-time service keeps its existing display', () => {
  assert.deepEqual(trainStatus({ status: 'On time', delayMinutes: 0 }), {
    text: 'On time', tone: 'on',
  })
})
