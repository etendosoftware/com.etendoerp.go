/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License").
 * *************************************************************************
 */
package com.etendoerp.go.oauth2;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Pure policy for the user-owned public API credential contract. */
final class PublicApiKeyPolicy {
  static final int MAX_ACTIVE_KEYS_PER_OWNER = 10;
  static final int MAX_NAME_LENGTH = 120;
  static final String CAPABILITY_READ = "public-api:read";
  static final String CAPABILITY_WRITE = "public-api:write";
  static final String CAPABILITY_PROCESS = "public-api:process";
  static final String PUBLIC_KEY_SCOPE_MARKER = "neo:public-api-key";
  private static final String OWNER_ORG_SCOPE_PREFIX = "neo:public-api-owner-org:";

  private static final Map<String, String> CAPABILITY_SCOPES;
  static {
    Map<String, String> capabilities = new LinkedHashMap<>();
    capabilities.put(CAPABILITY_READ, "neo:read");
    capabilities.put(CAPABILITY_WRITE, "neo:write");
    capabilities.put(CAPABILITY_PROCESS, "neo:process");
    CAPABILITY_SCOPES = Collections.unmodifiableMap(capabilities);
  }

  private PublicApiKeyPolicy() {
  }

  static String normalizeName(String name) {
    if (name == null) {
      throw new InvalidRequestException("name is required");
    }
    String normalized = name.trim().replaceAll("\\s+", " ");
    if (normalized.isEmpty()) {
      throw new InvalidRequestException("name must not be blank");
    }
    if (normalized.length() > MAX_NAME_LENGTH) {
      throw new InvalidRequestException("name must be at most " + MAX_NAME_LENGTH + " characters");
    }
    return normalized;
  }

  static Set<String> normalizeCapabilities(String[] requested) {
    if (requested == null || requested.length == 0) {
      throw new InvalidCapabilityException("at least one public capability is required");
    }
    Set<String> capabilities = new LinkedHashSet<>(Arrays.asList(requested));
    if (capabilities.contains(null) || capabilities.contains("")
        || !CAPABILITY_SCOPES.keySet().containsAll(capabilities)) {
      throw new InvalidCapabilityException("unsupported public API capability");
    }
    return capabilities;
  }

  static String toInternalScopes(Set<String> capabilities) {
    StringBuilder scopes = new StringBuilder();
    for (String capability : capabilities) {
      if (scopes.length() > 0) {
        scopes.append(' ');
      }
      scopes.append(CAPABILITY_SCOPES.get(capability));
    }
    return scopes.toString();
  }

  static String toStoredScopes(Set<String> capabilities) {
    return PUBLIC_KEY_SCOPE_MARKER + " " + toInternalScopes(capabilities);
  }

  static String ownerOrganizationMarker(String organizationId) {
    return OWNER_ORG_SCOPE_PREFIX + organizationId;
  }

  static String ownerOrganizationId(String scopes) {
    if (scopes == null) {
      return null;
    }
    for (String scope : scopes.trim().split("\\s+")) {
      if (scope.startsWith(OWNER_ORG_SCOPE_PREFIX)) {
        String organizationId = scope.substring(OWNER_ORG_SCOPE_PREFIX.length());
        return organizationId.isEmpty() ? null : organizationId;
      }
    }
    return null;
  }

  static Set<String> capabilitiesForScopes(String scopes) {
    Set<String> result = new LinkedHashSet<>();
    if (scopes == null) {
      return result;
    }
    for (String scope : scopes.trim().split("\\s+")) {
      for (Map.Entry<String, String> entry : CAPABILITY_SCOPES.entrySet()) {
        if (entry.getValue().equals(scope)) {
          result.add(entry.getKey());
        }
      }
    }
    return result;
  }

  static final class InvalidRequestException extends IllegalArgumentException {
    InvalidRequestException(String message) {
      super(message);
    }
  }

  static final class InvalidCapabilityException extends IllegalArgumentException {
    InvalidCapabilityException(String message) {
      super(message);
    }
  }
}
