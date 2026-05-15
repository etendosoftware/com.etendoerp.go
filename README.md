# com.etendoerp.go

Etendo Go runtime module. Provides the NEO Headless API (`/sws/neo/*`), OAuth2 authentication, MCP server integration, webhook events, and Schema Forge runtime support (ETGO_SF_* tables). Served by Tomcat as part of an Etendo Classic deployment.

## Prerequisites

- **Java 17+** — required by Etendo Classic
- **Gradle** — used by Etendo for module compilation
- **PostgreSQL** — Etendo development database
- **Docker** (optional) — for jsreport report engine via `com.etendoerp:docker`
- An existing Etendo Classic project as the build root

## Installation

This module is installed as an Etendo module under `modules/com.etendoerp.go` inside your Etendo Classic project.

1. Clone into the Etendo `modules/` directory:
   ```bash
   cd /path/to/etendo_core/modules
   git clone git@github.com:etendosoftware/com.etendoerp.go.git
   cd com.etendoerp.go
   git checkout <your-branch>
   ```

2. If the build fails due to a missing OpenAPI dependency, also clone:
   ```bash
   cd /path/to/etendo_core/modules
   git clone git@github.com:etendosoftware/com.etendoerp.openapi.git
   ```

3. From the Etendo Classic root, prepare the environment:
   ```bash
   cd /path/to/etendo_core
   ./gradlew setup
   ```

## Build and Test

Run Gradle commands from the **Etendo Classic root** (not the module directory).

```bash
# Compile all modules including com.etendoerp.go
./gradlew smartbuild

# Run all tests for this module
./gradlew test --tests "com.etendoerp.go.*"

# Run a specific test class
./gradlew test --tests "com.etendoerp.go.schemaforge.NeoCrudHandlerTest"
```

From the module root you can also run:
```bash
gradle test    # runs the JUnit/Mockito test suite under src-test/src
gradle build   # compiles the module and produces the Maven publication
```

If you modify files under `src-db/database/model`, `src-db/database/sourcedata`, or `referencedata/`, apply them to the database:
```bash
./gradlew update.database
```

## Running Locally

This module runs inside the Etendo Classic Tomcat deployment. After a successful `smartbuild`, start Etendo Classic through your usual method (SmartTomcat, Dockerized Tomcat, or `./gradlew tomcatRun`).

The NEO Headless API will be available at:
```
http://localhost:8080/etendo/sws/neo/*
```

### jsreport (optional)

jsreport provides PDF report generation via a Docker container. The `com.etendoerp:docker` dependency is already declared in `build.gradle`.

1. Add to `gradle.properties` in the Etendo root:
   ```properties
   docker_com.etendoerp.go=true
   JSREPORT_PORT=5488
   SCHEMA_FORGE_DIR=/path/to/schema_forge
   ```

2. Build the jsreport image:
   ```bash
   cd modules/com.etendoerp.go/compose
   docker buildx build -f Dockerfile -t etendo-jsreport:latest .
   ```

3. Start from the Etendo root:
   ```bash
   ./gradlew resources.up
   ```

## XML Sourcedata Scripts

Two helper scripts are available in the module root for working with the XML files under `src-db/database/sourcedata/`.

---

### `fix-etgo-xml-order.sh` — Reorder & validate

Sorts all records inside each XML file by UUID (ascending), which is required by the CI order check. After writing, it re-reads the file and verifies that no records were lost.

**Reorder all XML files in `src-db/database/sourcedata/`:**
```bash
./fix-etgo-xml-order.sh
```

**Reorder one or more specific files:**
```bash
./fix-etgo-xml-order.sh src-db/database/sourcedata/ETGO_SF_FIELD.xml
./fix-etgo-xml-order.sh src-db/database/sourcedata/ETGO_SF_FIELD.xml src-db/database/sourcedata/ETGO_SF_SPEC.xml
```

Output legend:
- `already sorted (N records) ✓` — file was already in order, integrity confirmed.
- `sorted (N records) ✓` — file was reordered, integrity confirmed.
- `WARNING ... duplicate UUIDs` — duplicate records detected (file is still written).
- `ERROR ... lost N IDs` — records were lost during processing (script exits with code 1).
- `SKIP ...` — file could not be parsed (malformed XML or no records found).

---

### `check-etgo-xml.sh` — Validate order, uniqueness & referential integrity

Read-only check that validates XML files without modifying them. Run this before committing to catch issues early.

**Check all XML files:**
```bash
./check-etgo-xml.sh
```

**Check a specific file:**
```bash
./check-etgo-xml.sh src-db/database/sourcedata/ETGO_SF_FIELD.xml
```

---

### Typical workflow

```bash
# 1. Fix order and validate integrity
./fix-etgo-xml-order.sh

# 2. Run the CI checks locally
./check-etgo-xml.sh

# 3. Commit
git add src-db/database/sourcedata/
git commit -m "Fix XML sourcedata order"
```

## Project Structure

```
com.etendoerp.go/
├── src/com/etendoerp/go/          # Java sources (schemaforge, mcp, oauth2, onboarding, rest)
├── src-test/src/com/etendoerp/go/ # JUnit/Mockito tests
├── src-db/database/               # Database metadata and sourcedata
│   ├── model/                     # Table/column definitions
│   └── sourcedata/                # Application dictionary records (XML)
├── referencedata/sampledata/      # Sample client data
├── compose/                       # Docker compose for jsreport
├── docs/                          # Module documentation
├── etendo-resources/              # CDI beans.xml
└── build.gradle                   # Module build and dependencies
```

## Documentation

See `docs/INDEX.md` for the full documentation index, including:
- [NEO Headless Overview](docs/neo-headless.md)
- [NEO Headless Development Guide](docs/neo-headless-guide.md)
- [Onboarding Sample Data Packaging](docs/onboarding-sampledata-packaging.md)
- [Package Architecture](docs/package-architecture.md)
