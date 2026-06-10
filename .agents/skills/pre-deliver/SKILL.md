---
name: pre-deliver
description: Use when preparing to deliver, push, commit, or create a pull request for changes in the com.etendoerp.go module. Ensures local checks like unit tests, SonarQube, and other quality gates pass before pushing or creating a PR.
---

# Pre-Deliver Validation for com.etendoerp.go

This skill guides you through the validation steps required before pushing code or creating a Pull Request (PR) in the `com.etendoerp.go` module repository.

## Pre-Delivery Guidelines

### 1. Avoid Early Pull Requests
- **Never create a PR or request reviews until the task/feature is fully completed and verified locally.**
- PRs should represent finished, high-quality work. Creating incomplete/draft PRs causes unnecessary CI runs and review noise. Validate as much as possible locally first.

### 2. Run JUnit / Gradle Tests Locally
Before pushing, run all unit and integration tests for the module.
- From the `etendo_core` root, you can run tests for the `com.etendoerp.go` module:
  ```bash
  ./gradlew test --tests "com.etendoerp.go.*"
  ```
- Make sure all tests complete successfully. If any tests fail, fix them before proceeding.

### 3. Run SonarQube Quality Checks Locally
You must run the local SonarQube script to catch code smells, bugs, and coverage issues:
- Run the `./run-sonar.sh` script located in the module root (`etendo_core/modules/com.etendoerp.go/`):
  ```bash
  ./run-sonar.sh --base-ref <base-branch-or-commit>
  ```
  *(Example: `./run-sonar.sh --base-ref origin/develop` or `./run-sonar.sh --base-ref origin/main` depending on your PR target)*
- Resolve any blocker or critical issues identified by the Sonar analysis. Ensure the quality gate passes.

### 4. Verify Local Git Cleanliness & Branch Context
- Confirm you are on the correct branch (e.g., `feature/ETP-...`) and check that you haven't committed any unwanted files (such as local `.env` files, temporary logs, or large caches).
- Run `git status` to verify there are no stray modifications.
