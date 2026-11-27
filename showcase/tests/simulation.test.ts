import assert from 'node:assert/strict';
import { test } from 'node:test';
import { initialState, transition } from '../src/simulation/model.ts';
import type { State } from '../src/simulation/model.ts';

const quantity = (state: State, sku = 'NOTEBOOK', value = 1) => transition(state, { type: 'quantity', sku, quantity: value });
const checkout = (state: State, key = 'demo-1') => transition(state, { type: 'checkout', key });
const ready = () => checkout(quantity(initialState()));
const payment = (outcome: string) => transition(ready(), { type: 'pay', outcome });
function domain(state: State) { return { stock: state.stock, carts: state.carts, cart: state.cart, orders: state.orders, selected: state.selected }; }

test('deterministic successful checkout snapshots lines and consumes stock once', () => {
  const before = quantity(initialState());
  const saved = structuredClone(before);
  const state = checkout(before);
  assert.deepEqual(before, saved);
  assert.deepEqual(state, checkout(quantity(initialState())));
  assert.equal(state.stock.NOTEBOOK, 4);
  assert.equal(state.orders[0].total, 1200);
  assert.equal(state.orders[0].status, 'PENDING');
  assert.equal(state.orders[0].reservation, 'ACTIVE');
});
test('insufficient multi-line checkout changes nothing; rejected key remains reusable', () => {
  const before = quantity(quantity(initialState()), 'MUG', 4);
  const failed = checkout(before);
  assert.equal(failed.error, true);
  assert.deepEqual(domain(failed), domain(before));
  const accepted = checkout(quantity(failed, 'MUG', 2));
  assert.equal(accepted.orders.length, 1);
  assert.equal(accepted.stock.NOTEBOOK, 4);
  assert.equal(accepted.stock.MUG, 1);
});
test('same cart/key replays original despite edited cart; conflicting cart rejected', () => {
  const before = ready();
  const retry = checkout(quantity(before, 'NOTEBOOK', 20));
  assert.equal(retry.orders.length, 1);
  assert.deepEqual(retry.orders, before.orders);
  assert.deepEqual(retry.stock, before.stock);
  const otherCart = transition(retry, { type: 'cart', id: 'cart-b' });
  const conflict = checkout(otherCart);
  assert.equal(conflict.error, true);
  assert.deepEqual(domain(conflict), domain(otherCart));
});
test('authorized payment and separate workflow confirm without decrementing again', () => {
  const observed = payment('authorized');
  assert.equal(observed.orders[0].status, 'PENDING');
  const applied = transition(observed, { type: 'workflow' });
  assert.equal(applied.orders[0].status, 'CONFIRMED');
  assert.equal(applied.orders[0].reservation, 'CONFIRMED');
  assert.equal(applied.stock.NOTEBOOK, 4);
  assert.deepEqual(domain(transition(applied, { type: 'workflow' })), domain(applied));
});
test('decline maps to FAILED, workflow cancels and releases stock only once', () => {
  const observed = payment('declined');
  assert.equal(observed.orders[0].payment, 'FAILED');
  assert.equal(observed.stock.NOTEBOOK, 4);
  const applied = transition(observed, { type: 'workflow' });
  assert.equal(applied.orders[0].status, 'CANCELLED');
  assert.equal(applied.orders[0].reservation, 'RELEASED');
  assert.equal(applied.stock.NOTEBOOK, 5);
  assert.deepEqual(domain(transition(applied, { type: 'workflow' })), domain(applied));
});
test('lost response stays UNKNOWN; explicit lookup never repeats authorization', () => {
  const unknown = payment('lost');
  assert.equal(unknown.orders[0].payment, 'UNKNOWN');
  assert.equal(unknown.orders[0].status, 'PENDING');
  assert.equal(transition(unknown, { type: 'pay', outcome: 'authorized' }).error, true);
  assert.equal(transition(unknown, { type: 'workflow' }).error, true);
  const lookedUp = transition(unknown, { type: 'reconcile' });
  assert.equal(lookedUp.orders[0].authorizations, 1);
  assert.equal(lookedUp.orders[0].lookups, 1);
  assert.equal(lookedUp.orders[0].payment, 'AUTHORIZED');
  assert.equal(lookedUp.orders[0].review, 'RESOLVED');
  assert.equal(lookedUp.orders[0].status, 'PENDING');
  assert.equal(transition(lookedUp, { type: 'workflow' }).orders[0].status, 'CONFIRMED');
});
test('expiry before lookup requires manual review and does not re-reserve or confirm', () => {
  const expired = transition(payment('lost'), { type: 'expire' });
  assert.equal(expired.stock.NOTEBOOK, 5);
  assert.equal(transition(expired, { type: 'expire' }).error, true);
  const reviewed = transition(expired, { type: 'reconcile' });
  assert.equal(reviewed.orders[0].review, 'REQUIRES_REVIEW');
  assert.equal(reviewed.orders[0].payment, 'AUTHORIZED');
  assert.equal(reviewed.orders[0].reservation, 'EXPIRED');
  assert.equal(reviewed.orders[0].status, 'PENDING');
  const workflow = transition(reviewed, { type: 'workflow' });
  assert.equal(workflow.orders[0].status, 'PENDING');
  assert.equal(workflow.stock.NOTEBOOK, 5);
});
test('reset restores independent initial state including both carts and keys', () => {
  assert.deepEqual(transition(payment('lost'), { type: 'reset' }), initialState());
  const one = initialState(); one.carts['cart-a'].PEN = 5;
  assert.equal(initialState().carts['cart-a'].PEN, 0);
});
test('invalid quantities, keys, identities and malformed actions fail without domain changes', () => {
  const before = ready();
  for (const action of [null, 42, {}, { type: 'unknown' }, { type: 'quantity', sku: 'MISSING', quantity: 1 },
    ...[-1, 21, 1.5, NaN, Infinity, '2', null].map(quantity => ({ type: 'quantity', sku: 'PEN', quantity })),
    ...['', 'a'.repeat(65), '<script>', 'a b', null].map(key => ({ type: 'checkout', key })),
    { type: 'cart', id: 'cart-c' }, { type: 'selectOrder', id: 'missing' }, { type: 'pay', outcome: 'missing' },
    { type: 'reconcile' }, { type: 'expire' }, { type: 'workflow' }]) {
    const result = transition(before, action);
    assert.equal(result.error, true, JSON.stringify(action));
    assert.deepEqual(domain(result), domain(before));
  }
  assert.equal(checkout(initialState()).error, true);
  assert.equal(transition(initialState(), { type: 'pay', outcome: 'authorized' }).error, true);
});
test('zero removes a line; stock and payment remain scoped to selected order', () => {
  assert.equal(checkout(quantity(quantity(initialState()), 'NOTEBOOK', 0)).error, true);
  let state = ready();
  state = checkout(state, 'second');
  state = transition(state, { type: 'pay', outcome: 'declined' });
  assert.equal(state.orders[0].payment, 'NOT_STARTED');
  assert.equal(state.orders[1].payment, 'FAILED');
  state = transition(state, { type: 'selectOrder', id: 'SIM-ORDER-1' });
  assert.equal(state.selected, 'SIM-ORDER-1');
});
test('identifier growth is bounded to twenty orders until reset', () => {
  let state = quantity(initialState());
  for (let i = 0; i < 20; i++) {
    state = checkout(state, `key-${i}`);
    state = transition(state, { type: 'pay', outcome: 'declined' });
    state = transition(state, { type: 'workflow' });
  }
  assert.equal(state.orders.length, 20);
  const rejected = checkout(state, 'over-limit');
  assert.equal(rejected.error, true);
  assert.deepEqual(domain(rejected), domain(state));
});
