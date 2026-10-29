"""Collect operator acceptance rates from the BEAST operator tables in each run's beast.out.

Usage: python3 scripts/operator_acceptance.py <results_dir> > acceptance.tsv
"""

import re
import sys
from pathlib import Path

ROW = re.compile(r"^(\S+)\((\w+)\)\s+(\S+)\s+(\d+)\s+(\d+)\s+(\S+)\s+(\S+)")


def main():
    root = Path(sys.argv[1])
    print("\t".join(["cell", "model", "setting", "dataset", "seed",
                     "operator_id", "operator_class", "accept", "reject", "p_accept"]))
    for out in sorted(root.glob("runs/*/*/set*/ds*/seed*/beast.out")):
        seed, ds, setting, model, cell = out.parts[-2:-7:-1]
        for line in out.read_text().splitlines():
            m = ROW.match(line)
            if m:
                cls, op_id, _, accept, reject, _, p_accept = m.groups()
                print("\t".join([cell, model, setting[3:], ds[2:], seed[4:],
                                 op_id, cls.rsplit(".", 1)[-1], accept, reject, p_accept]))


if __name__ == "__main__":
    main()
