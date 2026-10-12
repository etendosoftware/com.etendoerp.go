/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License").
 * *************************************************************************
 */
package com.etendoerp.go.oauth2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ApiScopes}: the {@code neo:} to {@code etendo:} scope equivalence that
 * every scope check relies on.
 *
 * @covers com.etendoerp.go.oauth2.ApiScopes
 */
class ApiScopesTest {

  private static final List<String> SCOPES = Arrays.asList("read", "write", "process", "report");

  @Test
  @DisplayName("a legacy-scope token still grants its scope")
  void legacyScopeGrantsItsScope() {
    assertTrue(ApiScopes.grants("neo:read", ApiScopes.READ));
    assertTrue(ApiScopes.grants("neo:write", "neo:write"));
  }

  @Test
  @DisplayName("a new-scope token grants its scope, under either name")
  void newScopeGrantsItsScope() {
    assertTrue(ApiScopes.grants("etendo:read", ApiScopes.READ));
    assertTrue(ApiScopes.grants("etendo:read", "neo:read"));
  }

  @Test
  @DisplayName("each scope of either prefix is equivalent to its counterpart and to nothing else")
  void aliasesNeverWidenAccess() {
    for (String granted : SCOPES) {
      for (String required : SCOPES) {
        boolean same = granted.equals(required);
        for (String grantedPrefix : Arrays.asList("neo:", "etendo:")) {
          for (String requiredPrefix : Arrays.asList("neo:", "etendo:")) {
            assertEquals(same,
                ApiScopes.grants(grantedPrefix + granted, requiredPrefix + required),
                grantedPrefix + granted + " -> " + requiredPrefix + required);
          }
        }
      }
    }
  }

  @Test
  @DisplayName("a token mixing both prefixes grants the union")
  void mixedScopesGrantTheUnion() {
    String mixed = "neo:read etendo:process";
    assertTrue(ApiScopes.grants(mixed, ApiScopes.READ));
    assertTrue(ApiScopes.grants(mixed, "neo:process"));
    assertFalse(ApiScopes.grants(mixed, ApiScopes.WRITE));
    assertFalse(ApiScopes.grants(mixed, ApiScopes.REPORT));
  }

  @Test
  @DisplayName("the wildcard of either prefix grants every scope")
  void wildcardOfEitherPrefixGrantsEverything() {
    for (String wildcard : Arrays.asList("neo:*", "etendo:*")) {
      for (String scope : SCOPES) {
        assertTrue(ApiScopes.grants(wildcard, "etendo:" + scope), wildcard + " -> " + scope);
        assertTrue(ApiScopes.grants(wildcard, "neo:" + scope), wildcard + " -> neo:" + scope);
      }
      assertTrue(ApiScopes.grants(wildcard, "neo:*"));
      assertTrue(ApiScopes.grants(wildcard, ApiScopes.ALL));
    }
  }

  @Test
  @DisplayName("a concrete scope never grants a wildcard")
  void concreteScopeDoesNotGrantWildcard() {
    assertFalse(ApiScopes.grants("neo:read neo:write neo:process neo:report", ApiScopes.ALL));
  }

  @Test
  @DisplayName("only the etendo: scopes are advertised; both prefixes are accepted")
  void advertisedListHasOnlyEtendoScopes() {
    assertEquals(Arrays.asList("etendo:read", "etendo:write", "etendo:process", "etendo:report",
        "etendo:*"), ApiScopes.ADVERTISED);
    for (String scope : ApiScopes.ADVERTISED) {
      assertFalse(scope.startsWith("neo:"), scope);
    }
    assertTrue(ApiScopes.ACCEPTED.containsAll(ApiScopes.ADVERTISED));
    assertTrue(ApiScopes.ACCEPTED.containsAll(
        Arrays.asList("neo:read", "neo:write", "neo:process", "neo:report", "neo:*")));
    assertEquals(10, ApiScopes.ACCEPTED.size());
  }

  @Test
  @DisplayName("unknown scopes stay unknown and grant nothing")
  void unknownScopesAreNotAccepted() {
    assertFalse(ApiScopes.ACCEPTED.contains("etendo:admin"));
    assertFalse(ApiScopes.ACCEPTED.contains("neo:admin"));
    assertEquals("neo:admin", ApiScopes.canonical("neo:admin"));
    assertEquals("neo:public-api-key", ApiScopes.canonical("neo:public-api-key"));
    assertFalse(ApiScopes.grants("neo:admin", "etendo:admin"));
    assertFalse(ApiScopes.grants("unknown:scope", ApiScopes.READ));
  }

  @Test
  @DisplayName("null or blank inputs grant nothing")
  void degenerateInputsGrantNothing() {
    assertFalse(ApiScopes.grants((String) null, ApiScopes.READ));
    assertFalse(ApiScopes.grants("  ", ApiScopes.READ));
    assertFalse(ApiScopes.grants("etendo:*", null));
    assertTrue(ApiScopes.parse(null).isEmpty());
  }
}
