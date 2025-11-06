# Type-Level Mapping Between Index and Repo

This document describes the type-level mapping solution implemented for mapping `Index` enum values to their corresponding `Repo` types.

## Solution Implemented: Type Class Pattern (Solution 2)

### Files Created

1. **`modules/ingestor-core/src/main/scala/IndexMapping.scala`** - Core type-level mapping
2. **`modules/ingestor-core/src/main/scala/IndexMappingExample.scala`** - Usage examples

### Type Mapping

The solution provides a compile-time type-safe mapping between:

- `Index.Game` → `Repo[DbGame]`
- `Index.Forum` → `Repo[DbForum]`
- `Index.Ublog` → `Repo[DbUblog]`
- `Index.Study` → `Repo[(DbStudy, StudyChapterData)]`
- `Index.Team` → `Repo[DbTeam]`

### Core API

```scala
trait IndexMapping[I <: Index]:
  type Out
  def repo: Repo[Out]

object IndexMapping:
  // Type alias for clearer signatures
  type Aux[I <: Index, O] = IndexMapping[I] { type Out = O }

  // Match type for extracting the mapped type
  type Of[I <: Index] = I match
    case Index.Game.type => DbGame
    case Index.Forum.type => DbForum
    case Index.Ublog.type => DbUblog
    case Index.Study.type => (DbStudy, StudyChapterData)
    case Index.Team.type => DbTeam

  // Given instances (require Repo instances in scope)
  given gameMapping(using r: Repo[DbGame]): IndexMapping[Index.Game.type]
  given forumMapping(using r: Repo[DbForum]): IndexMapping[Index.Forum.type]
  given ublogMapping(using r: Repo[DbUblog]): IndexMapping[Index.Ublog.type]
  given studyMapping(using r: Repo[(DbStudy, StudyChapterData)]): IndexMapping[Index.Study.type]
  given teamMapping(using r: Repo[DbTeam]): IndexMapping[Index.Team.type]

  // Helper method
  def getRepo[I <: Index](index: I)(using mapping: IndexMapping[I]): Repo[mapping.Out]
```

### Usage Examples

#### 1. Generic function with type safety

```scala
def fetchData[I <: Index](
    index: I,
    since: Instant,
    until: Instant
)(using mapping: IndexMapping[I]): fs2.Stream[IO, Repo.Result[mapping.Out]] =
  mapping.repo.fetch(since, until)
```

#### 2. Type-safe wrapper

```scala
case class IndexOps[I <: Index](index: I)(using val mapping: IndexMapping[I]):
  def repo: Repo[mapping.Out] = mapping.repo
  def watch(since: Option[Instant]): fs2.Stream[IO, Repo.Result[mapping.Out]] =
    repo.watch(since)
```

#### 3. Creating wrappers for specific indices

```scala
def gameOps(using repo: Repo[DbGame]): IndexOps[Index.Game.type] =
  IndexOps(Index.Game)
```

#### 4. Pattern matching with type dispatch

```scala
def processAnyIndex(index: Index, repos: ...): IO[Unit] = index match
  case Index.Game =>
    given Repo[DbGame] = gameRepo
    gameOps.watch(None).compile.drain
  // ... other cases
```

#### 5. Direct usage with singleton types

```scala
def watchGameIndex(since: Option[Instant])(using gameRepo: Repo[DbGame]): fs2.Stream[IO, Repo.Result[DbGame]] =
  summon[IndexMapping[Index.Game.type]].repo.watch(since)
```

## Alternative Solutions Considered

### Solution 1: Match Types (Pure Type-Level)

```scala
type RepoType[I <: Index] = I match
  case Index.Game.type => DbGame
  case Index.Forum.type => DbForum
  // ...
```

**Pros:** Clean, native Scala 3
**Cons:** No runtime repo access, only type-level

### Solution 3: Enum Extension

**Pros:** Co-located with Index definition
**Cons:** Can't add type members to enum cases in current Scala

### Solution 4: Heterogeneous Registry

**Pros:** Clean API, separation of concerns
**Cons:** Still needs runtime registry

### Solution 5: Mirror-based

**Pros:** Exhaustiveness checking
**Cons:** More complex, more boilerplate

## Why Solution 2 (Type Class) Was Chosen

1. **Type Safety**: Compile-time checking of Index → Repo mappings
2. **Runtime Access**: Can hold actual Repo instances via implicit resolution
3. **Extensible**: Easy to add new mappings
4. **Testable**: Can provide test implementations via given instances
5. **Functional**: Follows FP patterns (type classes, implicit resolution)
6. **Works with existing code**: Integrates with the existing `Repo[A]` trait

## Important Notes

### Scala 3 Singleton Type Limitations

Due to Scala 3's type inference, when using `Index.Game` directly, it may be widened to `Index` instead of preserving the singleton type `Index.Game.type`. Solutions:

1. **Explicit typing**: `val index: Index.Game.type = Index.Game`
2. **Direct reference**: `summon[IndexMapping[Index.Game.type]]`
3. **Wrapper functions**: Use the `gameOps`, `forumOps`, etc. helper functions

### Usage Pattern

The recommended pattern is to create given Repo instances in your application setup, then the IndexMapping instances will be automatically available:

```scala
given Repo[DbGame] = GameRepo(...)
given Repo[DbForum] = ForumRepo(...)
// ... etc

// Now IndexMapping instances are automatically available
val ops = IndexOps(Index.Game) // compiles with type safety
```

## Compilation

The solution compiles successfully:

```bash
sbt ingestor-core/compile
# [success] Total time: 1 s
```

## See Also

- `modules/ingestor-core/src/main/scala/IndexMapping.scala` - Implementation
- `modules/ingestor-core/src/main/scala/IndexMappingExample.scala` - Usage examples
