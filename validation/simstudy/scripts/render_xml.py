"""Render a run XML from a template, substituting ${VAR} placeholders.

Uses safe_substitute so BEAST's own `$(seg)` plate syntax passes through untouched, but then
checks that no ${VAR} placeholder was left behind -- a silently unsubstituted placeholder would
otherwise produce an XML that parses and runs with the wrong settings.

The rephyner arm additionally takes its prior parameters from the calibration table rather than
from the command line, so a cell that was never calibrated fails loudly here instead of quietly
running under a default.
"""

import argparse
import csv
import string
import sys


def load_calibration(path, cell):
    """Return (alpha, Ne) for a cell, or exit with a message naming what is missing."""
    with open(path) as f:
        rows = list(csv.DictReader(f, delimiter="\t"))

    wanted = {k: str(v) for k, v in cell.items()}
    for row in rows:
        if all(row.get(k, "").strip() == v for k, v in wanted.items()):
            if not row.get("alpha", "").strip():
                sys.exit(f"{path}: cell {wanted} has no fitted alpha "
                         f"(note: {row.get('note', '')!r})")
            return row["alpha"], row["Ne"]

    available = sorted({tuple(r[k] for k in wanted) for r in rows})
    sys.exit(f"{path}: no calibration for cell {wanted}.\n"
             f"Calibrated cells: {available}\n"
             f"The simstudy and calibration grids must cover the same cells.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--template", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--set", nargs="*", default=[], metavar="KEY=VALUE")
    parser.add_argument("--calibration",
                        help="calibration.tsv; when given, ALPHA and POP_SIZE are taken from it")
    parser.add_argument("--cell", nargs="*", default=[], metavar="KEY=VALUE",
                        help="the cell to look up in the calibration table")
    args = parser.parse_args()

    values = dict(kv.split("=", 1) for kv in args.set)

    if args.calibration:
        cell = dict(kv.split("=", 1) for kv in args.cell)
        alpha, ne = load_calibration(args.calibration, cell)
        values["ALPHA"] = alpha
        values["POP_SIZE"] = ne

    with open(args.template) as f:
        template = string.Template(f.read())

    rendered = template.safe_substitute(values)

    leftover = sorted(set(m.group("named") or m.group("braced")
                          for m in string.Template.pattern.finditer(rendered)
                          if m.group("named") or m.group("braced")))
    if leftover:
        sys.exit(f"{args.template}: unsubstituted placeholders {leftover}")

    with open(args.out, "w") as f:
        f.write(rendered)


if __name__ == "__main__":
    main()
