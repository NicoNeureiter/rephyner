# Learning an approximate rARG prior

Status: paused on 2026-10-21. This document describes the approach, the models we tried, what we learned, and where things stand. The wider project (rephyner: Bayesian inference of reduced ARGs in BEAST 2) is documented in `../documentation/`. See `1_idea.md` and `2_reduced_args.md` there for the motivation and formal definitions, and `3_prior.md` for the full range of prior options.

## 1. Aim

A **reduced ARG (rARG)** $R = r(G)$ keeps only the nodes and edges of an ARG $G$ that appear in at least one local tree. Everything the sequence likelihood needs is kept. The unidentifiable parts are dropped: reassortment times, coalescences of lineages that carry disjoint segments, and loops. Sampling $R$ instead of $G$ should make MCMC much easier. The price is the prior. The prior on rARGs that is consistent with the coalescent with reassortment $p(G \mid \theta)$ is the push-forward

$$
p(R \mid \theta) = \int \mathbf{1}[r(G) = R]\, p(G \mid \theta)\, dG ,
$$

and this integral over all hidden ARG structure is intractable.

The goal here is a **learned surrogate** $q_\phi(R) \approx p(R \mid \theta)$ that can be used inside MCMC. It therefore has to be

* **normalised** (a proper density that we can also sample from, so it can be validated by simulation);
* **deterministic and cheap**: one evaluation costs $O(\text{events} \times k^2)$, with no Monte Carlo inside the density (no particle filters, no pseudo-marginal MCMC);
* **close to $p$** where the posterior puts mass.

## 2. Approach

1. Simulate ARGs from the coalescent with reassortment and reduce them. Simulation is cheap and unlimited.
2. Fit $q_\phi$ by maximum likelihood on the simulated rARGs. Because the training samples are exact draws from $p(R \mid \theta)$, this minimises $\mathrm{KL}(p \,\|\, q_\phi)$, the mass-covering direction.
3. Validate by sampling from $q_\phi$ and comparing with held-out exact rARGs.

**Autoregressive factorisation.** The internal nodes of $R$ are processed from the present into the past. Before each event, the **context** is the set of $k$ **open nodes** (nodes with at least one segment that has not yet found its parent). For each open node we know its *open segments* (a boolean mask) and its *age* (time since the node was created). An event consists of

* a **waiting time** since the previous event;
* a **child assignment**: for each segment, either nothing coalesces or exactly two open carriers coalesce. These choices define the new node.

$\log q_\phi(R)$ is exactly the sum of the event log-probabilities. So "log-likelihood per event" on held-out rARGs is a direct measure of $\mathrm{KL}(p \,\|\, q_\phi)$, up to a constant. Any **deterministic** function of the observed history can enter the context without breaking normalisation.

**Why this is hard.** The true process is Markov in the *hidden* ARG lineages, not in the open nodes. A single ARG lineage can carry segments of several open nodes, because disjoint lineages merge invisibly. The open segments of one node can also be spread over several lineages after reassortment. The context does not contain this grouping, so the process on rARGs is not Markov in it. Every model below is an attempt to approximate the effect of the hidden state from the observed context.

## 3. Models

Log-likelihoods are natural logarithms. A difference of $d$ in log-likelihood per event means the better model assigns each event on average about $e^{d} - 1 \approx d$ (for small $d$) more probability: a gain of 0.05 per event is about 5%.

### 3.1 Waiting time (`waiting_time/lineages.py`)

The waiting time is exponential, with a log-linear rate on per-segment lineage counts. Let $n_s$ be the number of open nodes carrying segment $s$:

$$
\log\lambda = b_0 + b_1 \log \textstyle\sum_s \binom{n_s}{2} + b_2 \log \max_s \binom{n_s}{2}.
$$

Here $(b_1, b_2) = (1, 0)$ would mean the segments coalesce independently and $(0, 1)$ that they are fully linked. The model is a convex GLM, fitted exactly. On the 40-taxon / 20-segment data it fits $(b_1, b_2) = (0.06, 0.85)$, i.e. nearly fully linked. It beats the earlier gamma-per-$k$ model on held-out NLL (−3.53 vs −3.41 per event), and its PIT is calibrated in every $k$ range (KS 0.007). The earlier gamma- and exponential-per-$k$ models are superseded; the gamma one is kept as the baseline in `waiting_time/gamma.py`.

### 3.2 Topology v4: pairwise urn (`topology/pairwise_urn.py`)

This is a per-segment model:

1. A Pólya-urn "presence" step decides which eligible segments coalesce, with a mixture weight for "all segments coalesce".
2. Each coalescing segment then picks an unordered pair of carriers with $P(\{a,b\}) \propto c_a c_b$. The weights $c$ are reinforced by earlier choices in the same event, which models linkage, and are scaled by node age (young nodes are "sticky").

The parameters are monotone logit-splines in $k$. Training is in `topology/train_pairwise_urn.py`. The density and sampler share one implementation and are tested against each other by exhaustive enumeration.

Limitation: it has no notion of the two merging lineages. Choosing the pair segment by segment produces too many children per node and too many reticulations, and node age helps only marginally (about 1.4% higher probability per event).

### 3.3 Topology v5: two-lineage model (`topology/two_lineage.py`)

This model is built on the structure of the true process. Every event merges exactly two ARG lineages $L$ and $R$. Each lineage carries at most one open node per segment, and a segment coalesces iff it is carried on both sides.

1. **Primary pair** $\{a, b\}$ among the open nodes with overlapping open segments. Its weight depends on age, number of open segments and the size of the overlap.
2. **Primary coalescing set.** Each primary is "intact" (all its open segments are on its lineage) or "fragmented". If both are intact, all shared segments coalesce. Otherwise a Beta-binomial picks a subset. The step is conditioned on the set being non-empty.
3. **Satellites.** Further open nodes may join one side and coalesce with a primary on segments the primaries cannot pair among themselves. Node-level attachment and an urn make an attached satellite coalesce its segments together. The decisive feature: a satellite is (almost) never on the same side as a node it overlaps with, because one lineage carries each segment only once.

The density sums over the possible primary pairs of an observed event and is exactly normalised. The module docstring defines the full process; the trainer is `topology/train_two_lineage.py`.

The model has about 20 parameters. Two pieces are effectively unused and can be removed: the soft "both sides" penalty $\kappa$ fits to ≈ 0, and the overlap weight saturates at −12.5, i.e. acts as a hard constraint. Note that $\kappa \approx 0$ lets a node sit on both sides of an event. This happens in only ~0.02% of exact events, but the model generates such topologies ~20× too often.

### 3.4 Tried and dropped

* **Earlier per-event exploration**: phase-type waiting times and Monte-Carlo marginals of masked Dirichlet-categoricals, and a factorised CRP fit of the child assignment. The code was removed; see the git history of the phylophun repository (`rephyner/prior_learning/`, where this package lived until 2026-10-30).
* **The neural context encoders** sketched in the original plan were never needed. The problem is missing information, not model flexibility (§5).
* **History features for the hidden grouping.** These are deterministic, so they would be allowed in the prior:
  * "sibling" (two open nodes created or touched by the same latest event) predicts satellite attachment on 40×20 (AUC 0.58 → 0.72) but raises the probability per event by only ~5% there and not at all at 6×4;
  * union-find groups and shared-history counts add nothing beyond it.

## 4. Data and code

**Exact data.**

* `simulate_contemp40taxon20seg.reduced.trees` (in the phylophun repo, `rephyner/`): CoalRe simulation, 40 contemporaneous taxa, 20 segments, $N_e = 1$, $\rho = 1$ (`../validation/simulator/simulate_contemp40taxon20seg.xml`). 10,000 networks; the benchmarks use the first 1000. Committed compressed as `.reduced.trees.xz`; unpack with `xz -dk`. The XML sets no seed, so rerunning it gives a different sample.
* `simulate.py`: a Python simulator of the same process that writes reduced ARGs directly, in the same `.trees` format or as event arrays (`.npz`). The `.npz` output additionally holds the **true hidden lineage label** of every open (node, segment) at every event. It follows CoalRe's conventions: reassortment rate per lineage, segments assigned 50/50 to the two parents, all-to-one-side reassortments ignored, segment flags never dropped. On 40×20 it agrees with the CoalRe file on all summary statistics (KS D ≤ 0.053, |z| ≤ 0.9). It avoids the slow pydot parsing altogether: 1000 networks at 40×20 take ~2 s, 200k small networks ~10 s.

**Code layout.** All imports inside the package are relative, so it runs under any parent package.

| path | content |
|---|---|
| `rarg/` | `ReducedNetwork` and event contexts, DOT/nexus parsing, training-event extraction (`events.py`) |
| `simulate.py` | simulator of reduced ARGs, `.npz` event files |
| `waiting_time/` | lineage-count model (`lineages.py`) and the gamma-per-$k$ baseline (`gamma.py`) |
| `topology/` | v4 pairwise urn and v5 two-lineage model, each with its trainer |
| `generate.py` | sampling complete networks from a waiting-time and a topology model |
| `density.py` | log-density of complete networks; writes the fixture for the Java test |
| `tests/` | unit tests |

**Install.** `pip install -e .` in this directory installs the package as `prior_learning` with its dependencies (`pyproject.toml`); the commands below then also work as `python -m prior_learning.…` from anywhere.

**Pipeline** (from the rephyner repository root):

```bash
python -m prior_learning.simulate --n 200000 --leaves 6 --segments 4 --rho 0.4 --seed 11 --workers 8 --events train.npz
python -m prior_learning.simulate --n 20000  --leaves 6 --segments 4 --rho 0.4 --seed 12 --workers 8 --events heldout.npz
python -m prior_learning.waiting_time.lineages train.npz --out wait.json
python -m prior_learning.topology.train_two_lineage train.npz topology.json --heldout heldout.npz   # v4: topology.train_pairwise_urn
python -m prior_learning.generate --waitModel wait.json --childModel topology.json --nLeaves 6 --nSegments 4 --nSamples 1000
```

The trainers and the waiting-time fit also accept CoalRe `.trees` files, with `--max-networks` and a `--valid-frac` split. `generate` dispatches on the `family` field of the topology JSON. Tests: `python -m unittest discover -s prior_learning/tests -t .`

**Java.** `LearnedReducedNetworkPrior` (`src/rephyner/distribution/`) evaluates the waiting-time model and the v4 pairwise urn from their JSON files (`waitingTimeModel`, `topologyModel`): fit the models first, then run BEAST. See §6.

## 5. Current performance

Log-likelihoods below are for the **topology** part only, per event, on held-out networks (higher is better). Generation comparisons sample complete networks (waiting-time model + topology model) and compare them with held-out exact networks.

**Small benchmark: 6 taxa, 4 segments, $\rho = 0.4$** (200k training and 20k held-out networks, ~6 events per network)

| | held-out LL/event |
|---|---|
| v4 pairwise urn | −2.747 |
| two-lineage (10k / 50k / 200k training networks) | −2.520 / −2.519 / −2.520 |
| frequency table over (context → event), the ceiling for the open-node context | ≈ −2.46 |
| oracle: true hidden lineages known (not achievable by a prior) | −1.606 |

| statistic (mean) | exact | v4 | two-lineage |
|---|---|---|---|
| internal nodes | 6.13 | 6.36 | 6.25 |
| reticulations | 2.11 | 3.09 | 2.30 |
| children per node | 2.14 | 2.31 | 2.15 |
| fraction of fully coalescing nodes | 0.70 | 0.63 | 0.67 |
| mean local-tree height | 1.67 | 1.40 | 1.62 |
| TV distance of reticulation histogram | – | 0.20 | 0.06 |

Topology distributions (unlabelled, times ignored, 100k samples):

| setting | v4 TV | two-lineage TV | sampling-noise floor |
|---|---|---|---|
| 3 taxa × 2 segments | 0.051 | 0.033 | ≈ 0.004 |
| 4 taxa × 3 segments | 0.316 | 0.133 | ≈ 0.03 |

**Original setting: 40 taxa, 20 segments, $\rho = 1$** (CoalRe data, 1000 networks, 80/20 split by network; ~48 events per network)

| | held-out LL/event |
|---|---|
| v4 pairwise urn | −8.684 |
| two-lineage | −7.570 |

| statistic (mean) | exact | v4 | two-lineage |
|---|---|---|---|
| internal nodes | 48.1 | 45.0 | 54.5 |
| reticulations | 15.4 | 24.9 | 30.4 |
| children per node | 2.37 | 3.01 | 3.00 |
| fraction of fully coalescing nodes | 0.71 | 0.77 | 0.53 |
| root height | 3.02 | 2.01 | 3.87 |
| mean local-tree height | 1.94 | 0.87 | 1.67 |

**Reading of the results.**

* **Data size is not the bottleneck.** The learning curve is flat from 10k networks on.
* **The model form is nearly as good as the open-node context allows.** On 6×4 the two-lineage model assigns held-out events on average only ~6% less probability than a nonparametric table on the same context. On small networks it generates realistic samples.
* **The remaining error is the missing hidden lineage state.** Knowing the true lineages would make everything but the choice of the merging lineage pair deterministic. Cheap history features recover almost none of that information.
* **The error grows with network size.** It is small at 6×4 (reticulations +9%) but large at 40×20 (+97%), where the two-lineage model has the far better held-out likelihood and yet generates networks that are too reticulate. Small per-event errors compound over longer histories with more hidden groupings.

## 6. Java integration (`LearnedReducedNetworkPrior`)

`src/rephyner/distribution/LearnedReducedNetworkPrior.java` integrates the waiting-time model (`waiting_time/lineages.py`) and the pairwise urn process (`topology/pairwise_urn.py`). It reads the two JSON files written by the trainers. The "open-node" bookkeeping follows `rarg/reduced_network.py`: waiting times are measured from the previous event or sample, and segments carried by a single open node are closed after every event.

`LearnedReducedNetworkPriorTest` compares it with `density.py` on examples in `test/rephyner/distribution/learnedprior/`. The two densities agree to a relative error of ~1e-5 per network (float32 in JAX). After changing a model or the density, regenerate the fixture:

```bash
python -m prior_learning.density fixture.trees --waitModel waiting_time.json --childModel topology.json --out fixture.json
```

Not handled on either side: censoring of intervals that end in a sample (heterochronous tips), and the dependence on $\rho N_e$ (each fit is for one value).
