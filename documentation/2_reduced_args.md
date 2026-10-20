# 2. Reduced ARGs: definition, reduction, likelihood, prior

Notation: $n$ sampled taxa with sampling times, $m$ segments $\mathcal{S} = \{1, \dots, m\}$. Time
runs backwards (heights), as in BEAST.

## 2.1 The full ARG

A full ARG $G$ under the coalescent with reassortment (the model of CoalRe) is a dated, rooted DAG
with three kinds of nodes:

* **leaves** (samples): no children, one parent edge;
* **coalescence nodes**: two child edges, one parent edge;
* **reassortment nodes**: one child edge, two parent edges.

Every edge $e$ carries the set $A_e \subseteq \mathcal{S}$ of segments that are ancestral to the
sample along it. At a coalescence the parent carries the union of the children's sets; at a
reassortment the child's set is partitioned between the two parents (in CoalRe each segment picks
a parent independently with probability ½).

The **local tree** $T_s(G)$ of segment $s$ is obtained by keeping only the edges with
$s \in A_e$ and suppressing the resulting degree-2 nodes. A coalescence node $v$ with children
$c_1, c_2$ is a node of $T_s(G)$ iff $s \in A_{c_1} \cap A_{c_2}$.

Model parameters of the full-ARG prior $p(G \mid \theta)$: effective population size $N_e(t)$
(any BEAST `PopulationFunction`) and reassortment rate $\rho$. Backwards in time, $k$ lineages
coalesce at total rate $\binom{k}{2}/N_e(t)$ and a lineage carrying $a$ segments undergoes an
*observable* reassortment (one that actually splits its segments) at rate
$\rho\,(1 - 2^{1-a})$. Likelihood parameters $\theta_D$: clock rate, substitution model, site
heterogeneity, per segment.

## 2.2 The reduced ARG

**Definition.** The reduced ARG $R = r(G)$ is the graph with

* **nodes** $V_R$: all leaves of $G$ plus every node of $G$ that is an internal node of at least
  one local tree $T_s(G)$, with its height $t_v$;
* **edges**: for $u, w \in V_R$, an edge $w \to u$ carrying the segment set
  $$S_{w \to u} = \{ s : w \text{ is the parent of } u \text{ in } T_s(G) \},$$
  present iff $S_{w\to u} \neq \emptyset$;
* **root edges**: for every node $u$ that is the root of some local tree, an edge from $u$ to
  "nothing" carrying $\{ s : u = \mathrm{root}(T_s(G)) \}$. A rARG generally has several root
  edges (segments can have different roots), which is why `ReducedNetwork` stores a list
  `rootEdges` rather than a single root.

Equivalently: $R$ is the **union of all local trees**, where nodes of different local trees are
identified when they correspond to the same event of $G$, and parallel edges are merged into one
edge labelled with the set of segments that use it.

So a recombination/reassortment event never appears as a node of $R$. Its effect is that a node can
have **several parent edges**: the segments that coalesced at $v$ next coalesce at *different*
nodes. Where along the way the underlying reassortment happened is not recorded.

### Structural properties

For an internal node $v$ let $c_v$ be the number of child edges, $p_v$ the number of parent edges
(root edges included), and $A_v = \bigcup_{e \in \mathrm{parents}(v)} S_e$ the segments carried
upward from $v$ (`NetworkNode.getActiveSegments()`). Then:

1. **Every node is a coalescence.** Each $s \in A_v$ is carried by exactly two child edges of $v$
   (local trees are binary) and by exactly one parent edge. In particular $c_v \geq 2$, and no
   segment merely "passes through" a node.
2. **Parent edges partition $A_v$.** This is the only place reticulation appears.
3. **$c_v$ can exceed 2.** Different segments can coalesce different pairs of children at the same
   node (see the example below). These are not polytomies of any local tree.
4. **Leaves** carry all segments, $A_v = \mathcal{S}$, and can have several parent edges.
5. **Minimality.** Every edge carries at least one segment and every node is needed by some local
   tree; removing anything changes a local tree. Parents are older than children.
6. **Local trees are recovered exactly:** following the edges that carry $s$ gives
   $T_s(R) = T_s(G)$.

`ReducedNetwork.isValid()` checks exactly these conditions; every operator must preserve them.

**Size.** With $n$ leaves, $R$ has between $n-1$ internal nodes (all local trees share all nodes:
$R$ is a tree) and $m(n-1)$ (all local trees disjoint). Counting edges from tips to root gives the
identity

$$
\sum_{v \text{ internal}} (c_v - 1) \;=\; (n - \#\text{root edges}) \;+\; \sum_{v} (p_v - 1),
$$

so, with $n$ fixed, the node count of $R$ is driven by the **total reticulation**
$\mathrm{ret}(R) = \sum_v (p_v - 1) = \#\text{edges} - \#\text{nodes}$ (root edges counted). This is
the complexity measure used to compare rephyner with CoalRe in the simulation study.

### A small example

Three taxa $a, b, c$, two segments. In $G$, the lineage of $a$ reassorts at time $t_0$; its
segment-1 parent coalesces with $b$ at $t_1$ (node $x$), its segment-2 parent with $c$ at $t_2$
(node $y$), and all lineages finally meet at the root $z$ at $t_3$. Local trees:
$T_1 = ((a,b)_x, c)_z$ and $T_2 = ((a,c)_y, b)_z$.

| edge | segments |
|---|---|
| $x \to a$, $x \to b$ | {1} |
| $y \to a$, $y \to c$ | {2} |
| $z \to x$, $z \to c$ | {1} |
| $z \to y$, $z \to b$ | {2} |
| root edge of $z$ | {1, 2} |

$a$ has two parent edges ($p_a = 2$), $z$ has four child edges ($c_z = 4$), and the reassortment
time $t_0 \in (0, t_1)$ appears nowhere: every ARG with $t_0$ anywhere in that interval (and any
number of extra invisible loops) reduces to the same $R$.

## 2.3 The reduction map $r$

`rephyner.network.NetworkReducer.reduce(coalre.network.Network)` computes $r(G)$ in one bottom-up
pass. Each node of $G$ returns a list of **active lineages** $(u, S)$: a node $u$ of $R$ together
with the segments of $u$ that have not coalesced yet. At a node $v$ of $G$:

1. Collect the active lineages of all children, restricting each one's segments to the child
   edge's $A_e$, and merge lineages that come from the same $R$-node via different paths (this is
   what collapses invisible loops).
2. Let $D_v$ be the segments that occur on **two or more** of these lineages.
3. If $D_v = \emptyset$, $v$ is dropped and all lineages are passed up unchanged.
4. Otherwise create the $R$-node $v'$ at $t_v$; for each lineage $(u, S)$ add the edge
   $v' \to u$ with segments $S \cap D_v$ and pass $(u, S \setminus D_v)$ up if non-empty; pass
   $(v', D_v)$ up as a new lineage.

Lineages still active at the root of $G$ become the root edges of $R$. Because the recursion runs
over all nodes of $G$, large ARGs need a larger JVM stack (`-Xss`), otherwise the reducer overflows
silently mid-run.

The reducer is used to (i) initialise the MCMC state from a simulated CoalRe ARG
(`SimulatedReducedCoalescentNetwork`), (ii) log rARG statistics of CoalRe runs so both methods can
be compared on the same quantities (`ReducedNetworkStatsLogger fullNetwork=...`), and (iii) produce
training data for the learned prior.

**Equivalence classes.** The map $r$ is many-to-one. For a rARG $R$ the set
$\mathcal{E}(R) = \{G : r(G) = R\}$ contains ARGs that differ in the timing of reassortments,
in invisible coalescences between lineages carrying disjoint segments, and in arbitrarily many
invisible reassortment loops. All of them have the same likelihood.

## 2.4 Parameters of the rARG model

| Symbol | Meaning | In BEAST |
|---|---|---|
| $R$ | topology, node heights, segment sets on edges | `ReducedNetwork` (a `StateNode`) |
| $T_1, \dots, T_m$ | local trees, deterministic functions of $R$ | one `Tree` per segment, kept in sync by the operators |
| $\theta_D$ | clock rate, substitution model, site model per segment | standard BEAST parameters |
| $N_e(t)$ | effective population size | `populationModel` input of the prior |
| $\rho$ | reassortment rate of the *full* ARG model | only in the exact prior and the learned prior (as a simulation setting) |
| $\alpha$, $\lambda$ | concentration and reticulation penalty of the stand-in prior `ReducedCoalescent` | fixed `RealParameter`s so far |

## 2.5 The likelihood

The data of segment $s$ evolve along its local tree, so the likelihood is the same product of
tree likelihoods as for the full ARG:

$$
p(D \mid R, \theta_D) \;=\; \prod_{s=1}^{m} p\big(D_s \mid T_s(R), \theta_D\big),
$$

each factor a standard Felsenstein pruning likelihood (`TreeLikelihood` or
`ThreadedTreeLikelihood` on the segment trees). Since $T_s(R) = T_s(G)$, nothing about the
likelihood is approximate, and the per-segment caching of BEAST tree likelihoods applies
unchanged: an operator that only changes $T_s$ only invalidates segment $s$.

(For within-sequence recombination the same holds with one local tree per non-recombining
interval; rephyner currently only implements the segmented/reassortment case.)

## 2.6 The prior — the difficult part

The full-ARG prior induces the marginal

$$
p(R \mid \theta) \;=\; \int_{\mathcal{G}} \mathbf{1}\big[r(G) = R\big]\; p(G \mid \theta)\; dG
\;=\; \int_{\mathcal{E}(R)} p(G \mid \theta)\; dG .
$$

Writing $G = (R, Z)$, where $Z$ is everything $r$ discards (reassortment events and their times,
invisible coalescences, invisible loops, and the assignment of $R$'s edges to lineages of $G$),
this is $p(R \mid \theta) = \int p(R, Z \mid \theta)\, dZ$. Why this is hard:

* **$Z$ has variable dimension** and is unbounded (any number of invisible loops).
* **The lineage state is hidden.** The rate of the next observable event depends on how segments
  are spread over lineages of $G$, but the correspondence between edges of $R$ crossing a time $t$
  and lineages of $G$ at $t$ is many-to-many: one $R$-edge can be spread over several $G$-lineages
  (its segments reassorted apart and will re-meet invisibly), and several $R$-edges can share one
  $G$-lineage (they coalesced invisibly). The process on $R$ is therefore not Markov in any small
  state: the past of $R$ carries information about the hidden configuration.
* **Events are untimed.** A reassortment that splits a node's segments has no time in $R$; the
  density must integrate over it.
* **Observable coalescences** only happen between lineages that share a segment. The rate at which
  two $R$-edges coalesce visibly depends on the hidden configuration of both.

Requirements on any replacement for $p(R \mid \theta)$ used inside MCMC:

1. **Proper**, ideally with a matching simulator (so it can be validated by sampling from the prior).
2. **Invariant to what $R$ does not record** — no term may depend on where along an edge a
   reassortment happened.
3. **Cheap**: evaluated at every MCMC step, so $O(\#\text{events})$ or close.
4. **Faithful**: close to the true marginal over the region of rARG space the posterior visits,
   otherwise the posterior is biased (prior misspecification).

Approaches are discussed in [3_prior.md](3_prior.md).
