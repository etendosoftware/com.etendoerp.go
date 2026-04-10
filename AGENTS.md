# Repository Guidelines

## Project Structure & Module Organization
`src/com/etendoerp/go` contains the Java sources, grouped by feature area such as `schemaforge`, `mcp`, `oauth2`, `onboarding`, and `rest`. Unit tests live under `src-test/src/com/etendoerp/go` and usually mirror the production package they cover. Database metadata and application dictionary changes belong in `src-db/database/model` and `src-db/database/sourcedata`. Sample client data is stored in `referencedata/sampledata/GOClient`. Supporting material lives in `docs`, `compose`, and `etendo-resources`.

## Build, Test, and Development Commands
Use Gradle from this module root when working on Java code:

- `gradle test`: runs the JUnit and Mockito test suite under `src-test/src`.
- `gradle build`: compiles the module and produces the Maven publication declared in `build.gradle`.

When this module is installed inside an Etendo root, use the wrapper there for platform tasks:

- `./gradlew setup`: prepares the Etendo environment.
- `./gradlew update.database`: applies `src-db` and `referencedata` changes to the database.

CI also runs Sonar analysis and XML validation on pull requests.

## Coding Style & Naming Conventions
Follow the existing Java style: 2-space indentation, same-line braces, descriptive `camelCase` methods and fields, and `UpperCamelCase` class names such as `NeoDefaultsService`. Keep packages under `com.etendoerp.go.*`. New Java files should preserve the license header pattern already used in `src` and `src-test`. No formatter config is committed here, so match surrounding code.

For XML sourcedata, keep records sorted by ascending UUID. The workflow in `.github/workflows/xml-order-check.yml` fails PRs when ordering or referential integrity is broken.

## Testing Guidelines
Tests are standard `*Test.java` classes, usually colocated by package path with the code they verify. The current suite uses JUnit 4-style assertions and `@Test`, with JUnit 5 runtime support and Mockito for isolated logic tests. Add or update tests for every behavior change; Sonar runs on PRs, so avoid leaving new branches untested.

## Commit & Pull Request Guidelines
Recent history follows `Feature ETP-1234: Short imperative summary`. Keep that format unless the branch already uses a different convention. Pull requests should link the ticket, summarize functional impact, call out any `src-db` or `referencedata` changes, and include test evidence. If the change affects API behavior or onboarding flows, include example requests or screenshots when they help review.

## Security & Configuration Tips
Do not place credentials in `build.gradle`; the file explicitly warns against it. Keep environment-specific values outside the module and review OAuth2, JWT, and MCP changes carefully because they affect authentication and exposed endpoints.
