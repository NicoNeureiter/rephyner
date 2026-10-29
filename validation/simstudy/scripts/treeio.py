"""Minimal NEXUS tree-log reader and Robinson-Foulds distance.

Only what the mixing study needs: read the trees BEAST logged, turn each into its set of clades,
and compare clade sets. No branch lengths, no rerooting -- these are time trees on a common taxon
set, so rooted clade comparison is the right notion and is what the RF numbers mean here.
"""

import re

TREE_LINE = re.compile(r"^\s*tree\s+\S+\s*=\s*(?:\[[^\]]*\]\s*)?(\(.*;)\s*$")
TRANSLATE_ENTRY = re.compile(r"^\s*(\w+)\s+([^,;]+)\s*[,;]?\s*$")


def newick_clades(newick, translate=None):
    """Return the tree's non-trivial clades, each a frozenset of taxon labels.

    Singletons and the all-taxa clade are dropped: every tree on the same taxa shares them, so
    keeping them would only dilute the RF distance.
    """
    clades = set()
    stack = [[]]
    token = ""
    in_comment = 0
    i = 0

    def flush():
        nonlocal token
        label = token.strip().split(":")[0].strip()
        token = ""
        if not label:
            return
        if translate:
            label = translate.get(label, label)
        stack[-1].append(frozenset([label]))

    while i < len(newick):
        c = newick[i]
        if c == "[":
            in_comment += 1
        elif c == "]":
            in_comment -= 1
        elif in_comment > 0:
            pass
        elif c == "(":
            flush()
            stack.append([])
        elif c == ",":
            flush()
        elif c == ")":
            flush()
            children = stack.pop()
            clade = frozenset().union(*children) if children else frozenset()
            stack[-1].append(clade)
            clades.add(clade)
            # Skip any internal node label or branch length attached to this group.
            j = i + 1
            while j < len(newick) and newick[j] not in "(),;":
                j += 1
            i = j - 1
        elif c == ";":
            break
        else:
            token += c
        i += 1

    if not clades:
        raise ValueError(f"could not parse newick: {newick[:80]!r}")

    all_taxa = frozenset().union(*clades)
    return {c for c in clades if 1 < len(c) < len(all_taxa)}


def read_translate(lines):
    """Parse the NEXUS Translate block, if there is one."""
    translate = {}
    in_block = False
    for line in lines:
        stripped = line.strip()
        if stripped.lower().startswith("translate"):
            in_block = True
            continue
        if in_block:
            if stripped.startswith("tree ") or stripped.lower() == "end;":
                break
            match = TRANSLATE_ENTRY.match(line)
            if match:
                translate[match.group(1)] = match.group(2).strip().rstrip(",;").strip("'\"")
            if stripped.endswith(";"):
                break
    return translate


def read_tree_log(path, burnin_percent=0.0, thin=1):
    """Return the clade set of each sampled tree, after burnin and thinning."""
    with open(path) as f:
        lines = f.readlines()

    translate = read_translate(lines)
    newicks = [m.group(1) for m in (TREE_LINE.match(line) for line in lines) if m]

    burnin = int(len(newicks) * burnin_percent / 100.0)
    return [newick_clades(n, translate) for n in newicks[burnin::thin]]


def robinson_foulds(clades_a, clades_b):
    """Unnormalised RF distance: clades present in one tree but not the other."""
    return len(clades_a ^ clades_b)
