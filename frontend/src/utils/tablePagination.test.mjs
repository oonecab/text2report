import test from 'node:test';
import assert from 'node:assert/strict';

import { buildCompactTablePagination, shouldEnableQuickJumper } from './tablePagination.ts';

test('enables quick jumper only when page count exceeds compact threshold', () => {
  // 7 pages stays compact; the quick jumper only appears after crossing the threshold.
  assert.equal(shouldEnableQuickJumper(70, 10), false);
  assert.equal(shouldEnableQuickJumper(71, 10), true);
  assert.equal(shouldEnableQuickJumper(7, 1), false);
  assert.equal(shouldEnableQuickJumper(8, 1), true);
  assert.equal(shouldEnableQuickJumper(0, 10), false);
  assert.equal(shouldEnableQuickJumper(-1, 10), false);
  assert.equal(shouldEnableQuickJumper(100, 0), false);
  assert.equal(shouldEnableQuickJumper(100, -5), false);
});

test('builds compact pagination config that stays bounded for large totals', () => {
  const onChange = () => {};
  const pagination = buildCompactTablePagination({
    current: 12,
    pageSize: 10,
    total: 5000,
    onChange,
    showTotal: (total) => `共 ${total} 条`,
  });

  assert.equal(pagination.current, 12);
  assert.equal(pagination.pageSize, 10);
  assert.equal(pagination.total, 5000);
  assert.equal(pagination.showLessItems, true);
  assert.equal(pagination.responsive, true);
  assert.equal(pagination.showQuickJumper, true);
  assert.equal(pagination.hideOnSinglePage, false);
  assert.equal(pagination.onChange, onChange);
  assert.deepEqual(pagination.pageSizeOptions, ['10', '20', '50', '100']);
  assert.equal(pagination.showTotal(5000), '共 5000 条');
});

test('keeps pagination visible for empty server-side results so total count is still shown', () => {
  const pagination = buildCompactTablePagination({
    current: 1,
    pageSize: 10,
    total: 0,
    onChange: () => {},
    showTotal: (total) => `共 ${total} 条`,
  });

  assert.equal(pagination.hideOnSinglePage, false);
  assert.equal(pagination.showQuickJumper, false);
  assert.equal(pagination.showTotal?.(0), '共 0 条');
});

test('hides pagination when only one non-empty page is available', () => {
  const pagination = buildCompactTablePagination({
    current: 1,
    pageSize: 10,
    total: 8,
    onChange: () => {},
    showTotal: (total) => `共 ${total} 条`,
  });

  assert.equal(pagination.hideOnSinglePage, true);
  assert.equal(pagination.showQuickJumper, false);
});

test('normalizes invalid totals and supports omitted showTotal', () => {
  const pagination = buildCompactTablePagination({
    current: 1,
    pageSize: 10,
    total: -8,
    onChange: () => {},
  });

  assert.equal(pagination.total, 0);
  assert.equal(pagination.hideOnSinglePage, false);
  assert.equal(pagination.showTotal, undefined);
});
