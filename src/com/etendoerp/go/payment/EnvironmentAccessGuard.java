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

import java.io.IOException;
import java.time.Instant;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * ETP-5047 — the single commercial access check every tenant entry point runs. The complete list
 * of gated surfaces:
 *
 * <ul>
 *   <li><b>NEO</b> — {@code NeoAuthenticator}, {@code SurfacePolicy.NEO_API}, every credential
 *       scheme (Bearer JWT, cookie session, OAuth2 token);</li>
 *   <li><b>{@code SurfacePolicy.NEO_DATA}</b> — the favorites and fiscal test mode servlets
 *       (through {@code JwtAuthUtils.authenticateOrFail}), the report selectors (through
 *       {@code NeoServletSupport.authenticate}) and the OAuth2 API-key management endpoints
 *       (through {@code OAuth2Servlet}, which refuses in its own error envelope);</li>
 *   <li><b>MCP</b> — {@code McpServlet}, through {@link #checkAsSystem};</li>
 *   <li><b>{@code /sws/go} tenant-session endpoints</b> — {@code EtendoGoJwtServlet}'s
 *       {@code resolveTenantSession};</li>
 *   <li><b>the legacy environment login</b> — {@code GET /sws/go/login?userId=}, refused because
 *       it hands out a raw Etendo JWT.</li>
 * </ul>
 *
 * <p>{@code POST /sws/go/session/environment} reads {@link #enforcedDecision} to report the
 * decision but never refuses: the blocked-access screen and the pages that let the customer pay
 * render inside the entered environment. The survey configuration ({@code NEO_AUXILIARY}) is
 * deliberately not gated.
 *
 * <p>It decides with {@link TenantEnvironmentLifecycleService#evaluateAccess} and then applies the
 * kill switch ({@link EnvironmentAccessEnforcementFlag}): a denial is returned unless enforcement
 * was switched off (the flag resolves to {@code true}), in which case it is logged at INFO and
 * access is allowed. The flag is consulted only for a denial, so an allowed request — the normal
 * case, on every NEO call — costs no flag evaluation.
 *
 * <p><b>One log line per refusal.</b> {@link #check} logs every denial it returns at INFO —
 * {@code Commercial access denied at <entryPoint> for tenant <clientId>: <decision>} — so support
 * sees the same line whichever surface refused; the callers do not log the refusal again.
 *
 * <p><b>The denial wire format is shared.</b> {@link Denial#errorBody(int)} is the one JSON shape
 * every refusing entry point except the OAuth2 API-key endpoints answers with, HTTP 402:
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

  private final Supplier<TenantEnvironmentLifecycleService> lifecycleService;
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
    this(() -> lifecycleService);
  }

  /**
   * Creates a guard that looks its lifecycle service up at every check, with the real kill
   * switch. For a holder that keeps one guard for its whole life but whose lifecycle service is
   * a field a test replaces: {@code new EnvironmentAccessGuard(() -> this.lifecycleService)}.
   *
   * @param lifecycleService supplies the service that evaluates the access policy
   */
  public EnvironmentAccessGuard(Supplier<TenantEnvironmentLifecycleService> lifecycleService) {
    this(lifecycleService, EnvironmentAccessEnforcementFlag::isEnforcementSwitchedOff);
  }

  EnvironmentAccessGuard(TenantEnvironmentLifecycleService lifecycleService,
      Predicate<String> enforcementSwitchedOff) {
    this(() -> lifecycleService, enforcementSwitchedOff);
  }

  EnvironmentAccessGuard(Supplier<TenantEnvironmentLifecycleService> lifecycleService,
      Predicate<String> enforcementSwitchedOff) {
    this.lifecycleService = lifecycleService;
    this.enforcementSwitchedOff = enforcementSwitchedOff;
  }

  /**
   * Decides whether a caller may enter a tenant. The caller must already run with an
   * {@code OBContext}; a context-less caller uses {@link #checkAsSystem}.
   *
   * <p>Membership is passed as {@code true}: every caller has already authenticated a user of
   * that very tenant (its session, JWT or OAuth2 token names the tenant's own user), so the
   * policy's membership rule has nothing left to decide here.
   *
   * <p>A denial is logged here, at INFO, naming the entry point, the tenant and the decision:
   * the caller refuses the request and must not log the refusal a second time.
   *
   * @param clientId the tenant being entered
   * @param entryPoint a short label for the log line ({@code "neo"}, {@code "mcp"}, ...)
   * @return the denial to answer with, or null when access is allowed — including a tenant that
   *     predates lifecycle metadata ({@code evaluateAccess} answers null) and a denial the kill
   *     switch turned off
   */
  public Denial check(String clientId, String entryPoint) {
    EnvironmentAccessPolicy.Decision decision = enforce(clientId, entryPoint);
    if (!isRefusal(decision)) {
      return null;
    }
    log.info("Commercial access denied at {} for tenant {}: {}", entryPoint, clientId,
        decision.name());
    return new Denial(decision);
  }

  /**
   * ETP-5047 — the decision this guard actually enforces, for a caller that reports it rather
   * than acting on it (the environment list's {@code accessState}, the environment switch's
   * {@code accessDecision}). The policy decision, except that a refusal the kill switch turned
   * off reads as {@code ALLOWED}: reporting "suspended" for a tenant whose requests all go through
   * would contradict the product. Same cost profile as {@link #check}: the flag is read only for a
   * refusal. Logs nothing — it decides no request.
   *
   * @param clientId the tenant
   * @return the enforced decision, or null for a tenant that predates lifecycle metadata (the
   *     policy's own "no decision", which every entry point allows)
   */
  public EnvironmentAccessPolicy.Decision enforcedDecision(String clientId) {
    return enforce(clientId, null);
  }

  /**
   * The policy decision with the kill switch applied: a refusal the switch turned off reads as
   * {@code ALLOWED}. The kill-switch INFO line is written only for a request that is being decided
   * ({@code entryPoint} not null), never for a caller that merely reports the decision.
   */
  private EnvironmentAccessPolicy.Decision enforce(String clientId, String entryPoint) {
    EnvironmentAccessPolicy.Decision decision = lifecycleService.get().evaluateAccess(clientId,
        true, Instant.now());
    if (!isRefusal(decision) || !enforcementSwitchedOff.test(clientId)) {
      return decision;
    }
    if (entryPoint != null) {
      log.info("Environment access enforcement is switched off: {} would have refused tenant {}"
          + " ({}) and allowed it", entryPoint, clientId, decision.name());
    }
    return EnvironmentAccessPolicy.Decision.ALLOWED;
  }

  private static boolean isRefusal(EnvironmentAccessPolicy.Decision decision) {
    return decision != null && decision != EnvironmentAccessPolicy.Decision.ALLOWED;
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
    /** The HTTP status of every denial. */
    public static final int STATUS = HttpServletResponse.SC_PAYMENT_REQUIRED;

    private final EnvironmentAccessPolicy.Decision decision;

    Denial(EnvironmentAccessPolicy.Decision decision) {
      this.decision = decision;
    }

    /**
     * The policy decision that refused the entry.
     *
     * @return a refusing decision; never {@code ALLOWED} and never null
     */
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

    /**
     * Writes the denial as a complete HTTP answer: status 402, {@code application/json} in UTF-8,
     * and {@link #errorBody}. The one writer for every entry point that owns its raw response
     * (MCP, {@code JwtAuthUtils}, the environment login); NEO sends the same body through its own
     * {@code NeoResponse} writer, which sets the same content type.
     *
     * @param response the response to write to; nothing may have been written to it yet
     * @throws IOException if the body cannot be written
     */
    public void writeTo(HttpServletResponse response) throws IOException {
      response.setStatus(STATUS);
      response.setContentType("application/json");
      response.setCharacterEncoding("UTF-8");
      response.getWriter().write(errorBody(STATUS).toString());
    }
  }
}
