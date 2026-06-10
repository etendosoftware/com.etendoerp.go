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

.PHONY: coverage-check help

coverage-check: ## Compare local coverage vs epic, mirrors Jenkins (vars: REPORT_ONLY=1 NO_FAIL=1 BRANCH=<ref>)
	./compare-coverage-local.sh $(COVERAGE_FLAGS)

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
	  awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'
