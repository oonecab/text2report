#!/usr/bin/env python3
"""Check publication files without printing credential values."""

import argparse
import ipaddress
import json
import re
import subprocess
from pathlib import Path


TOKEN = re.compile(
    r"(?<![\w-])(?:sk-[A-Za-z0-9_-]{26,}|"
    r"gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|"
    r"AKIA[A-Z0-9]{16}|AIza[A-Za-z0-9_-]{30,}|"
    r"xox[baprs]-[A-Za-z0-9-]{20,})(?![\w-])"
)
PRIVATE_KEY = re.compile(r"-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----")
PERSONAL_PATH = re.compile(r"/Users/[\w.-]+/|[A-Z]:\\Users\\[\w.-]+\\")
EMAIL = re.compile(r"\b[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\.[A-Za-z]{2,})\b")
PHONE = re.compile(r"(?<!\d)(?:\+?86[- ]?)?1[3-9]\d{9}(?!\d)")
IPV4 = re.compile(r"(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])")
PASSWORD_DEFAULT = re.compile(
    r"\$\{[A-Z][A-Z0-9_]*(?:PASSWORD|SECRET|API_KEY)[A-Z0-9_]*:([^}]*)\}"
)
YAML_CREDENTIAL = re.compile(
    r"^\s*(?:password|secret|secret-key|api-key|client-secret)\s*:\s*([^#]*?)\s*$"
)
EXAMPLE_DOMAINS = {"example.com", "example.org", "example.net"}
PRIVATE_EXTENSIONS = {".pem", ".key", ".p8", ".p12", ".pfx", ".ppk", ".jks", ".keystore"}
SKIP_DIRECTORIES = {".git", "node_modules", "target", "dist"}


def placeholder(value):
    value = value.strip().strip("\"'")
    return (
        not value
        or value.startswith("${")
        or (value.startswith("<") and value.endswith(">"))
        or value.lower().startswith(("your-", "example-", "test-", "mock-"))
    )


def private_filename(path):
    file = Path(path)
    name = file.name
    if file.suffix.lower() in PRIVATE_EXTENSIONS:
        return True
    if name in {"id_rsa", "id_ecdsa", "id_ed25519", "id_dsa", "settings.local.json", "config.local.toml"}:
        return True
    if path.startswith("docs/env-") and file.suffix == ".md":
        return True
    if re.fullmatch(r"application-(?:local|prod|production|private|secrets)\.ya?ml", name):
        return True
    if name == ".env" or name.startswith(".env."):
        return name not in {".env.example", ".env.sample", ".env.template"} and path != "frontend/.env.development"
    return False


def inspect(path, data):
    findings = []

    def add(line, rule):
        findings.append({"file": path, "line": line, "rule": rule})

    if private_filename(path):
        add(0, "private-file")
    if b"\0" in data[:8192]:
        return findings
    config_file = Path(path).suffix in {".yml", ".yaml", ".template"}
    for number, line in enumerate(data.decode("utf-8", errors="replace").splitlines(), 1):
        for rule, pattern in [("api-token", TOKEN), ("private-key", PRIVATE_KEY), ("personal-path", PERSONAL_PATH)]:
            if pattern.search(line):
                add(number, rule)
        if "@" in line and any(match[1].lower() not in EXAMPLE_DOMAINS for match in EMAIL.finditer(line)):
            add(number, "personal-email")
        if PHONE.search(line):
            add(number, "phone-number")
        if any(not placeholder(match[1]) for match in PASSWORD_DEFAULT.finditer(line)):
            add(number, "credential-default")
        if config_file:
            match = YAML_CREDENTIAL.match(line)
            if match and not placeholder(match[1]):
                add(number, "credential-literal")
        if Path(path).suffix not in {".svg", ".lock"}:
            for match in IPV4.finditer(line):
                try:
                    address = ipaddress.ip_address(match[0])
                except ValueError:
                    continue
                if not address.is_loopback and not address.is_unspecified:
                    add(number, "network-address")
                    break
    return findings


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def publication_files(root, staged=False):
    if staged:
        names = git(root, "diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z")
    else:
        names = git(root, "ls-files", "--cached", "--others", "--exclude-standard", "-z")
    for raw in sorted(set(names.split(b"\0")) - {b""}):
        path = raw.decode("utf-8")
        if staged:
            yield path, git(root, "show", ":" + path)
        elif (root / path).is_file():
            yield path, (root / path).read_bytes()


def directory_files(root):
    for file in sorted(root.rglob("*")):
        relative = file.relative_to(root)
        if file.is_file() and not any(part in SKIP_DIRECTORIES for part in relative.parts):
            yield relative.as_posix(), file.read_bytes()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--staged", action="store_true", help="inspect added or changed index entries")
    mode.add_argument("--directory", type=Path, help="inspect a source snapshot")
    parser.add_argument("--json", action="store_true", help="emit paths and rules as JSON")
    args = parser.parse_args()
    root = args.directory.resolve() if args.directory else Path(__file__).resolve().parents[1]
    files = list(directory_files(root) if args.directory else publication_files(root, args.staged))
    findings = [finding for path, data in files for finding in inspect(path, data)]
    if args.json:
        print(json.dumps({"files_checked": len(files), "findings": findings}, ensure_ascii=False, indent=2))
    elif findings:
        for finding in findings:
            print(f"{finding['file']}:{finding['line']}: {finding['rule']}")
        print(f"FAIL: {len(findings)} findings in {len(files)} publication files")
    else:
        print(f"PASS: {len(files)} publication files checked")
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
