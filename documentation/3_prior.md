# 3. The rARG prior

## 3.1 Framing: an intractable integral over a subspace of ARGs

Given a well-motivated prior $p(G \mid \theta)$ on full ARGs (here: the coalescent with
reassortment), the prior on rARGs that makes rARG inference *exactly* equivalent to ARG inference
is the push-forward through the reduction map $r$:

$$
p(R \mid \theta) \;=\; \int_{\mathcal{G}} \mathbf{1}\big[r(G) = R\big]\; p(G \mid \theta)\; dG
\;=\; \int_{\mathcal{E}(R)} p(G \mid \theta)\; dG ,
\qquad \mathcal{E}(R) = \{G : r(G) = R\}.
$$

($p(R \mid \theta)$ is a density with respect to the node heights of $R$ and a counting measure on
its discrete structure.) The integral runs over the subspace of ARGs consistent with $R$: all
placements of reassortment events along the edges of $R$, all invisible coalescences between
lineages with disjoint ancestral segments, all invisible loops, and all ways of distributing the
edges of $R$ over lineages of $G$. Section 2.6 of [2_reduced_args.md](2_reduced_args.md) explains
why this space is hard to integrate over: it has unbounded dimension, and the hidden lineage
configuration makes the induced process on $R$ non-Markov.

Any prior used in place of $p(R \mid \theta)$ changes the posterior. A stand-in prior is acceptable
for testing the *sampler*, but for scientific inference the approximation has to be good where the
posterior puts its mass.

## 3.2 Possible approaches (overview)

| | Approach | Exact? | Cost per MCMC step | State |
|---|---|---|---|---|
| A | **Do not marginalise**: sample $G$ itself (CoalRe) | yes | low, but slow mixing | the baseline |
| B | **Unbiased estimate + pseudo-marginal MCMC**: estimate $p(R \mid \theta)$ by importance sampling or SMC over $\mathcal{E}(R)$ with a proposal that inserts hidden events consistent with $R$ | yes (pseudo-marginal) | high; variance of the estimate controls mixing | sketched in `rephyner.tex` ("naive" and "guided" importance sampling, particle filtering); not implemented |
| C | **Hand-crafted density on $R$** with a few tunable parameters, calibrated against simulated reduced ARGs | no | $O(\#\text{events})$ | implemented: `ReducedCoalescent` (main), `UniformReducedNetworkPrior`, `DummyNetworkPrior` |
| D | **Learned surrogate** $q_\phi(R) \approx p(R \mid \theta)$ fitted by maximum likelihood to reduced ARGs simulated from $p(G \mid \theta)$ | approximately | $O(\#\text{events} \cdot k \cdot m)$ | Python models implemented; Java port outdated and on hold |
| E | **Correct a surrogate**: use C or D as the MCMC prior and reweight posterior samples by $\hat p(R \mid \theta) / q(R)$ with an estimator from B (or use $q$ as the proposal inside B) | yes, if B works | offline | idea only |
| F | **Partial augmentation**: sample $R$ together with a small part of $Z$ (e.g. the assignment of $R$-edges to $G$-lineages) and integrate the remainder (reassortment times) analytically | yes | moderate | idea only, not explored |

Earlier exploratory Python code (in `../phylophun/rephyner/approx_network_prior/` and
`approximate_event_prior.py`) tried to model the rARG waiting times with phase-type /
hypoexponential distributions and Monte-Carlo marginals of masked Dirichlet-categorical child
choices. It was superseded by the learned prior of Section 3.4.

## 3.3 The hand-crafted stand-in: `ReducedCoalescent`

Class `rephyner.distribution.ReducedCoalescent`; derivation in `doc/claude/new_network_prior.md`.
It is the prior used in the current simulation study. It is meant as a cheap, proper-ish density
whose low-order moments can be matched to those of reduced CoalRe ARGs, **not** as an approximation
of the integral.

It has one timed event type (coalescence) and one **untimed** structural choice (how a node's
upward segments are split over its parent edges). Walking the events (sorted node heights, leaves
included) from the present into the past:

$$
\log p(R \mid N_e, \alpha, \lambda)
= \sum_{\text{intervals } i} -K_i \int_{\Delta_i} \frac{dt}{N_e(t)}
\;+\; \sum_{v \text{ internal}} (c_v - 1) \log \frac{1}{N_e(t_v)}
\;+\; \sum_{v} \log q_\alpha\big(\pi_v\big)
\;-\; \lambda \sum_v (p_v - 1)
$$

* $K_i$ = number of pairs of edges crossing interval $i$ that **share at least one segment**. Only
  such pairs can coalesce visibly; using $\binom{k}{2}$ instead over-counts in a way that grows with
  the number of segments. Maintained incrementally with bit masks, $O(E \cdot k)$ per evaluation.
* $(c_v - 1)$: a node with $c_v$ children stands for $c_v - 1$ binary coalescences at the same time
  (product extension; a continuous-time process would give such nodes probability zero).
* $\pi_v$ = the partition of $A_v$ (segments leaving $v$) induced by its parent edges.
  $q_\alpha$ is the partition weight, applied **at leaves too** (tip-level incongruence):
  * `crp` (default): Chinese restaurant process,
    $q_\alpha(\pi) = \frac{\Gamma(\alpha)}{\Gamma(\alpha + |A_v|)}\, \alpha^{p_v} \prod_{b \in \pi} (|b| - 1)!$.
    $\alpha \to 0$ collapses $R$ to a tree; larger $\alpha$ gives more reticulation and more nodes.
    For one segment $q = 1$ and the density reduces exactly to Kingman's coalescent.
  * `crpLength`: per-node $\alpha_v = \rho \cdot \bar L_v$ with $\bar L_v$ the mean length of $v$'s
    parent edges, so that splitting probability scales with lineage time as in CoalRe (here the
    `concentration` input plays the role of a reassortment rate).
  * `uniformPartition`: $q = 1 / S(|A_v|, p_v)$ (Stirling number of the second kind), parameter-free.
  * `none`: $q = 1$, for ablations.
* $\lambda \geq 0$ (`reticulationPenalty`, default 0): an additional plain penalty on total
  reticulation, used to pull rephyner's reticulation toward CoalRe's.

There is deliberately **no** term like $-\rho \cdot k_{\text{split}} \cdot \Delta_i$: it would make
the density depend on where reassortments sit in time, which $R$ does not record.
`ReducedCoalescentFormulaTest` guards this (changing $\alpha$ must shift $\log p$ by a purely
topological amount).

**Known limitations** (details in [5_status.md](5_status.md)):

* it is not a normalised density of any known process and has no simulator, so it can only be
  validated by moment matching, never by a sampling-from-prior test;
* with a shared $\alpha$ the node-count distribution is **bimodal** (a tree mode and a strongly
  reticulate mode, with $\alpha$ shifting weight between them), so mildly reticulate CoalRe targets
  cannot be matched in distribution;
* its node count saturates at roughly 35–47 % of the independent-trees limit $n + m(n-1)$, so for
  high reassortment no $\alpha$ reproduces CoalRe's prior.

**Other stand-ins.** `UniformReducedNetworkPrior` (with simulator `SimulatedUniformReducedNetwork`
and `UniformTree`) builds local trees one after the other, each reusing every internal node of the
previous trees with probability `keepNodeProbability`, with node heights uniform below `maxHeight`.
It has a matching simulator and is the model validated in the well-calibrated simulation study
(`validation/wcss`). `DummyNetworkPrior` is a Poisson prior on node and edge counts only
(improper in heights; intended as an operator-ablation control). Neither is meant for real data.

## 3.4 The learned rARG prior

Python code: `../phylophun/rephyner/prior_learning/`; plan:
`../phylophun/rephyner/learning_network_prior_plan.md`; Java port:
`rephyner.distribution.LearnedReducedNetworkPrior`.

### Pipeline

1. Simulate ARGs $G_1, \dots, G_N \sim p(G \mid \theta)$ with CoalRe's `SimulatedCoalescentNetwork`.
2. Reduce them, $R_j = r(G_j)$ (`NetworkReducer`), and write them to a `.trees` file (one rARG per
   line in dot format).
3. Fit a simple, normalised, autoregressive distribution $q_\phi$ by maximum likelihood on
   $\{R_j\}$. Since the $R_j$ are exact draws from $p(R \mid \theta)$, this minimises
   $\mathrm{KL}\big(p(\cdot \mid \theta)\,\|\,q_\phi\big)$ — the mass-covering direction, which is
   what one wants from a prior.
4. Use $q_\phi$ as the rARG prior in BEAST.

Simulation is cheap and unlimited, so the quality of $q_\phi$ is limited only by the model family,
not by data.

### Autoregressive factorisation

Order the internal nodes of $R$ by height, $v_1, \dots, v_K$. Before event $i$ the **context** is
the set of **open nodes** $j = 1, \dots, k_i$ (leaves sampled so far and earlier internal nodes),
each with

* a mask $M_i[j, s] = 1$ iff node $j$ carries segment $s$ and $s$ has not yet found a parent above
  $j$;
* an age $a_{ij} = t_i - t_j$.

A segment is **eligible** if at least two open nodes carry it. Event $i$ consists of

* a **waiting time** $\Delta t_i = t_i - t_{i-1}$ (measured from the previous event or sample);
* a **child assignment** $C_i[j, s] = 1$ iff open node $j$ passes segment $s$ to $v_i$. Each
  coalescing segment has exactly two children, so $C_i$ determines the child edges of $v_i$ and
  their segment sets.

After the event, the children's masks lose the coalesced segments, $v_i$ opens with mask = the set
of coalescing segments, segments carried by a single open node are closed (they reached their
local-tree root), and nodes without open segments are removed. Sampling stops when no segment is
eligible. Then

$$
q_\phi(R) \;=\; \prod_{i=1}^{K} q^{\text{wt}}_\phi\big(\Delta t_i \mid \text{ctx}_i\big)\;
q^{\text{top}}_\phi\big(C_i \mid \text{ctx}_i, t_i\big).
$$

Note that **parent edges and the partition of a node's segments over them are not modelled
explicitly**: they emerge from which later events pick up which of the node's open segments. The
untimed nature of reticulation is therefore built in.

### Envisioned general form

The plan describes the child choice as a generalised Chinese restaurant process over open nodes,
processed segment by segment:

$$
P\big(c^{s}_{i,0} = v \mid c^{0:s-1}_{i,0}\big) \;\propto\;
\begin{cases}
\beta, & v = \emptyset \\[2pt]
a^{s}_{i,v}\; n^{s}_{i,0}(v) \displaystyle\prod_{s' < s} \big(w(v, t_i)^\top w(c^{s'}_{i,0}, t_i)\big)^{\mathbf 1[c^{s'}_{i,0} = v]} + \alpha, & \text{otherwise,}
\end{cases}
$$

(and analogously for the second child, which is empty iff the first is), where
$a^{s}_{i,v} = \mathbf 1[v \in T_s \wedge \mathrm{parent}_s(v) \geq i]$ says whether $v$ is free in
segment $s$, $n^{s}_{i,0}(v)$ counts previous segments that chose $v$, and $w(v, t)$ is a learned
node embedding (constant $w$ recovers the plain CRP). Waiting time and embeddings $\lambda(\cdot)$,
$w(\cdot)$ were to be neural networks over the context (possibly attention layers, for permutation
invariance). The implemented models below are deliberately simple special cases of this.

### Implemented waiting-time models

| Model | File | Form |
|---|---|---|
| exponential per $k$ (MVP) | `wt_expon.py` | $\Delta t \sim \mathrm{Exp}(\lambda_k)$, $\hat\lambda_k = 1/\overline{\Delta t}_k$, $k$ = number of open nodes |
| gamma per $k$ | `wt_gamma.py` | $\Delta t \sim \mathrm{Gamma}(a_k, b_k)$ per $k$; **the format the Java port reads** (`waiting_time.json`) |
| lineage-pair exponential | `wt_lineages.py` | $\log \text{rate} = b_0 + b_1 \log \sum_s \binom{n_s}{2} + b_2 \log \max_s \binom{n_s}{2}$ with $n_s$ = number of open nodes carrying $s$; $(b_1, b_2) = (1, 0)$ is "segments coalesce independently", $(0, 1)$ "fully linked" |

### Implemented topology models

**Pairwise urn** (`topology_model.py`, model version 4, the current reference). At an event with
$k$ open nodes:

1. *Presence.* With probability $p_{\text{all}}(k)$ every eligible segment coalesces; otherwise
   eligible segments decide in order whether they coalesce, following a Pólya urn with initial
   pseudo-counts $[1 - \beta(k), \beta(k)]$. Conditioned on at least one segment coalescing.
2. *Choice.* Coalescing segments pick an unordered pair of children in order, with
   $P(\{a, b\}) \propto c_a c_b$ over nodes carrying the segment, where
   $c_j = \alpha(k)\, a_j^{\theta_{\text{base}}}$ plus reinforcement: after a segment chooses
   $\{a, b\}$, $c_a$ and $c_b$ grow by $w(\#\text{carriers}, k)\cdot a^{\theta_{\text{reinf}}}$.
   Reinforcement makes later segments follow earlier ones (linkage); $\theta_{\text{reinf}} < 0$
   makes old nodes less sticky, since their segments are more likely to have been split by
   reassortment.

$\alpha(k)$, $\beta(k)$, $p_{\text{all}}(k)$ are monotone piecewise-linear splines in logit space
over $k$; $\theta_{\text{base}}, \theta_{\text{reinf}}$ are scalars. Density and sampler are
implemented side by side and tested against each other.

**Two-lineage model** (`two_lineage_model.py`, newest). A more mechanistic model: every true
coalescence merges two ARG lineages $L$ and $R$; it draws a primary pair of open nodes, decides
whether each node is "intact" (entirely on one lineage) or "fragmented" (segments spread over
lineages), derives the coalescing segments, and then lets remaining segments join as "satellites"
(continue, new, or pair options with learned logits). This directly models the hidden lineage
configuration that makes the true process non-Markov. Trainer exists (`train_two_lineage.py`);
not yet evaluated against the pairwise urn.

**Training** (`train_child_crp_on_networks.py`): every internal node of every simulated rARG is one
training event; parameters maximise the mean event log-likelihood with minibatch Adam (JAX,
optax). `generate_from_prior.py` samples rARGs from fitted models and `evaluate_rarg_prior.py`
compares summary statistics (node/edge counts, heights) with the simulated reduced ARGs.

### Java port and open issues

`LearnedReducedNetworkPrior` reads `topology.json` (`alpha`, `beta`, `p_all`) and
`waiting_time.json` (gamma per $k$) from `paramPath` and evaluates the same factorisation. It
implements an **older** version of the topology model; `../phylophun/rephyner/java_port_notes.md`
lists the mismatches (spline knot range, ordered vs unordered pair probability, the $p_{\text{all}}$
mixture, the model JSON version, fallback for untrained $k$). The integration is **on hold** until
the Python model is settled. The `learnApproximator()` path (simulate, reduce, call Python) is a
stub with placeholder script paths.

Open modelling questions:

* **Dependence on $\theta$.** A surrogate fitted at one $(\rho, N_e)$ cannot be used to estimate
  $\rho$. Under a constant $N_e$ the coalescent is scale-invariant, so $N_e$ can be handled by
  rescaling time; $\rho$ (more precisely $\rho N_e$) would have to become an input of $q_\phi$
  (amortisation over a range of $\rho$).
* **Censoring.** With heterochronous samples, an interval can end in a sampling event; neither the
  fit nor the density includes the corresponding survival term yet.
* **Validation.** Matching summary statistics is necessary but weak. A direct check would compare
  $\log q_\phi(R)$ with an importance-sampling estimate of $\log p(R \mid \theta)$ on small rARGs
  (approach B), which would also quantify the remaining prior misspecification.
