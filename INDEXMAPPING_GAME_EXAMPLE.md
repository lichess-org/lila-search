# Applying IndexMapping Pattern to Game Index

This document shows how the `IndexMapping` type-level pattern was applied to the Game index in the real codebase.

## Location

**File:** `modules/ingestor-cli/src/main/scala/indexer.scala`

## Before (Traditional Pattern Matching)

```scala
case Index.Game =>
  GameRepo(res.lichess, config.ingestor.game).map:
    Ingestor.index(index, _, store, elastic, since, until, watch, dry)
```

This approach:
- Directly uses the repo from `GameRepo`
- No type-level guarantees about Index ↔ Repo mapping
- Pattern is repeated for each index type

## After (With IndexMapping Pattern)

```scala
case Index.Game =>
  // Using the new type-safe approach with IndexMapping!
  val gameIndex: Index.Game.type = Index.Game
  GameRepo(res.lichess, config.ingestor.game).flatMap: repo =>
    given Repo[DbGame] = repo
    val mapping = summon[IndexMapping[Index.Game.type]]
    val ingestor = Ingestor.index(gameIndex, mapping.repo, store, elastic, since, until, watch, dry)
    putMappingsIfNotExists(res.elastic, gameIndex) *>
      ingestor.run() *>
      refreshIndexes(res.elastic, gameIndex).whenA(opts.refresh)
```

## Key Improvements

### 1. Type Safety
```scala
val gameIndex: Index.Game.type = Index.Game  // Preserve singleton type
```
The singleton type `Index.Game.type` is explicitly preserved, which allows the compiler to verify the type-level mapping.

### 2. IndexMapping Resolution
```scala
given Repo[DbGame] = repo
val mapping = summon[IndexMapping[Index.Game.type]]
```
By providing a `given Repo[DbGame]`, the `IndexMapping[Index.Game.type]` instance is automatically resolved via the type class pattern.

### 3. Type-Level Verification
```scala
val ingestor = Ingestor.index(gameIndex, mapping.repo, ...)
```
The compiler verifies at compile-time that:
- `Index.Game.type` maps to `Repo[DbGame]`
- `mapping.repo` has the correct type `Repo[DbGame]`
- The `Indexable[DbGame]` instance exists

## Benefits Demonstrated

### Compile-Time Safety
If someone tries to use the wrong repo type:
```scala
given Repo[DbForum] = wrongRepo  // Compile error!
val mapping = summon[IndexMapping[Index.Game.type]]
```
This will **fail to compile** because there's no `IndexMapping[Index.Game.type]` that takes `Repo[DbForum]`.

### Explicit Type Relationships
The code now explicitly shows:
```
Index.Game.type → IndexMapping[Index.Game.type] → Repo[DbGame]
```

### Documentation Through Types
The pattern serves as living documentation that `Index.Game` always works with `DbGame` repositories.

## Comparison: Type Safety Analysis

### Traditional Approach
```scala
def processGame(repo: Repo[DbForum]): Ingestor =
  Ingestor.index(Index.Game, repo, ...)  // ❌ Compiles but wrong!
```

### IndexMapping Approach
```scala
def processGame(repo: Repo[DbForum]): Ingestor =
  given Repo[DbForum] = repo
  val mapping = summon[IndexMapping[Index.Game.type]]  // ✅ Compile error!
  Ingestor.index(Index.Game, mapping.repo, ...)
```

## Important Notes

### Singleton Type Preservation
The key to making this pattern work is preserving the singleton type:

```scala
// ❌ Wrong - type widened to Index
val index = Index.Game
val mapping = summon[IndexMapping[???]]  // What type to use?

// ✅ Correct - type preserved as Index.Game.type
val gameIndex: Index.Game.type = Index.Game
val mapping = summon[IndexMapping[Index.Game.type]]  // Clear!
```

### Pattern Matching Limitations
Inside a pattern match, types are widened:

```scala
index match
  case Index.Game =>
    // Here, `Index.Game` has type Index, not Index.Game.type
    // So we need to explicitly create a singleton-typed variable
    val gameIndex: Index.Game.type = Index.Game
```

## Future Work

This pattern can be extended to all other index types:

- ✅ `Index.Game` → Uses IndexMapping
- ⚪ `Index.Forum` → Could use IndexMapping
- ⚪ `Index.Ublog` → Could use IndexMapping
- ⚪ `Index.Study` → Could use IndexMapping
- ⚪ `Index.Team` → Could use IndexMapping

## Compilation

The changes compile successfully:

```bash
$ sbt ingestor-cli/compile
[success] Total time: 4 s
```

## See Also

- `modules/ingestor-core/src/main/scala/IndexMapping.scala` - Core implementation
- `modules/ingestor-core/src/main/scala/IndexMappingExample.scala` - Usage examples
- `TYPE_LEVEL_MAPPING_SOLUTIONS.md` - Design decisions
