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

package com.etendoerp.go.schemaforge.fiscal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.common.FiscalTestModeResolver;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoMessageText;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * The write rules of the three fiscal-configuration entities (SII, TicketBAI, VERI*FACTU), on the
 * server (ETP-6053). Called
 * from each entity's own customization ({@code SiiConfigDeactivateHandler},
 * {@code TbaiConfigSequenceHandler}, {@code VerifactuConfigReadyHandler}) and from the
 * {@code configureFiscalSystem} action of {@code sii-config/siiConfiguration}.
 *
 * <h2>Every channel (REST single, REST batch, MCP)</h2>
 * <ul>
 *   <li>A config is never created inactive — an inactive create would slip past every "one
 *       active" rule.</li>
 *   <li>No config is created while the client is in forced fiscal test mode
 *       ({@code ETSG_ForceTestMode}, {@link FiscalTestModeResolver}) — the same sentence as the SPA's
 *       {@code fiscal.testModeLock.warning}.</li>
 *   <li>One active config per system and organization. TicketBAI has no database trigger for it;
 *       SII and VERI*FACTU do, but their trigger messages are not translated, so the refusal is
 *       answered here first, in the request language.</li>
 * </ul>
 * These hold on every channel deliberately: {@code etendo_batch} builds its hook context without
 * {@code mcpOrigin}, so an MCP-only create rule would be bypassed by a batched create.
 *
 * <h2>MCP only</h2>
 * <ul>
 *   <li>{@code active} cannot be changed by an agent: deactivating is "Cambiar SIF" in the SPA,
 *       which this rule must keep working (REST {@code PUT {active:false}}), and reactivating has
 *       no UI path at all. An update echoing the current value is not a change and passes.</li>
 * </ul>
 */
public final class FiscalSifGuard {

  static final String MSG_TEST_MODE = "ETGO_FiscalSifTestMode";
  static final String MSG_ONE_ACTIVE = "ETGO_FiscalSifOneActive";
  static final String MSG_INACTIVE_CREATE = "ETGO_FiscalSifInactiveCreate";
  static final String MSG_ACTIVE_BY_AGENT = "ETGO_FiscalSifActiveByAgent";

  static final String FIELD_ACTIVE = "active";

  /**
   * Prefix of the catalog keys that name a territory as the SPA does ({@code fiscal.territory.*}
   * of its locales): {@code ETGO_FiscalSifTerritory_<territory id>}.
   */
  static final String TERRITORY_NAME_PREFIX = "ETGO_FiscalSifTerritory_";

  /**
   * The value of a config's {@code active} flag — requested by a body or stored in a record — or
   * {@link #UNKNOWN} when the body does not carry a readable one or the record does not exist.
   */
  public enum ActiveFlag {
    /** {@code active} is true / {@code 'Y'}. */
    ACTIVE,
    /** {@code active} is false / {@code 'N'}. */
    INACTIVE,
    /** Absent, unreadable or not found. */
    UNKNOWN
  }

  /**
   * Set while {@code configureFiscalSystem} creates its records, on every channel: the SII created
   * then is "acogido al SII" ({@code INSIISYSTEM = 'Y'}) from the start. A thread flag because the
   * records go through {@code BatchService}, whose hook context carries nothing from the caller.
   */
  private static final ThreadLocal<Boolean> FISCAL_SYSTEM_ACTIVATION = new ThreadLocal<>();

  private static final String METHOD_POST = "POST";
  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_PATCH = "PATCH";

  /** {table, primary key column} of each record system. Native SQL: no compile-time dependency. */
  private static final Map<String, String[]> TABLES = Map.of(
      FiscalSifRules.SII, new String[] { "aeatsii_config", "aeatsii_config_id" },
      FiscalSifRules.TBAI, new String[] { "tbai_config", "tbai_config_id" },
      FiscalSifRules.VERIFACTU, new String[] { "etvfac_verifactu_config",
          "etvfac_verifactu_config_id" });

  /** The name a person reads, as the SPA writes it. */
  private static final Map<String, String> DISPLAY_NAMES = Map.of(
      FiscalSifRules.SII, "SII",
      FiscalSifRules.TBAI, "TicketBAI",
      FiscalSifRules.SII_TBAI, "SII + TicketBAI",
      FiscalSifRules.VERIFACTU, "VERI*FACTU");

  private FiscalSifGuard() {
  }

  /**
   * The pre-hook of a fiscal-configuration entity: the refusal for this write, or {@code null} to
   * let it proceed.
   *
   * @param context      the CRUD context
   * @param recordSystem {@link FiscalSifRules#SII}, {@link FiscalSifRules#TBAI} or
   *                     {@link FiscalSifRules#VERIFACTU}
   * @return the refusal, or {@code null}
   */
  public static NeoResponse preCheck(NeoContext context, String recordSystem) {
    if (context == null || context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    String method = StringUtils.upperCase(context.getHttpMethod(), Locale.ROOT);
    JSONObject body = context.getRequestBody();
    if (METHOD_POST.equals(method)) {
      return createRefusal(context, recordSystem, body);
    }
    if ((METHOD_PUT.equals(method) || METHOD_PATCH.equals(method)) && body != null
        && body.has(FIELD_ACTIVE)) {
      return activeChangeRefusal(context, recordSystem, body);
    }
    return null;
  }

  private static NeoResponse createRefusal(NeoContext context, String recordSystem,
      JSONObject body) {
    if (requestedActive(body) == ActiveFlag.INACTIVE) {
      return refusal(HttpServletResponse.SC_BAD_REQUEST, MSG_INACTIVE_CREATE);
    }
    if (isForceTestMode(context.getObContext())) {
      return refusal(HttpServletResponse.SC_CONFLICT, MSG_TEST_MODE);
    }
    String orgId = currentOrganizationId(context.getObContext());
    if (orgId != null && activeRecordId(recordSystem, orgId, null) != null) {
      return oneActiveRefusal(recordSystem);
    }
    return null;
  }

  private static NeoResponse activeChangeRefusal(NeoContext context, String recordSystem,
      JSONObject body) {
    ActiveFlag requested = requestedActive(body);
    String recordId = context.getRecordId();
    ActiveFlag current = recordId == null ? ActiveFlag.UNKNOWN : isActive(recordSystem, recordId);
    if (requested == ActiveFlag.UNKNOWN || current == ActiveFlag.UNKNOWN || requested == current) {
      return null;
    }
    if (context.isMcpOrigin()) {
      return refusal(HttpServletResponse.SC_BAD_REQUEST, MSG_ACTIVE_BY_AGENT);
    }
    if (requested == ActiveFlag.ACTIVE) {
      String orgId = organizationOf(recordSystem, recordId);
      if (orgId != null && activeRecordId(recordSystem, orgId, recordId) != null) {
        return oneActiveRefusal(recordSystem);
      }
    }
    return null;
  }

  private static NeoResponse oneActiveRefusal(String recordSystem) {
    return refusal(HttpServletResponse.SC_CONFLICT, MSG_ONE_ACTIVE, displayName(recordSystem));
  }

  /**
   * The value of {@code active} in a request body; {@link ActiveFlag#UNKNOWN} when absent or
   * unreadable. Accepts a JSON boolean and the Etendo {@code "Y"}/{@code "N"} spelling.
   */
  static ActiveFlag requestedActive(JSONObject body) {
    if (body == null || !body.has(FIELD_ACTIVE) || body.isNull(FIELD_ACTIVE)) {
      return ActiveFlag.UNKNOWN;
    }
    Object value = body.opt(FIELD_ACTIVE);
    if (value instanceof Boolean) {
      return Boolean.TRUE.equals(value) ? ActiveFlag.ACTIVE : ActiveFlag.INACTIVE;
    }
    String text = StringUtils.trimToEmpty(String.valueOf(value));
    if ("Y".equalsIgnoreCase(text) || "true".equalsIgnoreCase(text)) {
      return ActiveFlag.ACTIVE;
    }
    if ("N".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
      return ActiveFlag.INACTIVE;
    }
    return ActiveFlag.UNKNOWN;
  }

  /**
   * Whether the request's client is in forced fiscal test mode.
   *
   * @param obContext the request context, may be {@code null}
   * @return whether the client is in forced fiscal test mode ({@code ETSG_ForceTestMode})
   */
  public static boolean isForceTestMode(OBContext obContext) {
    OBContext ctx = obContext != null ? obContext : OBContext.getOBContext();
    Client client = ctx != null ? ctx.getCurrentClient() : null;
    return client != null && FiscalTestModeResolver.isForceTestModeActive(client);
  }

  /**
   * The organization a create lands in: the session's, since {@code organization} is server-owned
   * (neo-headless §4.12.17).
   *
   * @param obContext the request context, may be {@code null}
   * @return the organization id, or {@code null}
   */
  public static String currentOrganizationId(OBContext obContext) {
    OBContext ctx = obContext != null ? obContext : OBContext.getOBContext();
    return ctx != null && ctx.getCurrentOrganization() != null
        ? ctx.getCurrentOrganization().getId()
        : null;
  }

  /**
   * The active config records of an organization, by record system.
   *
   * @param orgId the organization
   * @return record system to record id, only for the systems that have an active record, in
   *         SII, TBAI, VERI*FACTU order
   */
  public static Map<String, String> activeRecords(String orgId) {
    Map<String, String> active = new LinkedHashMap<>();
    for (String system : List.of(FiscalSifRules.SII, FiscalSifRules.TBAI,
        FiscalSifRules.VERIFACTU)) {
      String id = activeRecordId(system, orgId, null);
      if (id != null) {
        active.put(system, id);
      }
    }
    return active;
  }

  /**
   * Locks the organization's {@code AD_OrgInfo} row ({@code SELECT … FOR UPDATE}) until the
   * current transaction ends, so two activations of the same organization run one after the other:
   * the second one only reads the active configurations once the first has committed (or rolled
   * back), and its "one active SIF" checks see the first one's records. {@code configureFiscalSystem}
   * runs its checks and its batch in the request's single transaction ({@code BatchService} defers
   * every write to its final {@code commitAndClose}), so the lock covers both. The serialization
   * holds only while no handler run by that batch commits mid-flight. Total: an organization
   * without an {@code AD_OrgInfo} row simply locks nothing.
   *
   * @param orgId the organization
   */
  public static void lockOrganization(String orgId) {
    if (orgId == null) {
      return;
    }
    OBDal.getInstance().getSession()
        .createNativeQuery("SELECT ad_org_id FROM ad_orginfo WHERE ad_org_id = :orgId FOR UPDATE")
        .setParameter("orgId", orgId)
        .uniqueResult();
  }

  /**
   * The TicketBAI territory ({@code ARABA}/{@code BIZKAIA}/{@code GIPUZKOA}) of a config.
   *
   * @param recordId the {@code TBAI_CONFIG} id
   * @return the territory, or {@code null}
   */
  public static String tbaiTerritoryOf(String recordId) {
    Object value = OBDal.getInstance().getSession()
        .createNativeQuery("SELECT etsg_sif_territory FROM tbai_config WHERE tbai_config_id = :id")
        .setParameter("id", recordId)
        .uniqueResult();
    return value == null ? null : value.toString();
  }

  /**
   * The name of a system as the Fiscal Configuration window writes it.
   *
   * @param system a system or record system
   * @return the name a person reads
   */
  public static String displayName(String system) {
    return DISPLAY_NAMES.getOrDefault(system, system);
  }

  /**
   * The territory as the SPA names it, in the request language.
   *
   * @param territory a territory id ({@code navarra}, ..., {@code ceuta})
   * @return the name, or {@code territory} itself when it has none
   */
  public static String territoryName(String territory) {
    if (territory == null) {
      return null;
    }
    String key = TERRITORY_NAME_PREFIX + territory;
    String name = NeoMessageText.text(key);
    return name == null || name.equals(key) ? territory : name;
  }

  /**
   * Runs {@code action} as a fiscal system activation ({@link #isFiscalSystemActivation()} is
   * {@code true} inside it).
   *
   * @param action the work
   * @param <T>    its result
   * @return what {@code action} returns
   */
  public static <T> T asFiscalSystemActivation(Supplier<T> action) {
    FISCAL_SYSTEM_ACTIVATION.set(Boolean.TRUE);
    try {
      return action.get();
    } finally {
      FISCAL_SYSTEM_ACTIVATION.remove();
    }
  }

  /**
   * @return whether the current thread is creating the records of {@code configureFiscalSystem}
   */
  public static boolean isFiscalSystemActivation() {
    return Boolean.TRUE.equals(FISCAL_SYSTEM_ACTIVATION.get());
  }

  /**
   * A refusal in the request language, carrying the key in {@code error.messageKeys}.
   *
   * @param status the HTTP status
   * @param key    the {@code AD_MESSAGE} key of this module
   * @param args   the placeholder values
   * @return the response
   */
  public static NeoResponse refusal(int status, String key, Object... args) {
    return NeoMessageText.error(status, key, args);
  }

  /**
   * The text of a message of this module in the request language.
   *
   * @param key  the {@code AD_MESSAGE} key
   * @param args the placeholder values
   * @return the text
   */
  public static String text(String key, Object... args) {
    return NeoMessageText.text(key, args);
  }

  static String activeRecordId(String recordSystem, String orgId, String excludedId) {
    String[] table = TABLES.get(recordSystem);
    if (table == null || orgId == null) {
      return null;
    }
    Object id = OBDal.getInstance().getSession()
        .createNativeQuery("SELECT " + table[1] + " FROM " + table[0]
            + " WHERE ad_org_id = :orgId AND isactive = 'Y' AND " + table[1] + " <> :excluded"
            + " ORDER BY created DESC LIMIT 1")
        .setParameter("orgId", orgId)
        .setParameter("excluded", excludedId != null ? excludedId : "")
        .uniqueResult();
    return id == null ? null : id.toString();
  }

  private static ActiveFlag isActive(String recordSystem, String recordId) {
    String[] table = TABLES.get(recordSystem);
    if (table == null) {
      return ActiveFlag.UNKNOWN;
    }
    Object value = OBDal.getInstance().getSession()
        .createNativeQuery("SELECT isactive FROM " + table[0] + " WHERE " + table[1] + " = :id")
        .setParameter("id", recordId)
        .uniqueResult();
    if (value == null) {
      return ActiveFlag.UNKNOWN;
    }
    return "Y".equals(value.toString()) ? ActiveFlag.ACTIVE : ActiveFlag.INACTIVE;
  }

  private static String organizationOf(String recordSystem, String recordId) {
    String[] table = TABLES.get(recordSystem);
    if (table == null) {
      return null;
    }
    Object value = OBDal.getInstance().getSession()
        .createNativeQuery("SELECT ad_org_id FROM " + table[0] + " WHERE " + table[1] + " = :id")
        .setParameter("id", recordId)
        .uniqueResult();
    return value == null ? null : value.toString();
  }
}
