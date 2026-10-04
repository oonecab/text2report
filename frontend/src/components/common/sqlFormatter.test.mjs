import test from 'node:test';
import assert from 'node:assert/strict';

import { buildCompactSqlPreview, formatSqlForDisplay } from './sqlFormatter.js';

test('keeps SQL string literals unchanged while formatting keywords outside literals', () => {
  const sql = "SELECT 'ORDER BY status AND region' AS label, total FROM fact_sales WHERE region = 'North AND South' ORDER BY total DESC";

  const formatted = formatSqlForDisplay(sql);

  assert.match(formatted, /'ORDER BY status AND region'/);
  assert.match(formatted, /'North AND South'/);
  assert.match(formatted, /\nORDER BY total DESC$/);
});

test('keeps line comments unchanged while formatting the statement body', () => {
  const sql = "SELECT category -- ORDER BY should stay in comment\nFROM dim_product WHERE is_active = TRUE";

  const formatted = formatSqlForDisplay(sql);

  assert.match(formatted, /-- ORDER BY should stay in comment\nFROM dim_product/);
  assert.doesNotMatch(formatted, /--\nORDER BY should stay in comment/);
});

test('keeps block comments unchanged while formatting surrounding SQL', () => {
  const sql = "SELECT revenue /* AND ORDER BY stay untouched */ FROM fact_profit WHERE org_id = 1 AND revenue > 0";

  const formatted = formatSqlForDisplay(sql);

  assert.match(formatted, /\/\* AND ORDER BY stay untouched \*\//);
  assert.match(formatted, /\nWHERE org_id = 1\n    AND revenue > 0$/);
});

test('inline preview truncates formatted SQL without altering protected segments', () => {
  const sql = "SELECT 'ORDER BY' AS keyword_label, total_amount FROM fact_profit ORDER BY total_amount DESC";

  const preview = buildCompactSqlPreview(sql, 32);

  assert.equal(preview, "SELECT 'ORDER BY' AS keyword_lab...");
});

