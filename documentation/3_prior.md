# 3. The rARG prior

## 3.1 Framing: an intractable integral over a subspace of ARGs

Given a well-motivated prior $p(G \mid \theta)$ on full ARGs (here the coalescent with reassortment), the rARG prior that makes rARG inference exactly equivalent to ARG inference is

$$
p(R \mid \theta) \;=\; \int_{\mathcal{G}} \mathbf{1}\big[r(G) = R\big]\; p(G \mid \theta)\; dG
\;=\; \int_{\mathcal{E}(R)} p(G \mid \theta)\; dG ,
\qquad \mathcal{E}(R) = \{G : r(G) = R\}.
$$

($p(R \mid \theta)$ is a density in the node heights of $R$ and a probability in its discrete structure.) The integral runs over all ARGs consistent with $R$: all placements of reassortment events along the edges of $R$, all invisible coalescences, all invisible loops, and all ways of distributing the edges of $R$ over lineages of $G$. Section 2.6 of [2_reduced_args.md](2_reduced_args.md) explains why this is hard: the space has no bounded dimension, and the hidden lineages make the rate of each event in $R$ depend on the whole history of $R$.

Any prior used in place of $p(R \mid \theta)$ changes the posterior. A stand-in prior is fine for testing the sampler, but for real inference the approximation has to be good where the posterior puts its mass.

## 3.2 Possible approaches

| | Approach | Exact? | Cost per MCMC step | State |
|---|---|---|---|---|
| A | Don't integrate: sample $G$ itself (CoalRe) | yes | low, but slow mixing | the baseline |
| B | Estimate $p(R \mid \theta)$ by importance sampling or particle filtering over $\mathcal{E}(R)$ (inserting hidden events consistent with $R$) and use the estimate in the MCMC acceptance step (pseudo-marginal MCMC) | yes | high; the noise of the estimate slows mixing | sketched in `rephyner.tex`; not implemented |
| C | Hand-crafted density on $R$ with a few tunable parameters, calibrated against simulated reduced ARGs | no | linear in #events | implemented: `ReducedCoalescent` (main), `UniformReducedNetworkPrior`, `DummyNetworkPrior` |
| D | Learned approximation $q_\phi(R) \approx p(R \mid \theta)$, fitted by maximum likelihood to reduced ARGs simulated from $p(G \mid \theta)$ | approximately | linear in #events × open nodes × segments | Python models implemented; Java port outdated and on hold |
| E | Correct an approximation: use C or D as the MCMC prior and reweight posterior samples by $\hat p(R \mid \theta) / q(R)$, with $\hat p$ from B | yes, if B works | after the run | idea only |
| F | Partial augmentation: sample $R$ together with a small part of $Z$ (e.g. which $R$-edges belong to the same $G$-lineage) and integrate out the rest (reassortment times) analytically | yes | moderate | idea only |

Earlier exploratory Python code (`../phylophun/rephyner/approx_network_prior/`, `approximate_event_prior.py`) modelled rARG waiting times with phase-type distributions and child choices with Monte Carlo estimates. It was superseded by the learned prior of Section 3.4.

## 3.3 The hand-crafted stand-in: `ReducedCoalescent`

Class `rephyner.distribution.ReducedCoalescent`; derivation in `doc/claude/new_network_prior.md`. This is the prior used in the current simulation study. It is meant as a cheap density whose node count and height can be tuned to match reduced CoalRe ARGs, not as an approximation of the integral.

It has one timed event type (coalescence) and one untimed choice (how a node's segments are split over its parent edges). Going through the nodes (leaves included) from the present into the past:

$$
\log p(R \mid N_e, \alpha, \lambda)
= \sum_{\text{intervals } i} -K_i \int_{\Delta_i} \frac{dt}{N_e(t)}
\;+\; \sum_{v \text{ internal}} (c_v - 1) \log \frac{1}{N_e(t_v)}
\;+\; \sum_{v} \log q_\alpha\big(\pi_v\big)
\;-\; \lambda \sum_v (p_v - 1)
$$

* $K_i$ is the number of pairs of edges in interval $i$ that share at least one segment. Only those pairs can coalesce visibly; using $\binom{k}{2}$ instead overcounts more and more as the number of segments grows. $K_i$ is updated incrementally with bit masks.
* $(c_v - 1)$: a node with $c_v$ children counts as $c_v - 1$ coalescences at the same time.
* $\pi_v$ is the partition of the segments leaving $v$ over its parent edges, and $q_\alpha$ is a probability for that partition, applied at leaves too (incongruence right at the tips). Options:
  * `crp` (default): Chinese restaurant process, $q_\alpha(\pi) = \frac{\Gamma(\alpha)}{\Gamma(\alpha + |A_v|)}\, \alpha^{p_v} \prod_{b \in \pi} (|b| - 1)!$. $\alpha \to 0$ collapses $R$ to a tree; larger $\alpha$ gives more reticulation and more nodes. With one segment $q = 1$ and the density is exactly Kingman's coalescent.
  * `crpLength`: per-node $\alpha_v = \rho \cdot \bar L_v$, with $\bar L_v$ the mean length of $v$'s parent edges, so that the splitting probability grows with branch length as in CoalRe (the `concentration` input then acts as a reassortment rate).
  * `uniformPartition`: $q = 1 / S(|A_v|, p_v)$ (Stirling number of the second kind), no parameter.
  * `none`: $q = 1$, for comparisons.
* $\lambda \geq 0$ (`reticulationPenalty`, default 0) is an extra penalty on total reticulation, used to pull rephyner's reticulation toward CoalRe's.

There is deliberately no term like $-\rho \cdot k_{\text{split}} \cdot \Delta_i$: it would make the density depend on when reassortments happen, which $R$ does not record. `ReducedCoalescentFormulaTest` checks this (changing $\alpha$ must change $\log p$ by an amount that depends only on the topology).

Known limitations (details in [5_status.md](5_status.md)):

* it is not a normalised density of any known process and has no simulator, so it can only be checked by matching averages, never by a sampling-from-prior test;
* with a single $\alpha$ the node-count distribution has two modes (a tree and a strongly reticulate network, with $\alpha$ shifting weight between them), so mildly reticulate CoalRe targets cannot be matched;
* its node count levels off at about 35–47 % of the maximum $n + m(n-1)$ (independent local trees), so at high reassortment no $\alpha$ reproduces CoalRe's prior.

Other stand-ins. `UniformReducedNetworkPrior` (with simulator `SimulatedUniformReducedNetwork` and `UniformTree`) builds the local trees one after another, each reusing every internal node of the previous trees with probability `keepNodeProbability`, with node heights uniform below `maxHeight`. It has a matching simulator and is the model checked in the well-calibrated simulation study (`validation/wcss`). `DummyNetworkPrior` is a Poisson prior on node and edge counts only (improper in the heights; meant as a control for operator tests). Neither is meant for real data.

## 3.4 The learned rARG prior

Python code: `../phylophun/rephyner/prior_learning/`. Plan: `../phylophun/rephyner/learning_network_prior_plan.md`. Java port: `rephyner.distribution.LearnedReducedNetworkPrior`.

### Pipeline

1. Simulate ARGs $G_1, \dots, G_N \sim p(G \mid \theta)$ with CoalRe's `SimulatedCoalescentNetwork`.
2. Reduce them, $R_j = r(G_j)$ (`NetworkReducer`), and write them to a `.trees` file (one rARG per line in dot format).
3. Fit a simple, normalised, step-by-step model $q_\phi$ to $\{R_j\}$ by maximum likelihood. Since the $R_j$ are exact draws from $p(R \mid \theta)$, this makes $q_\phi$ as close as possible to the true prior in the sense of $\mathrm{KL}\big(p(\cdot \mid \theta)\,\|\,q_\phi\big)$, which penalises $q_\phi$ mainly for missing regions where $p$ has mass. That is what one wants from a prior.
4. Use $q_\phi$ as the rARG prior in BEAST.

Simulation is cheap and unlimited, so the quality of $q_\phi$ is limited by the model, not by the amount of training data.

### Step-by-step factorisation

Order the internal nodes of $R$ by height, $v_1, \dots, v_K$. Before event $i$ the state consists of the open nodes $j = 1, \dots, k_i$ (leaves sampled so far and earlier internal nodes), each with

* a mask $M_i[j, s] = 1$ if node $j$ carries segment $s$ and $s$ has not yet found a parent above $j$;
* an age $a_{ij} = t_i - t_j$.

A segment is eligible if at least two open nodes carry it. Event $i$ consists of

* a waiting time $\Delta t_i = t_i - t_{i-1}$ (since the previous event or sample);
* a child assignment $C_i[j, s] = 1$ if open node $j$ passes segment $s$ to $v_i$. Each coalescing segment has exactly two children, so $C_i$ determines the child edges of $v_i$ and their segment sets.

After the event, the children lose the coalesced segments from their masks, $v_i$ opens with the coalescing segments, segments carried by only one open node are closed (they reached the root of their local tree), and nodes without open segments are removed. The process stops when no segment is eligible. Then

$$
q_\phi(R) \;=\; \prod_{i=1}^{K} q^{\text{wt}}_\phi\big(\Delta t_i \mid \text{state}_i\big)\;
q^{\text{top}}_\phi\big(C_i \mid \text{state}_i, t_i\big).
$$

Parent edges, and how a node's segments are split over them, are not modelled explicitly. They follow from which later events pick up which of the node's open segments, so reassortments are untimed by construction.

### Envisioned general form

The plan describes the child choice as a generalised Chinese restaurant process over open nodes, going through the segments one by one:

$$
P\big(c^{s}_{i,0} = v \mid c^{0:s-1}_{i,0}\big) \;\propto\;
\begin{cases}
\beta, & v = \emptyset \\[2pt]
a^{s}_{i,v}\; n^{s}_{i,0}(v) \displaystyle\prod_{s' < s} \big(w(v, t_i)^\top w(c^{s'}_{i,0}, t_i)\big)^{\mathbf 1[c^{s'}_{i,0} = v]} + \alpha, & \text{otherwise,}
\end{cases}
$$

and the same for the second child, which is empty if and only if the first is. Here $a^{s}_{i,v} = \mathbf 1[v \in T_s \wedge \mathrm{parent}_s(v) \geq i]$ says whether $v$ is still available for segment $s$, $n^{s}_{i,0}(v)$ counts previous segments that chose $v$, and $w(v, t)$ is a learned node embedding (constant $w$ gives the plain CRP). The waiting-time rate and the embeddings were planned as neural networks over the current state. The implemented models below are deliberately simple special cases.

### Implemented waiting-time models

| Model | File | Form |
|---|---|---|
| exponential per $k$ | `wt_expon.py` | $\Delta t \sim \mathrm{Exp}(\lambda_k)$, $\hat\lambda_k = 1/\overline{\Delta t}_k$, $k$ = number of open nodes |
| gamma per $k$ | `wt_gamma.py` | $\Delta t \sim \mathrm{Gamma}(a_k, b_k)$ per $k$; the format the Java port reads (`waiting_time.json`) |
| lineage-pair exponential | `wt_lineages.py` | $\log \text{rate} = b_0 + b_1 \log \sum_s \binom{n_s}{2} + b_2 \log \max_s \binom{n_s}{2}$, with $n_s$ the number of open nodes carrying $s$; $(b_1, b_2) = (1, 0)$ means segments coalesce independently, $(0, 1)$ means they are fully linked |

### Implemented topology models

Pairwise urn (`topology_model.py`, model version 4, the current reference). At an event with $k$ open nodes:

1. Which segments coalesce: with probability $p_{\text{all}}(k)$ all eligible segments coalesce. Otherwise the eligible segments decide one after another whether they coalesce, following a Pólya urn with initial counts $[1 - \beta(k), \beta(k)]$, conditioned on at least one segment coalescing.
2. Which children: the coalescing segments pick an unordered pair of children one after another, with $P(\{a, b\}) \propto c_a c_b$ over the nodes carrying the segment, where $c_j = \alpha(k)\, a_j^{\theta_{\text{base}}}$ to start with. After a segment chooses $\{a, b\}$, $c_a$ and $c_b$ grow by $w(\#\text{carriers}, k)\cdot a^{\theta_{\text{reinf}}}$, so later segments tend to follow earlier ones (linkage). $\theta_{\text{reinf}} < 0$ makes old nodes less attractive, since their segments are more likely to have been split by reassortment.

$\alpha(k)$, $\beta(k)$ and $p_{\text{all}}(k)$ are monotone piecewise-linear functions of $k$ (on the logit scale); $\theta_{\text{base}}$ and $\theta_{\text{reinf}}$ are scalars. Density and sampler are implemented side by side and tested against each other.

Two-lineage model (`two_lineage_model.py`, newest). A more mechanistic model: every true coalescence merges two ARG lineages. The model draws a primary pair of open nodes, decides for each whether it is intact (all its open segments on one lineage) or fragmented (spread over several lineages), derives the coalescing segments, and then lets the remaining segments join one by one (continue, new, or pair, with learned weights). This models the hidden lineages directly. A trainer exists (`train_two_lineage.py`), but the model has not yet been compared with the pairwise urn.

Training (`train_child_crp_on_networks.py`): every internal node of every simulated rARG is one training event, and the parameters maximise the mean event log-likelihood with minibatch Adam (JAX, optax). `generate_from_prior.py` samples rARGs from fitted models and `evaluate_rarg_prior.py` compares summary statistics (node and edge counts, heights) with the simulated reduced ARGs.

### Java port and open issues

`LearnedReducedNetworkPrior` reads `topology.json` (`alpha`, `beta`, `p_all`) and `waiting_time.json` (gamma per $k$) from `paramPath` and evaluates the same factorisation. It implements an older version of the topology model; `../phylophun/rephyner/java_port_notes.md` lists the differences (spline knot range, ordered vs unordered pair probability, the $p_{\text{all}}$ mixture, the model JSON version, fallback for untrained $k$). The integration is on hold until the Python model is settled. The `learnApproximator()` path (simulate, reduce, call Python) is a stub with placeholder script paths.

Open questions:

* Dependence on $\theta$. An approximation fitted at one $(\rho, N_e)$ cannot be used to estimate $\rho$. With constant $N_e$ the coalescent only sets the time scale, so $N_e$ can be handled by rescaling time. $\rho$ (more precisely $\rho N_e$) would have to become an input of $q_\phi$, trained over a range of values.
* Sampling times. With samples taken at different times, an interval can end in a sampling event instead of a coalescence. Neither the fit nor the density accounts for this yet.
* Validation. Matching summary statistics is necessary but weak. A direct check would compare $\log q_\phi(R)$ with an importance-sampling estimate of $\log p(R \mid \theta)$ on small rARGs (approach B). This would also measure how wrong the prior still is.
