#!/usr/bin/env python3
"""
Validate golden query metadata against sql/schema-business.sql.

This script intentionally uses only Python standard library modules. It parses
the project DDL enough to verify that expected table and column names in the
golden dataset are real schema objects.
"""

from __future__ import annotations

import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
DATASET_PATH = ROOT / "docs" / "evaluation" / "datasets" / "golden_queries.json"
SCHEMA_PATH = ROOT / "sql" / "schema-business.sql"


CREATE_TABLE_RE = re.compile(
    r"create\s+table\s+(?:public\.)?([a-zA-Z_][a-zA-Z0-9_]*)\s*\((.*?)\);",
    re.IGNORECASE | re.DOTALL,
)
IDENTIFIER_RE = re.compile(r"^\s*([a-zA-Z_][a-zA-Z0-9_]*)\b")
DDL_SKIP_TOKENS = {
    "constraint",
    "primary",
    "foreign",
    "unique",
    "check",
    "references",
    "owner",
}


def parse_schema(path: Path) -> dict[str, set[str]]:
    ddl = path.read_text(encoding="utf-8")
    schema: dict[str, set[str]] = {}
    for match in CREATE_TABLE_RE.finditer(ddl):
        table_name = match.group(1).lower()
        body = match.group(2)
        columns: set[str] = set()
        for raw_line in body.splitlines():
            line = raw_line.strip().rstrip(",")
            if not line or line.startswith(")") or line.startswith("--"):
                continue
            identifier_match = IDENTIFIER_RE.match(line)
            if not identifier_match:
                continue
            token = identifier_match.group(1).lower()
            if token in DDL_SKIP_TOKENS:
                continue
            columns.add(token)
        schema[table_name] = columns
    return schema


def load_queries(path: Path) -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict) and isinstance(payload.get("queries"), list):
        return payload["queries"]
    raise ValueError("golden_queries.json must be a list or an object with a queries list")


def validate_query(query: dict[str, Any], schema: dict[str, set[str]]) -> list[str]:
    errors: list[str] = []
    query_id = query.get("id", "<missing-id>")

    expected_tables = [str(item).lower() for item in query.get("expected_tables", [])]
    if not expected_tables:
        errors.append(f"{query_id}: expected_tables is empty")

    for table_name in expected_tables:
        if table_name not in schema:
            errors.append(f"{query_id}: expected table does not exist in schema-business.sql: {table_name}")

    available_columns = set()
    for table_name in expected_tables:
        available_columns.update(schema.get(table_name, set()))

    for column_name in [str(item).lower() for item in query.get("expected_columns", [])]:
        if column_name not in available_columns:
            errors.append(
                f"{query_id}: expected column does not exist in expected tables: {column_name}"
            )

    for field_name in ("acceptable_sql_patterns", "forbidden_sql_patterns"):
        for pattern in query.get(field_name, []):
            try:
                re.compile(pattern)
            except re.error as exc:
                errors.append(f"{query_id}: invalid regex in {field_name}: {pattern!r} ({exc})")

    return errors


def print_distribution(queries: list[dict[str, Any]]) -> None:
    intent_counter = Counter(query.get("intent", "<missing>") for query in queries)
    time_counter = Counter(query.get("time_grain", "<missing>") for query in queries)
    dimension_counter: Counter[str] = Counter()
    for query in queries:
        for dimension in query.get("dimensions", []):
            dimension_counter[str(dimension)] += 1

    print("\nDistribution")
    print("Intent:")
    for key, count in sorted(intent_counter.items()):
        print(f"  {key}: {count}")

    print("Time grain:")
    for key, count in sorted(time_counter.items()):
        print(f"  {key}: {count}")

    print("Dimensions:")
    for key, count in sorted(dimension_counter.items()):
        print(f"  {key}: {count}")


def main() -> int:
    schema = parse_schema(SCHEMA_PATH)
    queries = load_queries(DATASET_PATH)

    print(f"Loaded schema tables: {len(schema)}")
    for table_name in sorted(schema):
        print(f"  {table_name}: {len(schema[table_name])} columns")

    print(f"\nLoaded golden queries: {len(queries)}")
    errors: list[str] = []
    seen_ids: set[str] = set()
    for query in queries:
        query_id = str(query.get("id", ""))
        if not query_id:
            errors.append("<missing-id>: id is required")
        elif query_id in seen_ids:
            errors.append(f"{query_id}: duplicate id")
        seen_ids.add(query_id)
        errors.extend(validate_query(query, schema))

    print_distribution(queries)

    if errors:
        print("\nValidation: FAILED")
        for error in errors:
            print(f"  - {error}")
        return 1

    print("\nValidation: PASSED")
    print("All expected_tables and expected_columns exist in sql/schema-business.sql.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
