#!/usr/bin/env python3
"""Catch workflow mistakes that a YAML parse alone will not.

A workflow can be perfectly valid YAML and still be rejected by Actions before
a single job starts, with no annotation to say why. The one that bit us: a
folded scalar keeps the newline of any line indented further than the first, so

    if: >-
      a &&
       (b)

parses fine and yields an expression containing a newline, which the Actions
expression parser refuses.

Run: python scripts/check-workflow.py
"""
import sys
import pathlib

try:
    import yaml
except ImportError:
    print("pyyaml is required")
    sys.exit(2)

WORKFLOWS = pathlib.Path(".github/workflows")


def walk(node, path=""):
    """Yields (path, key, value) for every mapping entry."""
    if isinstance(node, dict):
        for key, value in node.items():
            here = f"{path}.{key}" if path else str(key)
            yield here, key, value
            yield from walk(value, here)
    elif isinstance(node, list):
        for index, value in enumerate(node):
            here = f"{path}[{index}]"
            yield from walk(value, here)


def main() -> int:
    problems = []
    for file in sorted(WORKFLOWS.glob("*.yml")) + sorted(WORKFLOWS.glob("*.yaml")):
        try:
            doc = yaml.safe_load(file.read_text(encoding="utf-8"))
        except yaml.YAMLError as error:
            problems.append(f"{file}: not valid YAML: {error}")
            continue

        for where, key, value in walk(doc):
            if key != "if" or not isinstance(value, str):
                continue
            if "\n" in value:
                problems.append(
                    f"{file}: `if` at {where} contains a newline. Put the "
                    f"expression on one line — Actions rejects the whole "
                    f"workflow otherwise.\n    {value!r}"
                )
            if value.strip().startswith("${{") and value.strip().endswith("}}"):
                # Harmless, but worth flagging: the wrapper is redundant and
                # hides mistakes inside it.
                problems.append(f"{file}: `if` at {where} is redundantly wrapped in ${{{{ }}}}")

    for problem in problems:
        print("PROBLEM:", problem)
    if problems:
        return 1
    print("workflows OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
