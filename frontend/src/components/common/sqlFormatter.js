const BLOCK_KEYWORDS = [
  'UNION ALL',
  'GROUP BY',
  'ORDER BY',
  'LEFT JOIN',
  'RIGHT JOIN',
  'INNER JOIN',
  'FULL JOIN',
  'CROSS JOIN',
  'SELECT',
  'FROM',
  'WHERE',
  'HAVING',
  'LIMIT',
  'OFFSET',
  'UNION',
  'JOIN',
  'WITH',
  'ON',
];

const KEYWORD_INDENT = {
  WITH: 0,
  SELECT: 0,
  FROM: 0,
  WHERE: 0,
  'GROUP BY': 0,
  'ORDER BY': 0,
  HAVING: 0,
  LIMIT: 0,
  OFFSET: 0,
  'UNION ALL': 0,
  UNION: 0,
  JOIN: 1,
  'LEFT JOIN': 1,
  'RIGHT JOIN': 1,
  'INNER JOIN': 1,
  'FULL JOIN': 1,
  'CROSS JOIN': 1,
  ON: 2,
};

const LOGICAL_KEYWORDS = ['AND', 'OR'];
const PLACEHOLDER_PREFIX = '__SQL_LITERAL_';

const splitTopLevelComma = (line) => {
  const parts = [];
  let current = '';
  let depth = 0;

  for (const char of line) {
    if (char === '(') {
      depth += 1;
    } else if (char === ')' && depth > 0) {
      depth -= 1;
    }

    if (char === ',' && depth === 0) {
      parts.push(`${current.trim()},`);
      current = '';
      continue;
    }

    current += char;
  }

  if (current.trim()) {
    parts.push(current.trim());
  }

  return parts;
};

const protectSqlLiteralsAndComments = (sql) => {
  const protectedSegments = [];
  let normalized = '';
  let index = 0;

  const pushSegment = (segment) => {
    const token = `${PLACEHOLDER_PREFIX}${protectedSegments.length}__`;
    protectedSegments.push(segment);
    normalized += token;
  };

  while (index < sql.length) {
    const current = sql[index];
    const next = sql[index + 1];

    if (current === '\'') {
      let cursor = index + 1;
      while (cursor < sql.length) {
        if (sql[cursor] === '\'' && sql[cursor + 1] === '\'') {
          cursor += 2;
          continue;
        }
        if (sql[cursor] === '\'') {
          cursor += 1;
          break;
        }
        cursor += 1;
      }
      pushSegment(sql.slice(index, cursor));
      index = cursor;
      continue;
    }

    if (current === '"') {
      let cursor = index + 1;
      while (cursor < sql.length) {
        if (sql[cursor] === '"' && sql[cursor + 1] === '"') {
          cursor += 2;
          continue;
        }
        if (sql[cursor] === '"') {
          cursor += 1;
          break;
        }
        cursor += 1;
      }
      pushSegment(sql.slice(index, cursor));
      index = cursor;
      continue;
    }

    if (current === '-' && next === '-') {
      let cursor = index + 2;
      while (cursor < sql.length && sql[cursor] !== '\n') {
        cursor += 1;
      }
      if (cursor < sql.length) {
        cursor += 1;
      }
      pushSegment(sql.slice(index, cursor));
      index = cursor;
      continue;
    }

    if (current === '/' && next === '*') {
      let cursor = index + 2;
      while (cursor < sql.length) {
        if (sql[cursor] === '*' && sql[cursor + 1] === '/') {
          cursor += 2;
          break;
        }
        cursor += 1;
      }
      pushSegment(sql.slice(index, cursor));
      index = cursor;
      continue;
    }

    normalized += current;
    index += 1;
  }

  return { normalized, protectedSegments };
};

const restoreSqlLiteralsAndComments = (sql, protectedSegments) =>
  sql.replace(new RegExp(`${PLACEHOLDER_PREFIX}(\\d+)__`, 'g'), (_, value) => protectedSegments[Number(value)] ?? '');

const applyKeywordFormatting = (sql) => {
  let formatted = sql.replace(/\s+/g, ' ').trim();

  BLOCK_KEYWORDS.forEach((keyword) => {
    const pattern = new RegExp(`\\s+(${keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')})\\b`, 'gi');
    formatted = formatted.replace(pattern, '\n$1');
  });

  LOGICAL_KEYWORDS.forEach((keyword) => {
    const pattern = new RegExp(`\\s+(${keyword})\\b`, 'gi');
    formatted = formatted.replace(pattern, '\n$1');
  });

  return formatted
    .split('\n')
    .flatMap((line) => {
      const trimmed = line.trim();
      if (!trimmed) {
        return [];
      }

      const blockKeyword = BLOCK_KEYWORDS.find((keyword) => new RegExp(`^${keyword}\\b`, 'i').test(trimmed));
      const lineIndent = blockKeyword ? KEYWORD_INDENT[blockKeyword] ?? 0 : 0;

      if (/^SELECT\b/i.test(trimmed)) {
        const selectBody = trimmed.replace(/^SELECT\s+/i, '');
        const selectParts = splitTopLevelComma(selectBody);
        if (selectParts.length > 1) {
          return [
            'SELECT',
            ...selectParts.map((part) => `  ${part}`),
          ];
        }
      }

      if (/^(AND|OR)\b/i.test(trimmed)) {
        return [`    ${trimmed}`];
      }

      return [`${'  '.repeat(lineIndent)}${trimmed}`];
    })
    .join('\n');
};

export const formatSqlForDisplay = (sql) => {
  if (!sql || !sql.trim()) {
    return '';
  }

  const { normalized, protectedSegments } = protectSqlLiteralsAndComments(sql);
  return restoreSqlLiteralsAndComments(applyKeywordFormatting(normalized), protectedSegments);
};

export const buildCompactSqlPreview = (sql, truncateAt = 56) => {
  const compactText = formatSqlForDisplay(sql).replace(/\s+/g, ' ').trim();
  return compactText.length > truncateAt
    ? `${compactText.slice(0, truncateAt)}...`
    : compactText;
};
