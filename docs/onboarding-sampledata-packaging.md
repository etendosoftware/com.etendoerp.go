# Onboarding Sampledata Packaging

## Context
The onboarding dataset currently originates from `referencedata/sampledata/GOClient`. That source tree exists in development workspaces, but it is not a valid runtime dependency once Etendo packages the application as a WAR.

## Goal
Keep `GOClient` as the single editable source while making the packaged WAR self-contained.

## Decision
The build must stage the onboarding sampledata into `etendo_core/WebContent/WEB-INF/classes/com/etendoerp/go/onboarding/sampledata/GOClient` before WAR assembly.

The staged classpath payload must also include `etendo_core/WebContent/WEB-INF/classes/com/etendoerp/go/onboarding/sampledata/index.txt`, listing the XML filenames in deterministic order.

## Runtime Contract
`OnboardingDatasetNormalizer` must load bundled sampledata from the classpath, using `index.txt` to discover the XML files. Runtime code must not depend on repository-relative filesystem paths such as `referencedata/sampledata/GOClient` or `etendo_core/modules/com.etendoerp.go/...`.

> The curated onboarding dataset keeps `C_DOCTYPE` together with its cascading dependencies `AD_SEQUENCE` and `GL_CATEGORY`, so `DataImportService` can resolve document-number sequences and GL categories during import.

- `C_PAYMENTTERM` is also curated directly from GOClient. It does not introduce extra foreign-key tables beyond the normal client/organization ownership that the normalizer already remaps.

## Build Contract
A module Gradle task prepares the classpath payload from `referencedata/sampledata/GOClient` and hooks the root Etendo packaging tasks so `smartbuild`, `war`, and `antWar` always package the staged files into the final WAR by writing them into the root `WebContent/WEB-INF/classes` tree consumed by `antWar`.
