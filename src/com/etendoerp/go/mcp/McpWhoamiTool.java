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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.mcp;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;

import com.etendoerp.go.oauth2.ApiScopes;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoAccountingAccessPolicy;
import com.etendoerp.go.schemaforge.util.ReportAccessCatalog;
import com.etendoerp.go.schemaforge.util.RoleAccessMatrix;

/**
 * The {@code etendo_whoami} tool (etendo-ai#53, gap G17): who this MCP connection is — user, role,
 * client, organization — and what that role and token can do, in one side-effect-free call.
 *
 * <p>Every MCP connection runs as its own user, role, client and organization, and what an agent
 * can read or change depends on the role. Without this tool an agent that saw a spec answer
 * {@code forbidden} on a second connection had no way to tell a role change from a permission
 * change, and once created and deleted a test purchase order just to learn who it was.</p>
 *
 * <p>Read straight from the {@link OBContext} the servlet entered for this call (and the token's
 * scopes), so it reports exactly the identity the other tools run under. It writes nothing.</p>
 *
 * <p>With {@code includePermissions:true} it also lists the Etendo GO windows and reports the role
 * can reach, from {@link RoleAccessMatrix} — the rows "Configuración &gt; Roles" renders —
 * reduced to this one role: {@code none} rows are left out, and a window row names the MCP specs
 * it is served by instead of its {@code AD_Window_ID}. An admin or client-admin role gets
 * {@code full} on every row: that is the bypass the access checks apply, not what its grant rows
 * say.</p>
 */
final class McpWhoamiTool {

  static final String KEY_USER = "user";
  static final String KEY_ROLE = "role";
  static final String KEY_CLIENT = "client";
  static final String KEY_ORGANIZATION = "organization";
  static final String KEY_CAPABILITIES = "capabilities";
  static final String KEY_SHOW_ACCOUNTING_FIELDS = "showAccountingFields";
  static final String KEY_SCOPES = "scopes";
  static final String KEY_CURRENT_DATE = "currentDate";
  static final String KEY_TIME_ZONE = "timeZone";
  static final String KEY_CURRENCY = "currency";
  static final String KEY_LANGUAGE = "language";
  static final String KEY_IS_ADMIN = "isAdmin";
  static final String KEY_WAREHOUSE = "warehouse";
  static final String KEY_PERMISSIONS = "permissions";
  static final String KEY_WINDOWS = "windows";
  static final String KEY_REPORTS = "reports";
  /** Optional argument: also list the role's permissions (larger answer, so opt-in). */
  static final String PARAM_INCLUDE_PERMISSIONS = "includePermissions";

  private static final String KEY_CATEGORIES = "categories";
  private static final String KEY_ACCESS = "access";
  private static final String KEY_SPECS = "specs";
  private static final String KEY_SPEC = "spec";
  /** {@code ETGO_SF_SPEC.SPEC_TYPE} of a window (CRUD) spec. */
  private static final String SPEC_TYPE_WINDOW = "W";

  private static final String KEY_ID = "id";
  private static final String KEY_NAME = "name";

  static final String HINT = "This is the identity every other tool runs under on this "
      + "connection. Another connection may use a different user or role: if a spec that worked "
      + "before now answers forbidden, call etendo_whoami again and compare role before assuming "
      + "the permissions changed. Never create or delete a record to find out who you are.";

  private McpWhoamiTool() {
  }

  /**
   * Handle one {@code etendo_whoami} call.
   *
   * @param arguments the call arguments; only {@code includePermissions} is declared
   * @param scopes    the OAuth2 scopes granted to this connection's token
   * @return the MCP tool result carrying the session context
   */
  static JSONObject handle(JSONObject arguments, Set<String> scopes) {
    boolean includePermissions = arguments != null
        && arguments.optBoolean(PARAM_INCLUDE_PERMISSIONS, false);
    try {
      OBContext context = OBContext.getOBContext();
      JSONObject body = describe(context, scopes, Clock.systemDefaultZone());
      if (includePermissions) {
        body.put(KEY_PERMISSIONS, permissions(context.getRole()));
      }
      return McpToolRouter.wrapAsTextContent(body);
    } catch (JSONException e) {
      throw new McpToolException("Error building the etendo_whoami response", e);
    }
  }

  /**
   * The session context as the agent reads it.
   *
   * @param context the context the servlet entered for this call
   * @param scopes  the token's scopes
   * @param clock   the server clock; its zone is the one business dates are interpreted in
   * @return the body
   * @throws JSONException on serialization failure
   */
  static JSONObject describe(OBContext context, Set<String> scopes, Clock clock)
      throws JSONException {
    Role role = context.getRole();
    Organization org = context.getCurrentOrganization();
    Warehouse warehouse = context.getWarehouse();
    Language language = context.getLanguage();

    JSONObject body = new JSONObject();
    body.put(KEY_USER, user(context.getUser()));
    body.put(KEY_ROLE, role(role));
    body.put(KEY_CLIENT, client(context.getCurrentClient()));
    body.put(KEY_ORGANIZATION, idName(org == null ? null : org.getId(),
        org == null ? null : org.getName()));
    body.put(KEY_WAREHOUSE, idName(warehouse == null ? null : warehouse.getId(),
        warehouse == null ? null : warehouse.getName()));
    body.put(KEY_CAPABILITIES, new JSONObject()
        .put(KEY_SHOW_ACCOUNTING_FIELDS, NeoAccountingAccessPolicy.canSeeAccounting(role))
        .put(KEY_SCOPES, canonicalScopes(scopes)));
    body.put(KEY_CURRENT_DATE, LocalDate.now(clock).toString());
    body.put(KEY_TIME_ZONE, clock.getZone().getId());
    body.put(KEY_CURRENCY, org == null ? JSONObject.NULL : currency(org.getId()));
    body.put(KEY_LANGUAGE, language == null ? JSONObject.NULL : language.getLanguage());
    body.put(McpConstants.KEY_HINT, HINT);
    return body;
  }

  /**
   * The rows of the "Configuración &gt; Roles" matrix this role can reach, grouped by the same
   * categories. A row it cannot reach is left out: absent means no access.
   *
   * @param role the connection's role; {@code null} has no permissions to list
   * @return {@code {windows:{categories:[{name, windows:[{name,specs,access}]}]},
   *     reports:{categories:[{name, reports:[{spec,name,access}]}]}}}, or {@code null}
   * @throws JSONException on serialization failure
   */
  static Object permissions(Role role) throws JSONException {
    return role == null ? JSONObject.NULL : permissions(role, mcpSpecsByWindowId());
  }

  /**
   * {@link #permissions(Role)} with the window → spec names map already resolved.
   *
   * @param role             the connection's role, not {@code null}
   * @param specsByWindowId  the MCP-visible window spec names of each window
   * @return the permissions body
   * @throws JSONException on serialization failure
   */
  static JSONObject permissions(Role role, Map<String, List<String>> specsByWindowId)
      throws JSONException {
    boolean admin = NeoAccessHelper.isAdminOrClientAdmin(role);
    String roleId = role.getId();
    Map<String, Window> windows = RoleAccessMatrix.resolveActiveEtendoGoWindowsById();
    Map<String, String> windowTiers = admin ? Map.of()
        : RoleAccessMatrix.resolveTierMap(role, windows);
    Map<String, String> reportTiers = admin ? Map.of()
        : ReportAccessCatalog.resolveTierMap(role);
    return new JSONObject()
        .put(KEY_WINDOWS, reachableRows(
            RoleAccessMatrix.buildMatrix(windows, Map.of(roleId, windowTiers)),
            KEY_WINDOWS, roleId, admin, specsByWindowId))
        .put(KEY_REPORTS, reachableRows(
            RoleAccessMatrix.buildReportsMatrix(Map.of(roleId, reportTiers)),
            KEY_REPORTS, roleId, admin, null));
  }

  /**
   * Reduces a matrix to this role: each row's per-role {@code access} map becomes the role's tier
   * ({@code full} for an admin role, whatever its grants say), {@code none} rows and the
   * categories they empty are dropped, and the row id becomes what the agent addresses tools
   * with — a window row's MCP spec names ({@code specs}, empty when no spec of that window is in
   * the catalog), a report row's own spec name ({@code spec}, the report ids are spec names).
   */
  private static JSONObject reachableRows(JSONObject matrix, String rowsKey, String roleId,
      boolean admin, Map<String, List<String>> specsByWindowId) throws JSONException {
    JSONArray categories = matrix.getJSONArray(KEY_CATEGORIES);
    JSONArray kept = new JSONArray();
    for (int i = 0; i < categories.length(); i++) {
      JSONObject category = categories.getJSONObject(i);
      JSONArray rows = category.getJSONArray(rowsKey);
      JSONArray reachable = new JSONArray();
      for (int j = 0; j < rows.length(); j++) {
        JSONObject row = rows.getJSONObject(j);
        String tier = admin ? RoleAccessMatrix.FULL
            : row.getJSONObject(KEY_ACCESS).getString(roleId);
        if (RoleAccessMatrix.NONE.equals(tier)) {
          continue;
        }
        String id = row.getString(KEY_ID);
        JSONObject out = new JSONObject();
        if (specsByWindowId != null) {
          out.put(KEY_NAME, row.getString(KEY_NAME));
          out.put(KEY_SPECS, new JSONArray(specsByWindowId.getOrDefault(id, List.of())));
        } else {
          out.put(KEY_SPEC, id);
          out.put(KEY_NAME, row.getString(KEY_NAME));
        }
        out.put(KEY_ACCESS, tier);
        reachable.put(out);
      }
      if (reachable.length() > 0) {
        kept.put(new JSONObject().put(KEY_NAME, category.getString(KEY_NAME))
            .put(rowsKey, reachable));
      }
    }
    return new JSONObject().put(KEY_CATEGORIES, kept);
  }

  /**
   * The names of the active window specs published in the MCP catalog, per {@code AD_Window_ID},
   * sorted — what {@code etendo_list} and the other CRUD tools take as {@code spec}.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, List<String>> mcpSpecsByWindowId() {
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_SHOWINMCP, true));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_SPECTYPE, SPEC_TYPE_WINDOW));
    criteria.add(Restrictions.isNotNull(SFSpec.PROPERTY_ADWINDOW));
    criteria.addOrder(Order.asc(SFSpec.PROPERTY_NAME));
    Map<String, List<String>> specs = new HashMap<>();
    for (SFSpec spec : (List<SFSpec>) criteria.list()) {
      specs.computeIfAbsent(spec.getADWindow().getId(), k -> new ArrayList<>())
          .add(spec.getName());
    }
    return specs;
  }

  private static Object user(User user) throws JSONException {
    if (user == null) {
      return JSONObject.NULL;
    }
    return new JSONObject()
        .put(KEY_ID, user.getId())
        .put(KEY_NAME, user.getName())
        .put("username", nullable(user.getUsername()));
  }

  private static Object role(Role role) throws JSONException {
    if (role == null) {
      return JSONObject.NULL;
    }
    return new JSONObject()
        .put(KEY_ID, role.getId())
        .put(KEY_NAME, role.getName())
        // System role or client admin: the same bypass the access checks apply.
        .put(KEY_IS_ADMIN, NeoAccessHelper.isAdminOrClientAdmin(role));
  }

  private static Object client(Client client) throws JSONException {
    return client == null ? JSONObject.NULL : idName(client.getId(), client.getName());
  }

  private static Object idName(String id, String name) throws JSONException {
    if (id == null) {
      return JSONObject.NULL;
    }
    return new JSONObject().put(KEY_ID, id).put(KEY_NAME, nullable(name));
  }

  /**
   * The organization's functional currency, by the ERP's own chain (organization, legal entity,
   * client). {@code null} when none resolves: unlike {@code GET /sws/neo/session} there is no
   * placeholder currency, an agent would take it for the real one.
   */
  private static Object currency(String orgId) throws JSONException {
    String currencyId = OBCurrencyUtils.getOrgCurrency(orgId);
    Currency currency = currencyId == null ? null
        : OBDal.getReadOnlyInstance().get(Currency.class, currencyId);
    if (currency == null) {
      return JSONObject.NULL;
    }
    Long precision = currency.getStandardPrecision();
    return new JSONObject()
        .put(KEY_ID, currency.getId())
        .put("isoCode", currency.getISOCode())
        .put("standardPrecision", precision == null ? JSONObject.NULL : precision);
  }

  /** The granted scopes under their current {@code etendo:} names, sorted for a stable answer. */
  private static JSONArray canonicalScopes(Set<String> scopes) {
    Set<String> canonical = new TreeSet<>();
    if (scopes != null) {
      for (String scope : scopes) {
        canonical.add(ApiScopes.canonical(scope));
      }
    }
    return new JSONArray(canonical);
  }

  private static Object nullable(Object value) {
    return value == null ? JSONObject.NULL : value;
  }
}
