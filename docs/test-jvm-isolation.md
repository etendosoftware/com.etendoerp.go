# DAL Integration Tests — JVM Isolation

## Why this exists

Most tests in `src-test/` are mocked unit tests: they stub `OBDal`, `OBContext` and friends with
`mockStatic` and never touch a database. A handful extend `OBBaseTest`, whose `@BeforeClass`
initializes the Openbravo DAL layer against the real database.

Those two kinds of test do not coexist well inside a single JVM. When an `OBBaseTest` subclass
lands in a Gradle test worker that has already run mocked handler tests, the Apache JDBC pool
singleton can be left without a datasource, and DAL initialization dies before any test method
runs:

```
ReactivatePaymentHandlerRemoveIntegrationTest > initializationError FAILED
  OBException: Failed to load reference classes
   └─ NPE: Cannot invoke "DataSource.getConnection()" because "datasource" is null
      at OBBaseTest.staticInitializeDalLayer(OBBaseTest.java:447)
```

This is leaked JVM state, **not** a missing or misconfigured database — the other `OBBaseTest`
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

## Adding a new DAL integration test

If you add a class that extends `OBBaseTest` and it fails with the `initializationError` above,
add its class-file pattern to the `isolatedDalTests` list in `build.gradle`:

```groovy
def isolatedDalTests = [
        '**/ReactivatePaymentHandlerRemoveIntegrationTest.class',
        '**/YourNewIntegrationTest.class',
]
```

Do not "fix" it by wrapping the class in `assumeTrue` to skip when the DAL is unavailable. The DAL
*is* available; skipping would hide the leak and silently drop the coverage.

## The underlying leak

Isolating the class works around the symptom. The actual culprit — whichever mocked test leaves the
JDBC pool singleton without a datasource — has not been identified. Finding it means running the
handler test classes in a single worker locally until the pool is poisoned, then bisecting. Until
then, every new `OBBaseTest` subclass is at risk of the same failure depending on where Gradle
happens to schedule it.
