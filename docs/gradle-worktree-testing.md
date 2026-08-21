# Running Gradle Against a `git worktree` Checkout of This Module

## The problem

Feature branches for this module are frequently checked out as a nested `git worktree` under
the main checkout, e.g.:

```
modules/com.etendoerp.go/                                    <- main checkout (some branch)
modules/com.etendoerp.go/.worktrees/feat-some-branch/         <- worktree for a feature branch
```

Running `./gradlew test` (or any other Gradle task) from the Etendo root **always resolves
`modules/com.etendoerp.go` to the main checkout**, never to a nested worktree — Gradle's module
scan is path-based, and `modules/com.etendoerp.go/.worktrees/<name>/` is a different path. So a
change made only inside the worktree is invisible to a plain `./gradlew test` run: the build
compiles and tests whatever the main checkout currently has on disk, which may be a completely
different branch.

**This is not merely "no-op" — it can actively fail the build.** Every worktree is a full
checkout of the same module, including its own copy of `build.gradle`/`tasks.gradle`. If Gradle's
directory scan for module sourcesets ever recurses into `.worktrees/` (it does, at least for the
custom tasks this module's `tasks.gradle` declares), it finds a **second** definition of the same
task name and fails before compiling anything:

```
Cannot add task 'prepareOnboardingSampledata' as a task with that name already exists
```

This is a `tasks.gradle`/task-registration collision, not a JVM/test-state issue — do not confuse
it with the worker-contamination symptoms in `docs/test-jvm-isolation.md` (a different, unrelated
class of Gradle problem in this same module).

## The workaround

There is no Gradle flag that fixes this — the worktree's nested `tasks.gradle` has to be out of
the scan entirely. The reliable way to actually compile/run a worktree branch's code today:

1. **Make sure the worktree branch is fully committed** (nothing to lose — this workaround
   removes the worktree directory).
2. From the **main checkout**, detach and check out the worktree branch's tip commit directly:
   ```bash
   cd modules/com.etendoerp.go
   git checkout <worktree-branch-tip-sha>   # e.g. the commit the worktree branch points to
   ```
3. Remove the worktree registration so its directory (and its duplicate `tasks.gradle`) is gone
   from disk during the run:
   ```bash
   git worktree remove modules/com.etendoerp.go/.worktrees/<name>
   ```
4. Run Gradle as normal from the Etendo root — it now resolves `modules/com.etendoerp.go` to the
   commit under test, with no nested worktree left to collide:
   ```bash
   ./gradlew test --tests "com.etendoerp.go.some.TestClass"
   ```
5. Restore the worktree and the main checkout's original branch:
   ```bash
   git worktree add modules/com.etendoerp.go/.worktrees/<name> <worktree-branch-name>
   cd modules/com.etendoerp.go
   git checkout <main-checkout-original-branch>
   ```

Step 1 matters: this workaround only leaves the branch as safe as its last commit — anything
uncommitted in the worktree is gone once it's removed in step 3, with no separate backup taken
by this procedure.

## Why not something less disruptive

- **Symlinking** `modules/com.etendoerp.go` to the worktree instead of the main checkout was
  considered and rejected: it repoints a directory other concurrent work in the same environment
  (other worktrees, the main checkout's own branch) may be relying on, for the length of an
  entire test run — much larger blast radius than a scoped detached-checkout that is reverted
  immediately after.
- **A separate Gradle settings/include per worktree** would need `settings.gradle` (owned by the
  Etendo Gradle plugin, not editable from this module) to know about `.worktrees/` paths, which
  it does not.

Until the module scan is changed to skip `.worktrees/` directories (not attempted here — it would
need to live in the shared Etendo Gradle plugin, outside this module), this detach/remove/restore
cycle is the accepted way to run Gradle against a worktree branch's actual content.
