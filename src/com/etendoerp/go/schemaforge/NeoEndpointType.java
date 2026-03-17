package com.etendoerp.go.schemaforge;

/**
 * Identifies the type of sub-endpoint being invoked.
 * Used by NeoHandler hooks to determine which endpoint triggered
 * the hook and apply field-level granularity.
 */
public enum NeoEndpointType {
  CRUD,
  SELECTOR,
  ACTION,
  EVALUATE_DISPLAY,
  CALLOUT,
  DEFAULTS
}
