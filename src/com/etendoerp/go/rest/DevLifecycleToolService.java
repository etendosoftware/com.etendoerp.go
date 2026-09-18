/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * *************************************************************************
 */
package com.etendoerp.go.rest;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.common.GoRuntimeProperties;
import com.etendoerp.go.payment.EnvironmentAccessPolicy;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.schemaforge.util.OwnerSupport;

/** Local-only lifecycle clock and state controls used to exercise ETP-5396 boundaries. */
public final class DevLifecycleToolService {
  public static final String ENABLED_PROPERTY = "etendo.go.dev.lifecycle.tool.enabled";
  public static final String ENABLED_ENV = "ETGO_DEV_LIFECYCLE_TOOL_ENABLED";
  public static final String ENVIRONMENT_PROPERTY = "etendo.go.runtime.environment";
  public static final String ENVIRONMENT_ENV = "ETGO_RUNTIME_ENVIRONMENT";

  private final TenantEnvironmentLifecycleService lifecycle;

  public DevLifecycleToolService() {
    this(new TenantEnvironmentLifecycleService());
  }

  DevLifecycleToolService(TenantEnvironmentLifecycleService lifecycle) {
    this.lifecycle = lifecycle;
  }

  public static boolean isEnabled() {
    return GoRuntimeProperties.readBoolean(ENABLED_PROPERTY, ENABLED_ENV, false)
        && "local".equalsIgnoreCase(GoRuntimeProperties.readValue(
            ENVIRONMENT_PROPERTY, ENVIRONMENT_ENV, ""));
  }

  public JSONObject read(String accountEmail) throws JSONException {
    JSONObject result = new JSONObject();
    result.put("enabled", isEnabled());
    result.put("trialDays", lifecycle.configuration().getTrialDays());
    result.put("renewalGraceDays", lifecycle.configuration().getRenewalGraceDays());
    JSONArray environments = new JSONArray();
    Set<String> seen = new HashSet<>();
    for (User user : ownedUsers(accountEmail)) {
      if (user.getClient() == null || !seen.add(user.getClient().getId())) continue;
      environments.put(EtendoGoJwtDalHelper.buildEnvironmentJson(
          user.getClient(), user.getOrganization(), user));
    }
    result.put("environments", environments);
    return result;
  }

  public JSONObject update(String accountEmail, JSONObject body) throws JSONException {
    int trialDays = optionalInt(body, "trialDays");
    int graceDays = optionalInt(body, "renewalGraceDays");
    if (trialDays != Integer.MIN_VALUE) {
      if (trialDays <= 0) throw new IllegalArgumentException("trialDays must be positive");
      System.setProperty(TenantEnvironmentLifecycleService.TRIAL_DAYS_PROPERTY,
          String.valueOf(trialDays));
    }
    if (graceDays != Integer.MIN_VALUE) {
      if (graceDays < 0) throw new IllegalArgumentException("renewalGraceDays cannot be negative");
      System.setProperty(TenantEnvironmentLifecycleService.GRACE_DAYS_PROPERTY,
          String.valueOf(graceDays));
    }
    String clientId = body.optString("clientId", "").trim();
    if (!clientId.isEmpty()) {
      boolean owned = ownedUsers(accountEmail).stream().anyMatch(user ->
          user.getClient() != null && clientId.equals(user.getClient().getId()));
      if (!owned) throw new SecurityException("Only an owned environment can be changed");
      EnvironmentAccessPolicy.EnvironmentType type = EnvironmentAccessPolicy.EnvironmentType
          .valueOf(body.optString("type", "DEMO").toUpperCase(Locale.ROOT));
      Instant started = optionalInstant(body, "trialStartedAt");
      String statusValue = body.optString("subscriptionStatus", "").trim();
      EnvironmentAccessPolicy.SubscriptionStatus status = statusValue.isEmpty() ? null
          : EnvironmentAccessPolicy.SubscriptionStatus.valueOf(statusValue.toUpperCase(Locale.ROOT));
      Instant due = optionalInstant(body, "renewalDueAt");
      if (!lifecycle.updateDevelopmentState(clientId, type, started, status, due)) {
        throw new IllegalStateException("Could not update lifecycle state");
      }
    }
    return read(accountEmail);
  }

  private List<User> ownedUsers(String accountEmail) {
    return EtendoGoJwtDalHelper.findEnvironmentUsersByAccountEmail(accountEmail).stream()
        .filter(user -> OwnerSupport.isOwner(user.getId())).toList();
  }

  private int optionalInt(JSONObject body, String field) {
    return body.has(field) && !body.isNull(field) ? body.optInt(field, Integer.MIN_VALUE)
        : Integer.MIN_VALUE;
  }

  private Instant optionalInstant(JSONObject body, String field) {
    String value = body.optString(field, "").trim();
    return value.isEmpty() ? null : Instant.parse(value);
  }
}
