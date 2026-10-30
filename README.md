# common-graph

Dependency graphs for true-sim. Package `com.truesim.common.graph`.

This is the graph core that true-sim's two graphs share:

- the engine's **frame graph** (GPU passes);
- the **module graph** (game logic, planned).

Nothing here knows about GPUs, frames or game state; those belong to the graph built on top. See the engine's
`docs/frame-graph-v2.md`, §4.16.

## Usage

```kotlin
dependencies {
    implementation("com.truesim.common:common-graph:0.1.0")
}
```

## What it has

- **`Resolution<K, P>`:** producers resolved by the keys of their results, built lazily.
  - Requiring a key builds the producer that provides it, then every producer that modifies it, in order.
  - Producers nothing needs are never built.
  - Requests (shared work, compared by equality) are built once.
  - Results have one owner: a modifier takes a key's result to change, and everyone requiring the key gets its last
    version.
  - Cycles, missing providers and misuse are reported with the producers' names.
- **`NodeGraph` → `Schedule`:** nodes and the numbered resources they read and write, compiled into an immutable
  schedule.
  - **Culling:** only nodes that something needs run. A node is needed if it has side effects or writes a resource
    that is kept or read later.
  - **Order:** the order the nodes were added.
  - **Lifetimes:** each resource's first and last position.
  - **Dependencies:** each node's dependencies (read after write, write after write, write after read). The module
    graph's parallel scheduling uses them.
  - **Switches:** chosen per run (`active(mask, out)`) without compiling again, and allocation-free.
- **`Id<T>` and `IdRegistry`:** typed, namespaced ids (`acme.fog`), declared once as constants and used by reference,
  so a misspelt name does not compile. The registry rejects two ids of one name. Names read from data resolve back to
  their ids, with a "did you mean…" suggestion for misspelt ones.
- **`Snapshot`:** what a build may read, taken at once, as an open set of typed entries under ids. Equal snapshots
  build the same graph, and their `hash()` keys caches.

```java
NodeGraph g = new NodeGraph();
int color = g.resource();
int draw = g.node();
g.write(draw, color);
int bloom = g.node();
g.read(bloom, color).write(bloom, color).switchedBy(bloom, 0);
g.keep(color);

Schedule s = g.compile();      // draw, then bloom; color lives from position 0 to 1
int[] active = new int[s.size()];
int n = s.active(0L, active);  // bloom switched off: only draw runs
```
