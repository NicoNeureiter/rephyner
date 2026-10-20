# 1. The idea: more efficient ARG inference by reducing the ARG

## The problem

An ancestral recombination graph (ARG) describes the joint genealogy of sequences that exchange
genetic material: by recombination within a sequence, or by **reassortment** of whole segments in
segmented viruses such as influenza (the case rephyner currently targets). Backwards in time, a
lineage can *coalesce* with another (two lineages merge into their common ancestor) or *reassort*
(one lineage splits into two parents, each inheriting a subset of the segments). Every segment
$s$ has its own **local tree** $T_s$, obtained by following the segment through the graph, and
the sequence data of segment $s$ depend on the ARG only through $T_s$.

Bayesian ARG inference (as in CoalRe) samples the full ARG $G$ with MCMC. That is hard:

* the state space is high-dimensional, discrete-continuous and of variable dimension;
* the posterior is rugged, because small changes to $G$ can change several local trees at once;
* **a large part of $G$ is not identifiable from the data.** Many events in $G$ do not change any
  local tree:
  * the *timing* of a reassortment event is irrelevant as long as the order of the surrounding
    local-tree events is unchanged;
  * a coalescence of two lineages that carry *disjoint* sets of ancestral segments is invisible in
    every local tree;
  * a reassortment followed by an invisible re-coalescence of the two parents (a "loop") changes
    nothing at all.

The MCMC therefore spends much of its effort moving through regions of $G$-space that the
likelihood cannot distinguish, and its operators must jointly propose changes to the parts of $G$
that matter and the parts that do not.

## The idea

Replace the full ARG by the **reduced ARG (rARG)** $R = r(G)$: the graph that contains exactly the
nodes and edges that appear in at least one local tree. $R$ keeps everything the likelihood needs
(all local trees are recovered from $R$) and nothing else. Formally (see
[2_reduced_args.md](2_reduced_args.md)), many ARGs map to the same rARG, and the rARG is the
identifiable part of the ARG with respect to the data.

The hope is that sampling $R$ instead of $G$

1. **shrinks the state space**: every equivalence class
   $\mathcal{E}(G) = \{G' : r(G') = r(G)\}$ of ARGs collapses onto a single rARG, so the chain no
   longer has to explore unidentifiable directions;
2. **makes the posterior less rugged**: unobservable events are integrated out instead of being
   sampled, so there are no "walls" between configurations that explain the data equally well;
3. **allows operators phrased directly in terms of local trees**: an operator can move a subtree
   in one local tree and let the rARG adapt minimally, instead of having to construct and destroy
   reassortment events to achieve the same change.

The more segments and the more reassortment, the larger the equivalence classes and so the larger
the expected gain. Conversely, without reassortment the rARG is just a tree and rephyner should
behave like a tree sampler.

## The price: the prior

The likelihood carries over to $R$ unchanged (it is a product of tree likelihoods over local trees).
The prior does not. A well-motivated prior is available for full ARGs (e.g. the coalescent with
reassortment), but the induced prior on rARGs is the marginal

$$
p(R \mid \theta) = \int \mathbf{1}[r(G) = R]\; p(G \mid \theta)\; dG ,
$$

an integral over all ARGs that reduce to $R$. It has no known closed form and no simple
generative process. **Making the rARG approach work therefore requires (a) a good, cheap
approximation of this prior and (b) MCMC operators that move efficiently through rARG space.**
Both are open problems and are the two main fronts of the project:

* the prior: [3_prior.md](3_prior.md);
* the operators: [4_inference.md](4_inference.md).

## Two questions to answer

The project is organised around two questions, to be answered in this order:

* **Q1 (validity).** Does the rARG sampler sample the distribution it claims to?
  (Operator correctness, prior correctness.)
* **Q2 (efficiency).** At equal compute, does the rARG sampler mix better than CoalRe, and in which
  regime (number of taxa, segments, reassortment rate, information per segment)?

If the sampler does not mix better than CoalRe even with a cheap stand-in prior, finishing a
principled prior approximation is not worth it. That is why the current simulation study
([5_status.md](5_status.md)) evaluates the sampler under a stand-in prior first.
