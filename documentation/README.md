# rephyner documentation

rephyner (*re*duced *phy*logenetic *ne*twork *r*econstruction) is a BEAST 2 package for Bayesian
inference of **reduced ancestral recombination/reassortment graphs (rARGs)**. It is a fork of
[CoalRe](https://github.com/nicfel/CoalRe) and depends on it for simulating full ARGs. The
companion Python code for learning an approximate rARG prior lives in
`../phylophun/rephyner/` (separate repository).

Status of this documentation: 2026-10-20, written against commit `b43296a` plus the uncommitted
working tree at that date.

| Document | Contents |
|---|---|
| [1_idea.md](1_idea.md) | Motivation: why ARG inference is hard and how reducing the ARG should help |
| [2_reduced_args.md](2_reduced_args.md) | Formal definition of rARGs, the reduction map $r$, likelihood, and the prior problem |
| [3_prior.md](3_prior.md) | The rARG prior as an intractable integral; candidate approaches; the learned rARG prior |
| [4_inference.md](4_inference.md) | MCMC state, the BEAST 2 classes involved, and the operators implemented so far |
| [5_status.md](5_status.md) | What works, what does not, and the preliminary results (WCSS, calibration, infB, simulation study) |
| [cleanup_candidates.md](cleanup_candidates.md) | Redundant files and code found while writing this (to check and delete) |

## Related notes elsewhere in the repository

These are working notes rather than documentation. They are the primary record of *why* things
are the way they are and are cited from the documents above.

* `doc/claude/new_network_prior.md` — derivation of the `ReducedCoalescent` density.
* `dummy_rarg_prior_plan.md` — plan and results for calibrating `ReducedCoalescent`.
* `simulation_study_plan.md` — design and decision rule of the rephyner-vs-CoalRe simulation study.
* `evaluation_plan.md` — empirical benchmarks (infB, IE-CoR) and the EULER/SLURM set-up.
* `.github/local-tree-operators-review.md` — review of the local-tree operator family.
* `../phylophun/rephyner/learning_network_prior_plan.md` — the learned rARG prior.
* `../phylophun/rephyner/java_port_notes.md` — mismatches between the Python prior and its Java port.
* `rephyner.tex` — early (2025) model draft; largely superseded by this documentation.
