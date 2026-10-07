/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License").
 * *************************************************************************
 */
package com.etendoerp.go.oauth2;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The OAuth2 scope vocabulary of the Etendo API, and the single place that decides whether a
 * granted scope set satisfies a required scope.
 * <p>
 * The scopes were renamed from {@code neo:*} to {@code etendo:*} (ETP-5602). Both prefixes are
 * accepted everywhere: a legacy scope is equivalent to its {@code etendo:} counterpart and to
 * nothing else, so {@code neo:read} grants exactly what {@code etendo:read} grants. Only the
 * {@code etendo:} names are advertised. Tokens and clients keep the scope strings they were issued
 * with; equivalence is applied when a scope is checked, never by rewriting stored data.
 * <p>
 * Every scope check must go through {@link #grants}; comparing scope strings directly would
 * silently reject one of the two prefixes.
 */
public final class ApiScopes {

  public static final String READ = "etendo:read";
  public static final String WRITE = "etendo:write";
  public static final String PROCESS = "etendo:process";
  public static final String REPORT = "etendo:report";
  /** Grants every scope. */
  public static final String ALL = "etendo:*";

  private static final String PREFIX = "etendo:";
  /** Deprecated prefix, still accepted. */
  private static final String LEGACY_PREFIX = "neo:";

  /** The scopes this server advertises, in metadata and defaults. Only the current names. */
  public static final List<String> ADVERTISED =
      Collections.unmodifiableList(Arrays.asList(READ, WRITE, PROCESS, REPORT, ALL));

  /** The scopes a client may request or be configured with: the advertised ones and their legacy aliases. */
  public static final Set<String> ACCEPTED;
  static {
    Set<String> accepted = new LinkedHashSet<>(ADVERTISED);
    for (String scope : ADVERTISED) {
      accepted.add(LEGACY_PREFIX + scope.substring(PREFIX.length()));
    }
    ACCEPTED = Collections.unmodifiableSet(accepted);
  }

  private ApiScopes() {
  }

  /**
   * Returns the current name of a scope: a legacy {@code neo:} scope accepted by this server maps
   * to its {@code etendo:} counterpart; anything else is returned unchanged.
   *
   * @param scope a scope name, possibly {@code null}
   * @return the canonical scope name
   */
  public static String canonical(String scope) {
    if (scope != null && scope.startsWith(LEGACY_PREFIX) && ACCEPTED.contains(scope)) {
      return PREFIX + scope.substring(LEGACY_PREFIX.length());
    }
    return scope;
  }

  /**
   * Parses a space-separated scope string as issued in a token or stored on a client.
   *
   * @param scopes the scope string, possibly {@code null} or blank
   * @return the scopes, in order, without empty entries
   */
  public static Set<String> parse(String scopes) {
    if (scopes == null || scopes.trim().isEmpty()) {
      return Collections.emptySet();
    }
    return new LinkedHashSet<>(Arrays.asList(scopes.trim().split("\\s+")));
  }

  /**
   * Whether a granted scope set satisfies a required scope. The wildcard of either prefix grants
   * everything; otherwise the required scope, or its legacy/current alias, must be granted.
   *
   * @param granted the scopes held by the caller, possibly {@code null}
   * @param required the scope to check, of either prefix
   * @return {@code true} when the required scope is granted
   */
  public static boolean grants(Collection<String> granted, String required) {
    if (granted == null || required == null) {
      return false;
    }
    Set<String> canonical = new HashSet<>();
    for (String scope : granted) {
      canonical.add(canonical(scope));
    }
    return canonical.contains(ALL) || canonical.contains(canonical(required));
  }

  /**
   * {@link #grants(Collection, String)} for a space-separated scope string.
   *
   * @param granted the scope string held by the caller, possibly {@code null}
   * @param required the scope to check, of either prefix
   * @return {@code true} when the required scope is granted
   */
  public static boolean grants(String granted, String required) {
    return grants(parse(granted), required);
  }
}
