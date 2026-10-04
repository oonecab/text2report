import { http } from 'msw';
import { ok, bizError, httpError, okPage } from './utils';
import {
  META_TABLES,
  META_COLUMNS,
  META_GLOSSARY,
  META_JOINS,
  META_EXAMPLES,
} from '../data/metadataData';
import { nowInBeijing } from '@/utils/formatters';

// In-memory storage for mutations
let tables = [...META_TABLES];
let columns = [...META_COLUMNS];
let glossary = [...META_GLOSSARY];
let joins = [...META_JOINS];
let examples = [...META_EXAMPLES];

let glossaryIdCounter = 100;
let joinIdCounter = 100;
let exampleIdCounter = 100;

export const metadataHandlers = [
  // ============ Tables ============
  http.get('/api/v1/metadata/tables', async () => {
    return ok(tables);
  }),

  http.get('/api/v1/metadata/tables/:id', async ({ params }) => {
    const table = tables.find(t => t.id === Number(params.id));
    if (!table) {
      return httpError(404, 'TABLE_NOT_FOUND', '表不存在');
    }
    return ok(table);
  }),

  http.put('/api/v1/metadata/tables/:id', async ({ params, request }) => {
    const body = await request.json() as Partial<typeof META_TABLES[0]>;
    const table = tables.find(t => t.id === Number(params.id));
    if (!table) {
      return httpError(404, 'TABLE_NOT_FOUND', '表不存在');
    }
    Object.assign(table, body);
    return ok(table);
  }),

  http.get('/api/v1/metadata/tables/:id/columns', async ({ params }) => {
    const tableId = Number(params.id);
    const tableColumns = columns.filter(c => c.tableId === tableId);
    return ok(tableColumns);
  }),

  // ============ Columns ============
  http.put('/api/v1/metadata/columns/:id', async ({ params, request }) => {
    const body = await request.json() as Partial<typeof META_COLUMNS[0]>;
    const col = columns.find(c => c.id === Number(params.id));
    if (!col) {
      return httpError(404, 'COLUMN_NOT_FOUND', '字段不存在');
    }
    Object.assign(col, body, { commentSource: 'manual' });
    return ok(col);
  }),

  // ============ DDL Parse ============
  http.post('/api/v1/metadata/ddl/parse', async ({ request }) => {
    const body = await request.json() as { rawDdl: string; ddl?: string };
    const ddl = body.rawDdl ?? body.ddl ?? '';

    if (!ddl.trim().toUpperCase().startsWith('CREATE')) {
      return bizError('DDL_PARSE_ERROR', 'DDL 语法错误：缺少 CREATE TABLE 声明');
    }

    // Parse table name - support schema.table or just table
    const tableMatch = ddl.match(/CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:\[?(\w+)\]?\s*\.\s*)?\[?(\w+)\]?\s*\(/i);
    const tableName = tableMatch?.[2] || tableMatch?.[1] || 'unknown_table';

    // Parse columns from DDL
    const columns: Array<{
      columnName: string;
      dataType: string;
      description?: string;
      commentSource: 'ddl' | 'manual';
    }> = [];

    // Extract content between parentheses
    const parensMatch = ddl.match(/\(\s*([\s\S]+?)\s*\)\s*;?\s*$/);
    if (parensMatch) {
      const content = parensMatch[1];

      // Split by comma, but be careful of:
      // 1. Nested parentheses in type definitions like DECIMAL(18,2)
      // 2. Commas inside comments like -- 账期月份，格式YYYYMM
      const columnDefs: string[] = [];
      let depth = 0;
      let inComment = false;
      let current = '';
      for (let i = 0; i < content.length; i++) {
        const char = content[i];
        const nextTwo = content.substring(i, i + 2);

        // Check for comment start
        if (nextTwo === '--') {
          inComment = true;
        }
        // Check for comment end (newline)
        else if (inComment && char === '\n') {
          inComment = false;
        }
        // Track parentheses depth (only outside comments)
        else if (!inComment) {
          if (char === '(') depth++;
          else if (char === ')') depth--;
        }

        // Split on comma only if:
        // - Not inside parentheses
        // - Not inside a comment
        if (char === ',' && depth === 0 && !inComment) {
          columnDefs.push(current.trim());
          current = '';
          continue;
        }
        current += char;
      }
      if (current.trim()) columnDefs.push(current.trim());

      // Parse each column definition
      for (const colDef of columnDefs) {
        const trimmed = colDef.trim();

        // Skip standalone constraints (lines starting with PRIMARY KEY, FOREIGN KEY, etc.)
        // But NOT column definitions like "id INT PRIMARY KEY"
        if (/^(PRIMARY\s+KEY|FOREIGN\s+KEY|CONSTRAINT|UNIQUE|INDEX|CHECK)\s*\(/i.test(trimmed) ||
            (/^(PRIMARY\s+KEY|FOREIGN\s+KEY|CONSTRAINT|UNIQUE|INDEX|CHECK)\b/i.test(trimmed) &&
             !/^\w+\s+\w+/i.test(trimmed))) {
          continue;
        }

        // Parse column: name type [constraints] [-- comment]
        // Match pattern: column_name data_type [(size[,...])] [constraints] [-- comment]
        // Support types like: INT, VARCHAR(6), DECIMAL(18,2), DATETIME DEFAULT GETDATE()
        const colMatch = trimmed.match(/^\s*(\w+)\s+([A-Za-z0-9_]+(?:\s*\([^)]+\))?)/i);
        if (colMatch) {
          const columnName = colMatch[1];
          let dataType = colMatch[2].trim();

          // Clean up data type - remove extra spaces but keep parentheses content
          dataType = dataType.replace(/\s+/g, ' ').trim();

          // Extract line comment if exists
          const commentMatch = trimmed.match(/--\s*(.+)$/);
          const description = commentMatch ? commentMatch[1].trim() : undefined;

          columns.push({
            columnName,
            dataType,
            description,
            commentSource: description ? 'ddl' : 'manual',
          });
        }
      }
    }

    const result = {
      tableName,
      schemaName: 'dbo',
      columns: columns.length > 0 ? columns : [
        { columnName: 'id', dataType: 'INT', description: '主键ID', commentSource: 'ddl' as const },
      ],
    };

    return ok(result, 800);
  }),

  http.post('/api/v1/metadata/ddl/commit', async () => {
    // In real implementation, would save to database
    return ok({ success: true, tablesCreated: 1 });
  }),

  http.post('/api/v1/metadata/ddl/confirm', async () => {
    return ok({ success: true, tablesCreated: 1 });
  }),

  http.get('/api/v1/metadata/ddl/uploads', async () => {
    return ok([
      { id: 1, filename: 'gross_profit_detail.sql', uploadedAt: '2026-04-01T10:00:00Z', status: 'success' },
    ]);
  }),

  // ============ Glossary ============
  http.get('/api/v1/metadata/glossary', async ({ request }) => {
    const url = new URL(request.url);
    const keyword = url.searchParams.get('keyword') ?? '';
    const page = Number(url.searchParams.get('page') ?? 1);
    const pageSize = Number(url.searchParams.get('pageSize') ?? 10);

    let filtered = glossary;
    if (keyword) {
      filtered = glossary.filter(g =>
        g.term.includes(keyword) ||
        (g.definition?.includes(keyword) ?? false) ||
        (g.aliases ?? []).some((a: string) => a.includes(keyword))
      );
    }

    return okPage(filtered, page, pageSize);
  }),

  http.post('/api/v1/metadata/glossary', async ({ request }) => {
    const body = await request.json() as Partial<typeof META_GLOSSARY[0]>;
    const newItem = {
      id: glossaryIdCounter++,
      term: body.term || '',
      definition: body.definition || '',
      sqlSnippet: body.sqlSnippet || '',
      aliases: body.aliases || [],
      orgScope: body.orgScope,
      updatedAt: nowInBeijing(),
    };
    glossary.push(newItem as typeof META_GLOSSARY[0]);
    return ok(newItem);
  }),

  http.put('/api/v1/metadata/glossary/:id', async ({ params, request }) => {
    const body = await request.json() as Partial<typeof META_GLOSSARY[0]>;
    const item = glossary.find(g => g.id === Number(params.id));
    if (!item) {
      return httpError(404, 'GLOSSARY_NOT_FOUND', '术语不存在');
    }
    Object.assign(item, body, { updatedAt: nowInBeijing() });
    return ok(item);
  }),

  http.delete('/api/v1/metadata/glossary/:id', async ({ params }) => {
    const index = glossary.findIndex(g => g.id === Number(params.id));
    if (index === -1) {
      return httpError(404, 'GLOSSARY_NOT_FOUND', '术语不存在');
    }
    glossary.splice(index, 1);
    return ok(null);
  }),

  // ============ Joins ============
  http.get('/api/v1/metadata/joins', async () => {
    return ok(joins);
  }),

  http.post('/api/v1/metadata/joins', async ({ request }) => {
    const body = await request.json() as Partial<typeof META_JOINS[0]>;
    const newItem = {
      id: joinIdCounter++,
      fromTable: body.fromTable || '',
      fromColumn: body.fromColumn || '',
      toTable: body.toTable || '',
      toColumn: body.toColumn || '',
      joinType: body.joinType || 'LEFT',
      description: body.description || '',
    };
    joins.push(newItem as typeof META_JOINS[0]);
    return ok(newItem);
  }),

  http.put('/api/v1/metadata/joins/:id', async ({ params, request }) => {
    const body = await request.json() as Partial<typeof META_JOINS[0]>;
    const item = joins.find(j => j.id === Number(params.id));
    if (!item) {
      return httpError(404, 'JOIN_NOT_FOUND', 'JOIN关系不存在');
    }
    Object.assign(item, body);
    return ok(item);
  }),

  http.delete('/api/v1/metadata/joins/:id', async ({ params }) => {
    const index = joins.findIndex(j => j.id === Number(params.id));
    if (index === -1) {
      return httpError(404, 'JOIN_NOT_FOUND', 'JOIN关系不存在');
    }
    joins.splice(index, 1);
    return ok(null);
  }),

  // ============ Examples ============
  http.get('/api/v1/metadata/examples', async ({ request }) => {
    const url = new URL(request.url);
    const enabled = url.searchParams.get('enabled');

    let filtered = examples;
    if (enabled === 'true') {
      filtered = examples.filter(e => e.isActive);
    }

    return ok(filtered);
  }),

  http.post('/api/v1/metadata/examples', async ({ request }) => {
    const body = await request.json() as Partial<typeof META_EXAMPLES[0]>;
    const newItem = {
      id: exampleIdCounter++,
      question: body.question || '',
      sql: body.sql || '',
      domain: body.domain || '',
      orgScope: body.orgScope,
      isActive: body.isActive ?? true,
      createdAt: nowInBeijing(),
    };
    examples.push(newItem as typeof META_EXAMPLES[0]);
    return ok(newItem);
  }),

  http.put('/api/v1/metadata/examples/:id', async ({ params, request }) => {
    const body = await request.json() as Partial<typeof META_EXAMPLES[0]>;
    const item = examples.find(e => e.id === Number(params.id));
    if (!item) {
      return httpError(404, 'EXAMPLE_NOT_FOUND', '示例不存在');
    }
    Object.assign(item, body);
    return ok(item);
  }),

  http.delete('/api/v1/metadata/examples/:id', async ({ params }) => {
    const index = examples.findIndex(e => e.id === Number(params.id));
    if (index === -1) {
      return httpError(404, 'EXAMPLE_NOT_FOUND', '示例不存在');
    }
    examples.splice(index, 1);
    return ok(null);
  }),

  http.patch('/api/v1/metadata/examples/:id/toggle', async ({ params }) => {
    const item = examples.find(e => e.id === Number(params.id));
    if (!item) {
      return httpError(404, 'EXAMPLE_NOT_FOUND', '示例不存在');
    }
    item.isActive = !item.isActive;
    return ok(item);
  }),
];
