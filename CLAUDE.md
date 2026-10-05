# Working conventions for this repo

- After every `git commit` + `git push` to a branch CI builds, always tell the user which CI build number to expect/wait for (e.g. "Собирается билд #305"), not just that it was pushed. Don't make them ask.

## App menus (overflow / ⋮)

- Every Activity has the shared menu: `UtilityMenu.inflate(this, menu)` in `onCreateOptionsMenu` and `UtilityMenu.handle(this, item.itemId)` in `onOptionsItemSelected`. A new screen without it is a bug.
- Order: the screen's own items first (default order 0), then the shared navigation, then the «Дополнительно» (More) submenu, and **«Журнал» (Log) always last**. `UtilityMenu` enforces this with explicit `order` values — never add an item with an order ≥ `ORDER_LOG`.
- Occasional, task-specific screens (Discovered candidates, Phrasebook, Benchmark) go into `MORE_ENTRIES`, not the top level.
