# Community 252 Git dashboard coroutine correction

GoForge's ordinary-project native acceptance reproduced a first-open Git branch
tree exception on Community **252.28539.97**: `TreeUtil.expandAll` requires a read
action, but the dashboard's `launchOnShow` block runs on `Dispatchers.UI`, which
forbids that lock. The saved log identifies `FilteringBranchesTree.updateTree`.
Manual Expand All succeeds; the problem is automatic tree initialization.

The public, version-pinned [JetBrains source](https://github.com/JetBrains/intellij-community/blob/idea/252.28539.97/plugins/git4idea/src/git4idea/ui/branch/dashboard/BranchesTree.kt)
already uses `withContext(Dispatchers.EDT)` for the adjacent speed-search tree
operation for the same read-action reason. This independent compatibility patch
uses that API at the dashboard's one original `launchOnShow` block. It preserves
the component show/hide job, modality, cancellation, original update operation,
and listener registration/`finally` cleanup. Passing EDT as `launchOnShow`'s
context alone would not work: this platform replaces it with UI internally.

`GitDashboardEdt` wraps only that suspend function with `withContext(EDT)`.
`PatchGitDashboard` inserts one Function2-to-Function2 call in the pinned
constructor. The original coroutine class, tree logic, listeners, and remaining
archive entries remain byte-for-byte unchanged. No platform-wide dispatcher,
feature gate, error suppression, or proprietary GoLand implementation is used.

Run the patch only on an owned packaging stage:

```sh
python3 scripts/patch-git-dashboard.py --platform-home path/to/owned/platform --javac .tools/jdk/bin/javac
```

The wrapper pins the exact platform build, complete upstream Git JAR, target and
coroutine class hashes, and all unchanged archive contents. It rejects linked,
signed, duplicate, changed, or partially patched input. Reapplying identical
sources is a verified no-op; source changes require a fresh pristine input.
Rewriting removes the invalidated JetBrains optimized ZIP index/comment and
retains the ordinary ZIP central directory, as in the existing GoForge platform
patch. The JSON receipt is `goforge-git-dashboard-patch.json` in Resources on
macOS and the platform root on Windows; another receipt is embedded in the JAR.

The focused regression accepts either an existing platform or an official
Windows ZIP; it copies the necessary files and never starts an IDE or Gradle:

```sh
python3 scripts/test-patch-git-dashboard.py --platform-home path/to/platform
python3 scripts/test-patch-git-dashboard.py --platform-zip path/to/ideaIC.zip
```

It verifies exact archive changes, unchanged original coroutine bytes,
idempotence, rejection before writes, ASM/JVM loading, and the same real
TreeUtil read-action assertions before and after the callsite hook. Its headless
fixture uses the actual platform UI/EDT dispatchers, real nested locks, real
isolated kernel, and actual `launchOnShow` on a Swing component. Show/hide,
re-show, cancellation while suspended/before shown, hierarchy/model listener
cleanup, and error propagation are checked. Only Application service routing
and OS event delivery are supplied by the fixture. The Git model itself is not
bootstrapped; native Git tool-window and reopen acceptance remain separate.

These patch sources are original GoForge code under Apache-2.0. The transformed
Community class retains JetBrains' applicable Apache-2.0 copyright/license.
