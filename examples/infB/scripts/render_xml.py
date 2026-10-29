#!/usr/bin/env python3
import argparse
from pathlib import Path


SECTION_FILES = {
    "STATE_BLOCK": "state.xml",
    "PRIORS_BLOCK": "priors.xml",
    "OPERATORS_BLOCK": "operators.xml",
}


def substitute(text, mapping, context_name, require_all_tokens=False):
    out = text
    for key, value in mapping.items():
        token = "{{" + key + "}}"
        if require_all_tokens and token not in out:
            raise RuntimeError(f"Missing placeholder {token} in {context_name}")
        if token in out:
            out = out.replace(token, str(value))
    return out


def main():
    parser = argparse.ArgumentParser(description="Render BEAST XML from a shared template and model block sections.")
    parser.add_argument("--template", required=True)
    parser.add_argument("--block-dir", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--chain-length", type=int, required=True)
    parser.add_argument("--store-every", type=int, required=True)
    parser.add_argument("--log-every", type=int, required=True)
    parser.add_argument("--screen-log-every", type=int, required=True)
    parser.add_argument("--pop-size", type=float, required=True)
    parser.add_argument("--namespace", required=True)
    args = parser.parse_args()

    template_path = Path(args.template)
    block_dir = Path(args.block_dir)
    out = Path(args.output)
    template_text = template_path.read_text(encoding="utf-8")
    scalar_mapping = {
        "CHAIN_LENGTH": args.chain_length,
        "STORE_EVERY": args.store_every,
        "LOG_EVERY": args.log_every,
        "SCREEN_LOG_EVERY": args.screen_log_every,
        "POP_SIZE": args.pop_size,
        "NAMESPACE": args.namespace,
    }

    section_mapping = {}
    for section_name, file_name in SECTION_FILES.items():
        section_path = block_dir / file_name
        if not section_path.exists():
            raise RuntimeError(f"Missing section file: {section_path}")
        section_text = section_path.read_text(encoding="utf-8")
        section_mapping[section_name] = substitute(section_text, scalar_mapping, str(section_path))

    xml_text = substitute(
        template_text,
        section_mapping,
        str(template_path),
        require_all_tokens=True,
    )
    xml_text = substitute(xml_text, scalar_mapping, str(template_path))

    if "{{" in xml_text or "}}" in xml_text:
        raise RuntimeError("Unresolved placeholders remain in rendered XML")

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(xml_text, encoding="utf-8")


if __name__ == "__main__":
    main()
