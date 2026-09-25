/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.payment;

import java.time.Instant;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * ETP-5047 — the single commercial access check every tenant entry point runs: NEO
 * ({@code NeoAuthenticator}), MCP ({@code McpServlet}), the servlets authenticating through
 * {@code JwtAuthUtils.authenticateOrFail} (favorites, report selectors, survey config, fiscal test
 * mode) and the environment login endpoints of {@code EtendoGoJwtServlet}.
 *
 * <p>It decides with {@link TenantEnvironmentLifecycleService#evaluateAccess} and then applies the
 * kill switch ({@link EnvironmentAccessEnforcementFlag}): a denial is returned unless enforcement
 * was explicitly switched off, in which case it is logged at INFO and access is allowed. The flag
 * is consulted only for a denial, so an allowed request — the normal case, on every NEO call —
 * costs no flag evaluation.
 *
 * <p><b>The denial wire format is shared.</b> {@link Denial#errorBody(int)} is the one JSON shape
 * all three entry points answer with, HTTP 402:
 *
 * <pre>
 * { "error": { "message": "Environment access is not available: SUBSCRIPTION_REQUIRED",
 *              "status": 402,
 *              "code": "ENVIRONMENT_ACCESS_DENIED",
 *              "decision": "SUBSCRIPTION_REQUIRED" } }
 * </pre>
 *
 * <p>{@code message} keeps the exact text NEO answered before ETP-5047, so a client that still
 * parses the prefix keeps working; {@code code} and {@code decision} are the machine-readable
 * fields new clients read instead.
 *
 * <p><b>What it does not guard.</b> The platform-account endpoints (billing, the Stripe portal,
 * the plan catalogue) authenticate the account, not a tenant, and must stay reachable so a blocked
 * customer can pay; they never call this class.
 */
public class EnvironmentAccessGuard {

  private static final Logger log = LogManager.getLogger(EnvironmentAccessGuard.class);

  /** {@code error.code} of every environment-access denial. */
  public static final String ERROR_CODE = "ENVIRONMENT_ACCESS_DENIED";

  /**
   * Human-readable prefix of the denial message. Unchanged since ETP-5443: the SPA parsed it
   * before {@code error.decision} existed and still falls back to it.
   */
  public static final String MESSAGE_PREFIX = "Environment access is not available: ";

  private final TenantEnvironmentLifecycleService lifecycleService;
  private final Predicate<String> enforcementSwitchedOff;

  /** Creates a guard backed by the default lifecycle service and the real kill switch. */
  public EnvironmentAccessGuard() {
    this(new TenantEnvironmentLifecycleService());
  }

  /**
   * Creates a guard over a given lifecycle service, with the real kill switch.
   *
   * @param lifecycleService the service that evaluates the access policy
   */
  public EnvironmentAccessGuard(TenantEnvironmentLifecycleService lifecycleService) {
    this(lifecycleService, EnvironmentAccessEnforcementFlag::isEnforcementSwitchedOff);
  }

  EnvironmentAccessGuard(TenantEnvironmentLifecycleService lifecycleService,
      Predicate<String> enforcementSwitchedOff) {
    this.lifecycleService = lifecycleService;
    this.enforcementSwitchedOff = enforcementSwitchedOff;
  }

  /**
   * Decides whether a caller may enter a tenant. The caller must already run with an
   * {@code OBContext}; a context-less caller uses {@link #checkAsSystem}.
   *
   * @param clientId the tenant being entered
   * @param entryPoint a short label for the log line ({@code "neo"}, {@code "mcp"}, ...)
   * @return the denial to answer with, or null when access is allowed — including a tenant that
   *     predates lifecycle metadata ({@code evaluateAccess} answers null) and a denial the kill
   *     switch turned off
   */
  public Denial check(String clientId, String entryPoint) {
    EnvironmentAccessPolicy.Decision decision = lifecycleService.evaluateAccess(clientId, true,
        Instant.now());
    if (decision == null || decision == EnvironmentAccessPolicy.Decision.ALLOWED) {
      return null;
    }
    if (enforcementSwitchedOff.test(clientId)) {
      log.info("Environment access enforcement is switched off: {} would have refused tenant {}"
          + " ({}) and allowed it", entryPoint, clientId, decision.name());
      return null;
    }
    return new Denial(decision);
  }

  /**
   * {@link #check} for a caller that has no {@code OBContext} of its own (MCP resolves its
   * identity before any context exists). Runs as system and hands the thread back the context
   * it arrived with.
   *
   * @param clientId the tenant being entered
   * @param entryPoint a short label for the log line
   * @return the denial to answer with, or null when access is allowed
   */
  public Denial checkAsSystem(String clientId, String entryPoint) {
    return SystemContext.call("the environment access check", () -> check(clientId, entryPoint));
  }

  /** A refused environment entry: the policy decision plus its wire format. */
  public static final class Denial {
    private final EnvironmentAccessPolicy.Decision decision;

    Denial(EnvironmentAccessPolicy.Decision decision) {
      this.decision = decision;
    }

    public EnvironmentAccessPolicy.Decision decision() {
      return decision;
    }

    /**
     * @return the human-readable message: {@link EnvironmentAccessGuard#MESSAGE_PREFIX} followed
     *     by the decision name
     */
    public String message() {
      return MESSAGE_PREFIX + decision.name();
    }

    /**
     * Builds the shared JSON error envelope documented on {@link EnvironmentAccessGuard}.
     *
     * @param status the HTTP status the caller answers with (402)
     * @return {@code {"error": {message, status, code, decision}}}
     */
    public JSONObject errorBody(int status) {
      try {
        JSONObject error = new JSONObject();
        error.put("message", message());
        error.put("status", status);
        error.put("code", ERROR_CODE);
        error.put("decision", decision.name());
        return new JSONObject().put("error", error);
      } catch (JSONException e) {
        // Every value above is a non-null string or an int: jettison cannot reject them.
        throw new IllegalStateException("Could not build the environment access denial", e);
      }
    }
  }
}
