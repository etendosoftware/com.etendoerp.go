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

# --- @NeoExtension parity (ETP-5415 A13, ratchet since ETP-5588) ----------
# Offline, no DB and no build: reads the committed ETGO_SF_*.xml sourcedata and the
# @NeoExtension-annotated Java sources. EXTENSION_CEILING is the EXACT distinct Java_Qualifier
# count: above it fails (the old binding mechanism was chosen with the new one available), below
# it fails too (the PR that migrates a handler lowers this value in the same change, so the freed
# slack can never be spent on a new qualifier). CI rejects any PR that raises it.
EXTENSION_CEILING ?= 89

extension-parity: ## Report which mechanism binds each (spec, entity); fails unless distinct Java_Qualifier == EXTENSION_CEILING (vars: ALL=1 HOOKS=1)
	@python3 ./extension-parity.py --exact --ceiling $(EXTENSION_CEILING) $(if $(ALL),--all,) $(if $(HOOKS),--hooks,)

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
	  awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'
