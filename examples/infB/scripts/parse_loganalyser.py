#!/usr/bin/env python3
import argparse
import re
from pathlib import Path


def parse_runtime_hours(runtime_path: Path) -> float:
    text = runtime_path.read_text(encoding="utf-8")
    m = re.search(r"^real\s+([0-9]+(?:\.[0-9]+)?)$", text, flags=re.MULTILINE)
    if not m:
        raise RuntimeError("Could not parse 'real' runtime from runtime.txt")
    seconds = float(m.group(1))
    return seconds / 3600.0


def parse_ess_map(loganalyser_text: str):
    ess_map = {}
    header_idx = None
    lines = [ln.rstrip("\n") for ln in loganalyser_text.splitlines()]

    for i, line in enumerate(lines):
        if re.search(r"\bESS\b", line, flags=re.IGNORECASE) and re.search(r"\bmean\b", line, flags=re.IGNORECASE):
            header_idx = i
            break

    if header_idx is None:
        # Fallback: parse lines ending in a numeric ESS-like token.
        for line in lines:
            m = re.match(r"^\s*([^\s#][^\s]*)\s+.*?(-?\d+(?:\.\d+)?)\s*$", line)
            if m:
                name = m.group(1)
                value = float(m.group(2))
                if name.lower() not in {"burnin", "sample", "state"}:
                    ess_map[name] = value
        return ess_map

    header = re.split(r"\s+", lines[header_idx].strip())
    ess_col = None
    name_col = 0
    for idx, token in enumerate(header):
        if token.lower() == "ess":
            ess_col = idx
            break
    if ess_col is None:
        raise RuntimeError("Could not find ESS column in LogAnalyser output")

    for line in lines[header_idx + 1 :]:
        if not line.strip() or line.strip().startswith("#"):
            continue
        parts = re.split(r"\s+", line.strip())
        if len(parts) <= ess_col:
            continue
        name = parts[name_col]
        try:
            ess = float(parts[ess_col])
        except ValueError:
            continue
        ess_map[name] = ess

    return ess_map


def main():
    parser = argparse.ArgumentParser(description="Parse LogAnalyser output into a compact metrics TSV.")
    parser.add_argument("--input", required=True)
    parser.add_argument("--runtime", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    in_path = Path(args.input)
    runtime_path = Path(args.runtime)
    out_path = Path(args.output)

    text = in_path.read_text(encoding="utf-8", errors="replace")
    ess_map = parse_ess_map(text)
    runtime_hours = parse_runtime_hours(runtime_path)

    posterior_ess = ess_map.get("posterior", float("nan"))
    min_ess = min(ess_map.values()) if ess_map else float("nan")
    ess_per_hour = posterior_ess / runtime_hours if runtime_hours > 0 and posterior_ess == posterior_ess else float("nan")

    rows = [
        ("runtime_hours", runtime_hours),
        ("posterior_ess", posterior_ess),
        ("min_ess", min_ess),
        ("ess_per_hour", ess_per_hour),
    ]

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8") as fh:
        fh.write("metric\tvalue\n")
        for key, val in rows:
            fh.write(f"{key}\t{val}\n")
        for key in sorted(ess_map.keys()):
            fh.write(f"ess.{key}\t{ess_map[key]}\n")


if __name__ == "__main__":
    main()
