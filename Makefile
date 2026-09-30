SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

# Flags forwarded to compare-coverage-local.sh based on make variables.
COVERAGE_FLAGS :=
ifeq ($(REPORT_ONLY),1)
COVERAGE_FLAGS += --report-only
endif
ifeq ($(NO_FAIL),1)
COVERAGE_FLAGS += --no-fail
endif
ifneq ($(BRANCH),)
COVERAGE_FLAGS += --branch $(BRANCH)
endif

.PHONY: coverage-check extension-parity help

coverage-check: ## Compare local coverage vs epic, mirrors Jenkins (vars: REPORT_ONLY=1 NO_FAIL=1 BRANCH=<ref>)
	./compare-coverage-local.sh $(COVERAGE_FLAGS)

# --- @NeoExtension parity (ETP-5415 A13) -----------------------------------
# Offline, no DB and no build: reads the committed ETGO_SF_*.xml sourcedata and the
# @NeoExtension-annotated Java sources. EXTENSION_CEILING gates the DISTINCT Java_Qualifier
# count upwards only -- a new qualifier means the old binding mechanism was chosen with the
# new one available. A decrease is reported, never gated.
EXTENSION_CEILING ?= 90

extension-parity: ## Report which mechanism binds each (spec, entity); fails if distinct Java_Qualifier > EXTENSION_CEILING (vars: ALL=1)
	@python3 ./extension-parity.py --ceiling $(EXTENSION_CEILING) $(if $(ALL),--all,)

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
	  awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'
