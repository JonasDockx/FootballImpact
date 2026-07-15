# JSON parsing is isolated in a loader; the engine uses typed domain events

All Gson usage and all knowledge of StatsBomb's JSON shape live in a loader
layer. The loader translates each relevant raw event into an immutable typed
domain event (a `sealed interface MatchEvent` with records such as `StartingXI`,
`Substitution`, `Goal`, `RedCard`). The replay engine iterates only over
`List<MatchEvent>` and never touches Gson.

Chosen over replaying the engine directly across raw `JsonObject`s so that (a)
the eventual Gson→Jackson swap touches only the loader, (b) the engine is
type-safe and reads like the domain, and (c) the messy field-mapping knowledge is
confined to one place. The cost is more upfront translation code, accepted
because it also exercises sealed types, records, and pattern-matching `switch` —
squarely on the learning goal.
