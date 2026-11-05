#!/usr/bin/env python3
"""Turn a CHANGELOG section into notes short enough for a public release page.

    scripts/release-notes.py 0.2.1 [CHANGELOG.md]

The CHANGELOG entries are deliberately long: each one explains what broke, how, and why the
fix is the shape it is, because that reasoning is worth more six months later than a terse
line would be. A release page is the wrong place for it -- 0.2.1 ran to 2,600 characters of
engineering prose, which nobody scrolls.

So this keeps the detail where it belongs and publishes the headlines. Every bullet in the
CHANGELOG already opens with a bolded one-line summary, which is exactly the right length for
a release page, so there is nothing new to write and nothing to keep in sync: the notes are
derived, not a second copy that drifts.

Prints to stdout. Exits non-zero if the version has no section, which is the same thing
release.yml's verify-version already refuses a tag for.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = "https://github.com/chornge/halotap"


def section(changelog: str, version: str) -> list[str]:
    """The lines of one `## [version]` section, excluding its heading."""
    out: list[str] = []
    found = False
    for line in changelog.splitlines():
        if re.match(rf"^## \[{re.escape(version)}\]", line):
            found = True
            continue
        if found and line.startswith("## ["):
            break
        if found:
            out.append(line)
    if not found:
        raise SystemExit(f"release-notes: no '## [{version}]' section in the changelog")
    return out


def unwrap(lines: list[str]) -> list[tuple[str, str]]:
    """Join each wrapped bullet onto one line, tagged with the `###` heading it sits under.

    Bullets wrap across lines in the source, and a bold headline can wrap with them, so the
    headline cannot be read off the first line alone.
    """
    items: list[tuple[str, str]] = []
    heading = ""
    current: str | None = None

    def flush() -> None:
        nonlocal current
        if current is not None:
            items.append((heading, " ".join(current.split())))
            current = None

    for line in lines:
        if line.startswith("### "):
            flush()
            heading = line[4:].strip()
        elif line.startswith("- "):
            flush()
            current = line[2:]
        elif current is not None and line.strip():
            current += " " + line.strip()
        else:
            flush()
    flush()
    return items


def headline(bullet: str) -> str:
    """The bolded lead if there is one, else the first sentence.

    The fallback matters: not every entry is written lead-bold, and a bullet that silently
    vanished from the notes would be worse than one that is a little long.
    """
    bold = re.match(r"^\*\*(.+?)\*\*", bullet)
    if bold:
        return bold.group(1).rstrip(".")
    # A sentence end, but not the dot inside a version like 0.2.1 or a path like a.md.
    sentence = re.split(r"(?<=[a-z)`])\.\s", bullet, maxsplit=1)[0]
    return sentence.rstrip(".")


def lead(lines: list[str]) -> str:
    """Any prose before the first `###`, which is the author's own summary of the release."""
    text: list[str] = []
    for line in lines:
        if line.startswith("###"):
            break
        text.append(line)
    return "\n".join(text).strip()


def main() -> None:
    version = sys.argv[1] if len(sys.argv) > 1 else sys.exit("usage: release-notes.py VERSION")
    path = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("CHANGELOG.md")

    lines = section(path.read_text(encoding="utf-8"), version)

    parts: list[str] = []
    summary = lead(lines)
    if summary:
        parts.append(summary)

    last_heading = None
    for heading, bullet in unwrap(lines):
        if heading != last_heading:
            parts.append(f"\n### {heading}" if heading else "")
            last_heading = heading
        parts.append(f"- {headline(bullet)}")

    parts.append(
        f"\nFull detail for every line above is in "
        f"[CHANGELOG.md]({REPO}/blob/v{version}/CHANGELOG.md)."
    )
    print("\n".join(p for p in parts if p is not None).strip() + "\n")


if __name__ == "__main__":
    main()
