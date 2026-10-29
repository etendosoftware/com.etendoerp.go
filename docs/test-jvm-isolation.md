# DAL Integration Tests — JVM Isolation

## Why this exists

Most tests in `src-test/` are mocked unit tests: they stub `OBDal`, `OBContext` and friends with
`mockStatic` and never touch a database. A handful extend `OBBaseTest`, whose `@BeforeClass`
initializes the Openbravo DAL layer against the real database.

Those two kinds of test do not coexist well inside a single JVM. When an `OBBaseTest` subclass
lands in a Gradle test worker that has already run mocked handler tests, it inherits that worker's
polluted static state. This shows up as **two different symptoms** — recognising both matters,
because the second one does not look like an infrastructure problem at all.

### Symptom 1 — `initializationError` before any test method runs

The Apache JDBC pool singleton is left without a datasource, or `OBConfigFileProvider`'s file
location comes back `null`, and DAL initialization dies in `@BeforeClass`:

```
ReactivatePaymentHandlerRemoveIntegrationTest > initializationError FAILED
  OBException: Failed to load reference classes
   └─ NPE: Cannot invoke "DataSource.getConnection()" because "datasource" is null
      at OBBaseTest.staticInitializeDalLayer(OBBaseTest.java:447)
```

```
CreateDraftInvoiceHandlerNegativeQuantityIntegrationTest > initializationError FAILED
  java.lang.NullPointerException
   └─ Objects.requireNonNull → Paths.get(null)
      at OBBaseTest.initializeDisabledTestCases(OBBaseTest.java:457)
```

The second variant is the same class of leak seen from the other end: the worker never logged
`OBConfigFileProvider - Found provider config file`, so `getFileLocation()` returned `null` and
`Paths.get` blew up. A build log that shows more `Dal layer initialized` lines than
`Found provider config file` lines is the tell. The cause of this second variant is known and
fixed JVM-wide. See [Openbravo.properties preload](#openbravoproperties-preload-the-config-file-location-variant).

### Symptom 2 — the test passes, then fails on the admin-mode check

All assertions pass — the log even contains `*** Finished test case: …` — and the failure is thrown
afterwards by `OBBaseTest`'s `TestWatcher`:

```
CreateGoodsReceiptHandlerNegativeQuantityIntegrationTest > createReceiptLines… FAILED
  java.lang.IllegalStateException: Test case should take care of reseting admin mode correctly
  in a finally block, use OBContext.restorePreviousMode
      at OBBaseTest$1.finished(OBBaseTest.java:146)
```

This is **not** normally the failing test's fault. `OBContext.adminModeStack` is a
`private static ThreadLocal` (`OBContext.java:124`) that `OBContext.setOBContext()` does **not**
reset, and Gradle reuses a single `Test worker` thread across unrelated classes. An unbalanced
`setAdminMode()` leaked by any earlier test in that worker is therefore charged to whichever
`OBBaseTest` subclass happens to run next. Note that `finished()` calls
`OBContext.clearAdminModeStack()` as part of throwing, so the *next* class in the same worker
usually passes — which is why the blame appears to move around between builds.

Before assuming your test leaks admin mode, check whether it (or the production code it exercises)
calls `setAdminMode()` at all. If nothing in the call chain does, it is worker contamination and
the class belongs in `isolatedDalTests`.

### Both symptoms are leaked JVM state

Neither is a missing or misconfigured database — the other `OBBaseTest`
subclasses in this module initialize the DAL without trouble in the very same build.

It is also not a flake in the usual sense. Gradle's distribution of classes across workers is
stable for a given set of test classes, so once a class lands in a poisoned worker it fails on
every run. It appears and disappears when test classes are *added or removed* elsewhere in the
module, which is what makes it so confusing to chase: an unrelated PR that adds test classes can
"break" a test it never touched.

## How it is fixed

`build.gradle` declares a `goIsolatedDalTest` task that runs the affected classes with
`forkEvery = 1`, so each one gets a pristine JVM. The class is excluded from the root `test` task
and the new task is wired as `finalizedBy` it.

Consequences worth knowing:

- **It runs in CI automatically.** `finalizedBy` means anything invoking `:test` also runs it, so
  the Jenkins pipeline needed no change. Failures still fail the build.
- **Coverage is preserved.** The root `jacocoRootReport` globs `build/jacoco/**/*.exec`, which
  picks up the new task's execution data on its own.
- **The declaration lives in the module.** The root `test` task comes from the Etendo Gradle
  plugin and has no editable source in this repository, so both the exclusion and the new task are
  declared from `modules/com.etendoerp.go/build.gradle` against `rootProject`.

## Openbravo.properties preload (the config-file-location variant)

**Symptom.** An `OBBaseTest`/`WeldBaseTest` class fails as `initializationError` with an NPE in
`OBBaseTest.initializeDisabledTestCases` (`Paths.get(null, ...)`), although it does nothing wrong
itself. Example: `StorageDetailCostColumnsIntegrationTest > initializationError` in Jenkins
etendo-go-tests #2487.

**Cause.** `OBPropertiesProvider.getOpenbravoProperties()` loads `Openbravo.properties` only once
per JVM. It stores the properties first and only then calls
`OBConfigFileProvider.getInstance().setFileLocation(...)`. `OBConfigFileProvider` resolves itself
through `OBProvider.getInstance()` and caches the result statically. If the **first** properties
read of a JVM happens under `mockStatic(OBProvider.class)`, one of two things goes wrong:

- the file location is never set, and nothing retries because the properties are already marked
  as loaded;
- a Mockito mock is cached as the `OBConfigFileProvider`.

This usually happens through `ConfigPropertyReader`, `GoRuntimeProperties` or `GoFeatureFlags`,
which swallow the exception, so the mocked test itself still passes. Every DAL test forked later
into that JVM then fails. Which class pays depends only on how Gradle shares classes among forks:
the root `test` task uses `maxParallelForks = 1` and `forkEvery = 100`, and `goIsolatedDalTest`
uses `forkEvery = 1`. So an unrelated PR that adds or removes test classes can move the failure.

**Fix.** `com.etendoerp.go.testsupport.OpenbravoPropertiesPreloadListener`
(`src-test/src/com/etendoerp/go/testsupport/`) is a JUnit Platform `LauncherSessionListener`. It
is registered in
`src-test/resources/META-INF/services/org.junit.platform.launcher.LauncherSessionListener`. Once
per test JVM, before any test class is loaded, it reads the properties with the real providers and
pins the real `OBConfigFileProvider`. It is on the merged root test classpath, so it protects
every module's tests run by the root `test` task and by `goIsolatedDalTest`, not only this
module's tests. It tolerates failures: it catches `Throwable` and logs a warning, so a machine
without `Openbravo.properties` still runs its tests.

- **Do not remove the listener or its `META-INF/services` registration.** Without them the
  failure returns on whichever class Gradle schedules after the poisoning test.
- **New tests need no per-class preload.** Two earlier per-class patches are still in place and
  are harmless: the `@BeforeClass` preload in
  `TenantEnvironmentLifecycleServiceSubscriptionRowTest` and the properties mock in
  `EtendoGoJwtDalHelperTest`. Do not copy them into new classes.

**Verify.** `OpenbravoPropertiesPreloadListenerTest` reproduces the worst case on purpose. Its
first action reads a property while `OBProvider` is statically mocked. It then asserts three
things: the cached `OBConfigFileProvider` is not a mock, its file location is set, and the listener
ran exactly once in the JVM. Run it from the Etendo root:

```bash
./gradlew test --tests com.etendoerp.go.testsupport.OpenbravoPropertiesPreloadListenerTest
```

Pass full class names to `--tests`. A leading wildcard (`--tests '*PreloadListenerTest'`) also
matches core `@Suite` classes, which then fail with `NoTestsDiscoveredException`.

This fixes only the config-file-location variant. The JDBC-pool variant of symptom 1 and the
admin-mode leak of symptom 2 still need `isolatedDalTests` (below).

## Adding a new DAL integration test

If you add a class that extends `OBBaseTest` and it fails with **either** symptom above, add its
class-file pattern to the `isolatedDalTests` list in `build.gradle`:

```groovy
def isolatedDalTests = [
        '**/ReactivatePaymentHandlerRemoveIntegrationTest.class',
        '**/YourNewIntegrationTest.class',
]
```

Add sibling classes that exercise the same flow even if they currently pass. Passing is often just
positional luck: in build #2053 `CreatePurchaseInvoiceHandlerNegativeQuantityIntegrationTest`
survived only because it ran behind the goods-receipt class in the same worker, after that class's
`finished()` had cleared the dirty admin-mode stack on its way out.

### What not to do

- Do **not** wrap the class in `assumeTrue` to skip when the DAL is unavailable. The DAL *is*
  available; skipping would hide the leak and silently drop the coverage.
- Do **not** call `OBContext.clearAdminModeStack()` in `@Before` as a defensive reset. It makes the
  class immune to *its own* leaks too, so a genuine unbalanced `setAdminMode()` in production code
  would never be caught. ETP-4722 tried this and it was removed once the class was isolated.
- Do **not** "pre-warm" DAL singletons (e.g. `addReadWriteAccess(...)` to force
  `EntityAccessChecker` initialization outside the Hibernate flush callback) in the hope of fixing
  an admin-mode imbalance. `EntityAccessChecker.initialize()` already pairs `setAdminMode()` with
  `restorePreviousMode()` in a `finally`, so it cannot be the source of the imbalance. ETP-4722
  tried this too; the failure simply reappeared on a different class.

## The underlying leak

Isolating the class works around the symptom. There are two culprits and neither has been
identified:

1. Whichever mocked test leaves the JDBC pool singleton without a datasource — symptom 1. (The
   `OBConfigFileProvider` file-location half of symptom 1 is identified and fixed by the
   [preload listener](#openbravoproperties-preload-the-config-file-location-variant).)
2. Whichever mocked test calls `OBContext.setAdminMode()` without its matching
   `restorePreviousMode()` — symptom 2.

Finding either means running the handler test classes in a single worker locally until the state is
poisoned, then bisecting. Until then, every new `OBBaseTest` subclass is at risk of the same
failures depending on where Gradle happens to schedule it.
