package com.etendoerp.go.schemaforge;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.dal.core.OBContext;

/**
 * The tenant-owning fields a caller may read but never write.
 *
 * <p>{@code client} and {@code organization} are resolved from the session, always, on every
 * write path. A value supplied by the caller is discarded — never compared, never honoured.
 * Etendo GO positions an account in one specific organization of one client, so choosing a
 * different one is not a business act a caller can perform; it is either a client bug or an
 * attempt to write into another tenant.</p>
 *
 * <p><b>Why this is not left to the curated configuration.</b> Neither column has an
 * {@code ETGO_SF_FIELD} row, and the two write paths answer that absence in opposite ways. The
 * REST filter is a whitelist, so an uncurated key is stripped and REST is safe — by accident,
 * not by design: curating {@code AD_Org_ID} as an included field would expose it tomorrow. The
 * MCP write gate is two deny-sets built from those same rows, so an uncurated key matches
 * neither set, passes both gates and reaches {@code jsonService.add} with the caller's value
 * intact. That is a cross-tenant write returning {@code 200 OK}, with the record invisible to
 * the session that created it.</p>
 *
 * <p>The policy therefore lives here, stated once, and both write paths call it. Implementing it
 * separately on each side is how the same defect survived in two files after being closed
 * (IMP-39).</p>
 *
 * <p>Read access is unchanged. Both fields stay in {@code neo_get}, {@code neo_list} and
 * {@code neo_schema} responses: they are information the caller legitimately needs.</p>
 */
public final class NeoServerOwnedFields {

  private static final Logger log = LogManager.getLogger(NeoServerOwnedFields.class);

  /** DAL property name of the owning client. */
  public static final String PROP_CLIENT = "client";

  /** DAL property name of the owning organization. */
  public static final String PROP_ORGANIZATION = "organization";

  /** The DAL property names this policy owns. */
  public static final Set<String> SERVER_OWNED_PROPERTIES = Set.of(PROP_CLIENT, PROP_ORGANIZATION);

  /**
   * Every spelling a caller can reach these fields by: the DAL property name, the DB column
   * name, and the {@code _identifier} variant a client echoes back from a read response.
   */
  private static final Map<String, String> SERVER_OWNED_KEYS = Map.of(
      PROP_CLIENT, PROP_CLIENT,
      "ad_client_id", PROP_CLIENT,
      "client_identifier", PROP_CLIENT,
      PROP_ORGANIZATION, PROP_ORGANIZATION,
      "ad_org_id", PROP_ORGANIZATION,
      "organization_identifier", PROP_ORGANIZATION);

  private NeoServerOwnedFields() {
  }

  /**
   * Whether the server, not the caller, decides this property's value on every write.
   *
   * @param propertyName a DAL property name
   * @return whether the server owns this property on write
   */
  public static boolean isServerOwned(String propertyName) {
    return propertyName != null && SERVER_OWNED_PROPERTIES.contains(propertyName);
  }

  /**
   * Whether the server owns this DAL property, for callers that already resolved one.
   *
   * @param prop a DAL property, may be {@code null}
   * @return whether the server owns this property on write
   */
  public static boolean isServerOwned(Property prop) {
    return prop != null && isServerOwned(prop.getName());
  }

  /**
   * Whether a raw request key names a server-owned field, in any spelling a caller can reach it
   * by. Used on the REST path, where keys are matched before any DAL resolution.
   *
   * @param key a raw request-body key
   * @return whether the key names a server-owned field
   */
  public static boolean isServerOwnedKey(String key) {
    return key != null && SERVER_OWNED_KEYS.containsKey(key.toLowerCase(Locale.ROOT));
  }

  /**
   * The session's own value for a server-owned property.
   *
   * @param propertyName {@link #PROP_CLIENT} or {@link #PROP_ORGANIZATION}
   * @return the id held by the current {@link OBContext}, or {@code null} when there is no
   *     context to read (a background thread, or a property this policy does not own)
   */
  public static String sessionValue(String propertyName) {
    OBContext context = OBContext.getOBContext();
    if (context == null) {
      return null;
    }
    try {
      if (PROP_CLIENT.equals(propertyName) && context.getCurrentClient() != null) {
        return context.getCurrentClient().getId();
      }
      if (PROP_ORGANIZATION.equals(propertyName) && context.getCurrentOrganization() != null) {
        return context.getCurrentOrganization().getId();
      }
    } catch (Exception e) {
      log.debug("Could not read session value for {}: {}", propertyName, e.getMessage());
    }
    return null;
  }

  /**
   * Removes every server-owned key from the body, and reports the ones whose value differed
   * from the session's.
   *
   * <p>Stripping is unconditional; the comparison decides only whether the caller is told. A
   * caller that echoed back the value a read response handed it gets silence, because nothing
   * was taken from it. A caller that sent a different tenant is told what happened, rather than
   * discovering it from a {@code 404} on the record it just created — which is the failure shape
   * IMP-45 exists to avoid.</p>
   *
   * <p>Nothing is injected here. On create, {@code NeoMandatoryDefaultsService} fills both
   * columns from the session context, and the DAL fills them from {@link OBContext} if it does
   * not. On update they must simply not change.</p>
   *
   * @param body the request body, modified in place; {@code null} is tolerated
   * @param dalEntity the DAL entity the body is being written to, used to resolve a raw key to
   *     the property it names
   * @return a report keyed by property name, each entry holding the {@code sent} and
   *     {@code session} values, or an empty object when nothing differed
   */
  public static JSONObject stripServerOwned(JSONObject body, Entity dalEntity) {
    JSONObject report = new JSONObject();
    if (body == null) {
      return report;
    }
    for (String key : List.copyOf(keysOf(body))) {
      String propertyName = resolvePropertyName(key, dalEntity);
      if (propertyName == null) {
        continue;
      }
      Object sent = body.opt(key);
      body.remove(key);
      recordIfDifferent(report, propertyName, sent);
    }
    return report;
  }

  /**
   * Adds an entry to the report when the caller's value is not the session's own.
   *
   * @param report the report being built
   * @param propertyName the server-owned property
   * @param sent the value the caller supplied
   */
  public static void recordIfDifferent(JSONObject report, String propertyName, Object sent) {
    String session = sessionValue(propertyName);
    if (sent == null || session == null || session.equals(String.valueOf(sent))) {
      return;
    }
    try {
      JSONObject entry = new JSONObject();
      entry.put("sent", sent);
      entry.put("session", session);
      report.put(propertyName, entry);
    } catch (JSONException e) {
      log.debug("Could not report server-owned field {}: {}", propertyName, e.getMessage());
    }
  }

  /**
   * @param key a raw body key
   * @param dalEntity the entity being written, may be {@code null}
   * @return the server-owned DAL property this key names, or {@code null} when it names none
   */
  private static String resolvePropertyName(String key, Entity dalEntity) {
    if (isServerOwned(key)) {
      return key;
    }
    if (dalEntity != null) {
      Property byColumn = dalEntity.getPropertyByColumnName(key, false);
      if (isServerOwned(byColumn)) {
        return byColumn.getName();
      }
    }
    return key == null ? null : SERVER_OWNED_KEYS.get(key.toLowerCase(Locale.ROOT));
  }

  /**
   * @param body a JSON object
   * @return its keys, materialised so the caller can remove entries while iterating
   */
  private static Set<String> keysOf(JSONObject body) {
    Set<String> keys = new java.util.LinkedHashSet<>();
    Iterator<String> it = body.keys();
    while (it.hasNext()) {
      keys.add(it.next());
    }
    return keys;
  }
}
