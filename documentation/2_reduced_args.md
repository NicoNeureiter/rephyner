# 2. Reduced ARGs: definition, reduction, likelihood, prior

Notation: $n$ sampled taxa with sampling times, $m$ segments $\mathcal{S} = \{1, \dots, m\}$. Time runs backwards (heights), as in BEAST.

## 2.1 The full ARG

A full ARG $G$ under the coalescent with reassortment (the model of CoalRe) is a dated, rooted, directed acyclic graph with three kinds of nodes:

* leaves (samples): no children, one parent edge;
* coalescence nodes: two child edges, one parent edge;
* reassortment nodes: one child edge, two parent edges.

Every edge $e$ carries the set $A_e \subseteq \mathcal{S}$ of segments that are ancestral to the sample along it. At a coalescence the parent carries the union of the children's sets. At a reassortment the child's set is split between the two parents (in CoalRe each segment picks a parent independently with probability ½).

The local tree $T_s(G)$ of segment $s$ is obtained by keeping only the edges with $s \in A_e$ and removing the resulting nodes with a single child. A coalescence node $v$ with children $c_1, c_2$ is a node of $T_s(G)$ if and only if $s \in A_{c_1} \cap A_{c_2}$.

Parameters of the full-ARG prior $p(G \mid \theta)$: effective population size $N_e(t)$ (any BEAST `PopulationFunction`) and reassortment rate $\rho$. Backwards in time, $k$ lineages coalesce at total rate $\binom{k}{2}/N_e(t)$, and a lineage carrying $a$ segments undergoes an observable reassortment (one that actually splits its segments) at rate $\rho\,(1 - 2^{1-a})$. Likelihood parameters $\theta_D$: clock rate, substitution model and site model, per segment.

## 2.2 The reduced ARG

Definition. The reduced ARG $R = r(G)$ is the graph with

* nodes $V_R$: all leaves of $G$ plus every node of $G$ that is an internal node of at least one local tree $T_s(G)$, with its height $t_v$;
* edges: for $u, w \in V_R$, an edge $w \to u$ carrying the segment set $S_{w \to u} = \{ s : w \text{ is the parent of } u \text{ in } T_s(G) \}$, present if this set is not empty;
* root edges: for every node $u$ that is the root of some local tree, an edge from $u$ to nowhere carrying $\{ s : u = \mathrm{root}(T_s(G)) \}$. A rARG generally has several root edges, because segments can have different roots. This is why `ReducedNetwork` stores a list `rootEdges` instead of a single root.

Equivalently, $R$ is the union of all local trees, where nodes of different local trees are identified when they correspond to the same event in $G$, and parallel edges are merged into one edge labelled with the set of segments that use it.

So a reassortment (or recombination) event never appears as a node of $R$. Its only trace is that a node can have several parent edges: the segments that coalesced at $v$ next coalesce at different nodes. Where along the way the reassortment happened is not recorded.

### Structural properties

For an internal node $v$ let $c_v$ be the number of child edges, $p_v$ the number of parent edges (root edges included), and $A_v = \bigcup_{e \in \mathrm{parents}(v)} S_e$ the segments carried upward from $v$ (`NetworkNode.getActiveSegments()`). Then:

1. Every node is a coalescence. Each $s \in A_v$ is carried by exactly two child edges of $v$ (local trees are binary) and by exactly one parent edge. In particular $c_v \geq 2$, and no segment merely passes through a node.
2. The parent edges partition $A_v$. This is the only place where reticulation appears.
3. $c_v$ can exceed 2: different segments can coalesce different pairs of children at the same node (see the example below). These are not polytomies in any local tree.
4. Leaves carry all segments, $A_v = \mathcal{S}$, and can have several parent edges.
5. Minimality: every edge carries at least one segment and every node is needed by some local tree, so removing anything changes a local tree. Parents are older than children.
6. The local trees are recovered exactly: following the edges that carry $s$ gives $T_s(R) = T_s(G)$.

`ReducedNetwork.isValid()` checks exactly these conditions, and every operator must preserve them.

Size. With $n$ leaves, $R$ has between $n-1$ internal nodes (all local trees share all nodes, so $R$ is a tree) and $m(n-1)$ (all local trees disjoint). Counting edges from tips to root gives

$$
\sum_{v \text{ internal}} (c_v - 1) \;=\; (n - \#\text{root edges}) \;+\; \sum_{v} (p_v - 1),
$$

so for fixed $n$ the node count of $R$ is driven by the total reticulation $\mathrm{ret}(R) = \sum_v (p_v - 1) = \#\text{edges} - \#\text{nodes}$ (root edges counted). This is the complexity measure used to compare rephyner with CoalRe in the simulation study.

### A small example

Three taxa $a, b, c$, two segments. In $G$, the lineage of $a$ reassorts at time $t_0$. Its segment-1 parent coalesces with $b$ at $t_1$ (node $x$), its segment-2 parent with $c$ at $t_2$ (node $y$), and all lineages finally meet at the root $z$ at $t_3$. Local trees: $T_1 = ((a,b)_x, c)_z$ and $T_2 = ((a,c)_y, b)_z$.

| edge | segments |
|---|---|
| $x \to a$, $x \to b$ | {1} |
| $y \to a$, $y \to c$ | {2} |
| $z \to x$, $z \to c$ | {1} |
| $z \to y$, $z \to b$ | {2} |
| root edge of $z$ | {1, 2} |

$a$ has two parent edges ($p_a = 2$), $z$ has four child edges ($c_z = 4$), and the reassortment time $t_0 \in (0, t_1)$ appears nowhere. Every ARG with $t_0$ anywhere in that interval (and any number of extra invisible loops) reduces to the same $R$.

## 2.3 The reduction map $r$

`rephyner.network.NetworkReducer.reduce(coalre.network.Network)` computes $r(G)$ in one pass from the tips to the root. Each node of $G$ returns a list of active lineages $(u, S)$: a node $u$ of $R$ together with the segments of $u$ that have not coalesced yet. At a node $v$ of $G$:

1. Collect the active lineages of all children, restricting each one's segments to the child edge's $A_e$, and merge lineages that come from the same $R$-node via different paths (this removes invisible loops).
2. Let $D_v$ be the segments that occur on two or more of these lineages.
3. If $D_v$ is empty, drop $v$ and pass all lineages up unchanged.
4. Otherwise create the $R$-node $v'$ at $t_v$. For each lineage $(u, S)$ add the edge $v' \to u$ with segments $S \cap D_v$ and pass $(u, S \setminus D_v)$ up if not empty. Pass $(v', D_v)$ up as a new lineage.

Lineages still active at the root of $G$ become the root edges of $R$. The reducer is recursive, so large ARGs need a larger JVM stack (`-Xss`), otherwise it overflows silently in the middle of a run.

The reducer is used to (i) initialise the MCMC state from a simulated CoalRe ARG (`SimulatedReducedCoalescentNetwork`), (ii) log rARG statistics of CoalRe runs, so both methods can be compared on the same quantities (`ReducedNetworkStatsLogger fullNetwork=...`), and (iii) produce training data for the learned prior.

Equivalence classes. The map $r$ is many-to-one. For a rARG $R$ the set $\mathcal{E}(R) = \{G : r(G) = R\}$ contains ARGs that differ in the timing of reassortments, in invisible coalescences between lineages carrying disjoint segments, and in arbitrarily many invisible reassortment loops. All of them have the same likelihood.

## 2.4 Parameters of the rARG model

| Symbol | Meaning | In BEAST |
|---|---|---|
| $R$ | topology, node heights, segment sets on edges | `ReducedNetwork` (a `StateNode`) |
| $T_1, \dots, T_m$ | local trees, determined by $R$ | one `Tree` per segment, kept in sync by the operators |
| $\theta_D$ | clock rate, substitution model, site model per segment | standard BEAST parameters |
| $N_e(t)$ | effective population size | `populationModel` input of the prior |
| $\rho$ | reassortment rate of the full ARG model | only in the exact prior and the learned prior (as a simulation setting) |
| $\alpha$, $\lambda$ | concentration and reticulation penalty of the stand-in prior `ReducedCoalescent` | fixed `RealParameter`s so far |

## 2.5 The likelihood

The data of segment $s$ evolve along its local tree, so the likelihood is the same product of tree likelihoods as for the full ARG:

$$
p(D \mid R, \theta_D) \;=\; \prod_{s=1}^{m} p\big(D_s \mid T_s(R), \theta_D\big),
$$

each factor a standard Felsenstein likelihood (`TreeLikelihood` or `ThreadedTreeLikelihood` on the segment trees). Since $T_s(R) = T_s(G)$, the likelihood is exact, and BEAST's per-tree caching works as usual: an operator that only changes $T_s$ only requires recomputing segment $s$.

(For recombination within a sequence the same holds with one local tree per non-recombining interval. rephyner currently only implements the segmented/reassortment case.)

## 2.6 The prior: the difficult part

The full-ARG prior implies the rARG prior

$$
p(R \mid \theta) \;=\; \int_{\mathcal{G}} \mathbf{1}\big[r(G) = R\big]\; p(G \mid \theta)\; dG
\;=\; \int_{\mathcal{E}(R)} p(G \mid \theta)\; dG .
$$

Writing $G = (R, Z)$, where $Z$ is everything $r$ discards (reassortment events and their times, invisible coalescences, invisible loops, and the assignment of $R$'s edges to lineages of $G$), this is $p(R \mid \theta) = \int p(R, Z \mid \theta)\, dZ$. This is hard because:

* $Z$ has no fixed size and is unbounded (any number of invisible loops).
* The lineages of $G$ are hidden. The rate of the next observable event depends on how segments are spread over the lineages of $G$, but the edges of $R$ at a time $t$ do not correspond one-to-one to lineages of $G$ at $t$. One $R$-edge can be spread over several $G$-lineages (its segments reassorted apart and will meet again invisibly), and several $R$-edges can share one $G$-lineage (they coalesced invisibly). So the rate of the next event in $R$ depends on the whole history of $R$, not just on its current state.
* Reassortments have no time. A reassortment that splits a node's segments is not dated in $R$, so the density has to integrate over its time.
* Observable coalescences only happen between lineages that share a segment, so the rate at which two $R$-edges coalesce visibly depends on the hidden lineages behind both.

Requirements for any replacement of $p(R \mid \theta)$ used in MCMC:

1. Proper (normalised), ideally with a matching simulator, so it can be validated by sampling from the prior.
2. Independent of what $R$ does not record: no term may depend on where along an edge a reassortment happened.
3. Cheap: it is evaluated at every MCMC step, so its cost should be roughly linear in the number of events.
4. Accurate: close to the true $p(R \mid \theta)$ where the posterior puts its mass, otherwise the posterior is biased.

Approaches are discussed in [3_prior.md](3_prior.md).
