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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static com.etendoerp.go.schemaforge.FinancialAccountTransactionsSupport.attachOptional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.DimensionDisplayUtility;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.accounting.Costcenter;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.project.Project;

/**
 * Single source of truth for "which accounting dimensions are active right now".
 *
 * <p>Etendo answers that question from <b>two different places</b>, and which one wins is decided
 * by {@code AD_Client.Acctdim_Centrally_Maintained}:
 *
 * <ul>
 *   <li>{@code 'N'} — the flat per-ledger switches in {@code C_AcctSchema_Element.IsActive}
 *       (the "Dimensiones" tab of the Accounting Schema / Esquema Contable window).</li>
 *   <li>{@code 'Y'} — the fine-grained per-dimension / per-document-type / per-level matrix in
 *       {@code AD_Client} + {@code AD_Client_AcctDimension}, resolved by Core's
 *       {@link DimensionDisplayUtility#getAccountingDimensionConfiguration(Client)}. Under this
 *       flag {@code C_AcctSchema_Element.IsActive} is a <b>no-op</b>, so reading it directly gives
 *       the wrong answer (see gap K1 / ETP-4854 in {@code docs/etendo-ad/onboarding-gaps.md}).</li>
 * </ul>
 *
 * <p><b>Which source gates a user-facing surface (ETP-4950 QA round): the flat one, always</b> —
 * {@link #flatActiveDimensionsForCurrentClient()} / {@link #flatActiveDimensionsForAccount(String)}.
 * {@code C_AcctSchema_Element.IsActive} is what the "Esquema contable → Dimensiones" screen writes
 * ({@code GeneralLedgerConfigurationHandler} toggles that column and nothing else), so it is the only
 * dimension configuration a user of Etendo GO can reach. The header-level set additionally subtracts
 * {@code AD_Client_AcctDimension.Show_In_Header='N'}, a table GO ships no screen for — and the shipped
 * reference data marks Product hidden for {@code FAT}, so gating on it made Product permanently
 * invisible in the match-rule form and in the New Movement wizard on every tenant. The header helpers
 * are {@code @Deprecated} and have no production consumer.
 *
 * <p>Dimension codes are Core's ({@code PJ}, {@code CC}, {@code PR}, …, mapped in
 * {@code DimensionDisplayUtility}); the keys this class returns are the lowercase UI keys the
 * Etendo GO frontend already speaks ({@code project}, {@code costcenter}, {@code product}, …).
 */
final class AccountingDimensionsSupport {

  private static final Logger log = LogManager.getLogger(AccountingDimensionsSupport.class);

  /** Document base type of finacc transactions — the movements + automatch surfaces. */
  static final String DOCBASETYPE_FAT = "FAT";

  /** Accounting-dimension UI keys, shared with the frontend payloads. */
  static final String DIM_ORGANIZATION = "organization";
  static final String DIM_BPARTNER = "bpartner";
  static final String DIM_PROJECT = "project";
  static final String DIM_COSTCENTER = "costcenter";
  static final String DIM_PRODUCT = "product";
  static final String DIM_ACTIVITY = "activity";
  static final String DIM_CAMPAIGN = "campaign";
  static final String DIM_SALESREGION = "salesregion";
  static final String DIM_USER1 = "user1";
  static final String DIM_USER2 = "user2";

  /** AcctSchema element type → UI dimension key (AC = account, not a navigable dimension). */
  static final Map<String, String> DIM_BY_ELEMENT = Map.of(
      "OO", DIM_ORGANIZATION, "BP", DIM_BPARTNER, "PR", DIM_PRODUCT, "PJ", DIM_PROJECT,
      "CC", DIM_COSTCENTER, "AY", DIM_ACTIVITY, "MC", DIM_CAMPAIGN,
      "SR", DIM_SALESREGION, "U1", DIM_USER1, "U2", DIM_USER2);

  /** Stable display order for the dimension payloads. */
  static final List<String> DIM_ORDER = List.of(
      DIM_ORGANIZATION, DIM_BPARTNER, DIM_PROJECT, DIM_COSTCENTER, DIM_PRODUCT,
      DIM_ACTIVITY, DIM_CAMPAIGN, DIM_SALESREGION, DIM_USER1, DIM_USER2);

  /** Header level, as {@code DimensionDisplayUtility} spells it in its session-variable keys. */
  private static final String LEVEL_HEADER = DimensionDisplayUtility.DIM_Header;

  private static final String FLAT_ACTIVE_BY_ACCOUNT_SQL =
      "SELECT DISTINCT e.elementtype"
          + "  FROM c_acctschema_element e"
          + "  JOIN c_acctschema s ON s.c_acctschema_id = e.c_acctschema_id"
          + " WHERE s.isactive = 'Y' AND e.isactive = 'Y'"
          + "   AND s.ad_client_id = (SELECT ad_client_id FROM fin_financial_account"
          + "                          WHERE fin_financial_account_id = ?)"; // NOSONAR java:S2077

  private static final String FLAT_ACTIVE_BY_CLIENT_SQL =
      "SELECT DISTINCT e.elementtype"
          + "  FROM c_acctschema_element e"
          + "  JOIN c_acctschema s ON s.c_acctschema_id = e.c_acctschema_id"
          + " WHERE s.isactive = 'Y' AND e.isactive = 'Y'"
          + "   AND s.ad_client_id = ?"; // NOSONAR java:S2077

  /**
   * Dimensions explicitly hidden from a document header via
   * {@code ad_client_acctdimension.show_in_header = 'N'}. Header dimensions default to visible
   * when there is no override row (matching Classic), so the header set is "active dimensions
   * minus the ones explicitly hidden here" rather than only the rows flagged to show.
   */
  private static final String HIDDEN_HEADER_BY_ACCOUNT_SQL =
      "SELECT DISTINCT d.dimension"
          + "  FROM ad_client_acctdimension d"
          + " WHERE d.isactive = 'Y' AND d.show_in_header = 'N' AND d.docbasetype = ?"
          + "   AND d.ad_client_id = (SELECT ad_client_id FROM fin_financial_account"
          + "                          WHERE fin_financial_account_id = ?)"; // NOSONAR java:S2077

  private static final String HIDDEN_HEADER_BY_CLIENT_SQL =
      "SELECT DISTINCT d.dimension"
          + "  FROM ad_client_acctdimension d"
          + " WHERE d.isactive = 'Y' AND d.show_in_header = 'N' AND d.docbasetype = ?"
          + "   AND d.ad_client_id = ?"; // NOSONAR java:S2077

  private AccountingDimensionsSupport() {
  }

  // ---------------------------------------------------------------------------
  // Flat source (C_AcctSchema_Element) — authoritative only when NOT centrally maintained
  // ---------------------------------------------------------------------------

  /** Active chart-of-accounts elements of the account's client, as UI dimension keys. */
  static Set<String> flatActiveDimensionsForAccount(String accountId) throws Exception {
    return queryDimensions(FLAT_ACTIVE_BY_ACCOUNT_SQL, "elementtype", accountId);
  }

  /** Active chart-of-accounts elements of the given client, as UI dimension keys. */
  static Set<String> flatActiveDimensionsForClient(String clientId) throws Exception {
    return queryDimensions(FLAT_ACTIVE_BY_CLIENT_SQL, "elementtype", clientId);
  }

  /**
   * Active chart-of-accounts elements of the CURRENT tenant, resolved from {@link OBContext} rather
   * than from an id supplied by the request — so a caller can never be pointed at another tenant's
   * accounting configuration (ETP-4950).
   *
   * <p>This is the set the "Esquema contable &rarr; Dimensiones" screen writes, and therefore the only
   * one a user of Etendo GO can actually manage: {@code GeneralLedgerConfigurationHandler} toggles
   * {@code C_AcctSchema_Element.IsActive} and nothing else. See {@link #flatActiveDimensionsForAccount}
   * for the rationale on why the header-level source was abandoned.
   */
  static Set<String> flatActiveDimensionsForCurrentClient() throws Exception {
    Client client = currentClient();
    if (client == null) {
      return new HashSet<>();
    }
    return flatActiveDimensionsForClient(client.getId());
  }

  // ---------------------------------------------------------------------------
  // Header-level set — NO PRODUCTION CONSUMER (see the deprecation note below)
  // ---------------------------------------------------------------------------

  /**
   * Dimensions available at the header of a {@code docBaseType} document for the current tenant,
   * honouring {@code Acctdim_Centrally_Maintained}.
   *
   * @param client      the tenant whose configuration decides the answer
   * @param docBaseType the document base type, e.g. {@link #DOCBASETYPE_FAT}
   * @deprecated Do not gate a user-facing surface on this. It is the chart of accounts minus
   *     {@code AD_Client_AcctDimension.Show_In_Header='N'}, and Etendo GO ships no screen for
   *     {@code AD_Client_AcctDimension} — so whatever it subtracts is unreachable for the user. The
   *     shipped reference data marks Product hidden for {@code FAT}, which is why Product could never
   *     appear in the match-rule form nor in the New Movement wizard on any tenant provisioned from
   *     the published dataset (ETP-4950 QA round). Use {@link #flatActiveDimensionsForCurrentClient()}
   *     or {@link #flatActiveDimensionsForAccount(String)} instead — that is what the Accounting
   *     Schema screen writes. Kept only until its tests are retired.
   */
  @Deprecated
  static Set<String> activeHeaderDimensions(Client client, String docBaseType) throws Exception {
    if (client == null) {
      return new HashSet<>();
    }
    Set<String> flat = flatActiveDimensionsForClient(client.getId());
    if (!isCentrallyMaintained(client)) {
      flat.removeAll(queryDimensions(HIDDEN_HEADER_BY_CLIENT_SQL, "dimension",
          docBaseType, client.getId()));
      return flat;
    }
    return centrallyMaintainedHeaderSet(client, docBaseType, flat);
  }

  /**
   * Same as {@link #activeHeaderDimensions(Client, String)} but resolving the tenant from a
   * financial account. Falls back to the flat source when the account (or its client) cannot be
   * resolved, so a caller is never left with an empty set because of a lookup failure.
   *
   * @deprecated see {@link #activeHeaderDimensions(Client, String)}.
   */
  @Deprecated
  static Set<String> activeHeaderDimensionsForAccount(String accountId, String docBaseType)
      throws Exception {
    Client client = clientOfAccount(accountId);
    if (client != null) {
      return activeHeaderDimensions(client, docBaseType);
    }
    Set<String> flat = flatActiveDimensionsForAccount(accountId);
    flat.removeAll(queryDimensions(HIDDEN_HEADER_BY_ACCOUNT_SQL, "dimension",
        docBaseType, accountId));
    return flat;
  }

  /**
   * The current tenant's header dimensions for {@code docBaseType}.
   *
   * @deprecated see {@link #activeHeaderDimensions(Client, String)}.
   */
  @Deprecated
  static Set<String> activeHeaderDimensionsForCurrentClient(String docBaseType) throws Exception {
    return activeHeaderDimensions(currentClient(), docBaseType);
  }

  // ---------------------------------------------------------------------------
  // Serialization
  // ---------------------------------------------------------------------------

  /** Serializes a dimension-key set in the canonical display order. */
  static JSONArray toOrderedArray(Set<String> keys) {
    JSONArray arr = new JSONArray();
    for (String key : DIM_ORDER) {
      if (keys.contains(key)) {
        arr.put(key);
      }
    }
    return arr;
  }

  // ---------------------------------------------------------------------------
  // Applying dimensions to a transaction
  // ---------------------------------------------------------------------------

  /**
   * Copies the accounting dimensions carried by a {@code createTransactionForRule} spec (project,
   * cost center, product) onto the transaction Automatch generates, skipping any dimension that is
   * not in {@code allowed}.
   *
   * <p>Before ETP-4950 nothing read those three keys, so a matching rule that declared them
   * produced a movement without them. A value whose dimension was later switched off in the
   * Accounting Schema is deliberately <b>ignored</b> rather than cleared: the movement is generated
   * without it, and the rule starts applying it again if the dimension is re-enabled.
   *
   * <p>{@code allowedSupplier} is only invoked when the spec actually asks for a dimension. That
   * laziness is load-bearing, not an optimization: resolving the configuration runs SQL whose
   * {@code while (rs.next())} loop must not be entered for the callers that never carry a dimension
   * (a rule with none, and the difference postings in {@code ReconciliationDifferenceSupport}).
   *
   * @param trx             the transaction being built
   * @param spec            the {@code createPayment} spec, source of the dimension ids
   * @param allowedSupplier resolves the dimensions assignable for this account, lazily
   */
  static void applyRuleDimensions(FIN_FinaccTransaction trx, JSONObject spec,
      Supplier<Set<String>> allowedSupplier) {
    if (!requestsAnyDimension(spec)) {
      return;
    }
    Set<String> allowed = allowedSupplier.get();
    attachDimension(spec, AutoMatchSupport.KEY_PROJECT_ID, allowed, DIM_PROJECT,
        Project.class, trx::setProject);
    attachDimension(spec, AutoMatchSupport.KEY_COSTCENTER_ID, allowed, DIM_COSTCENTER,
        Costcenter.class, trx::setCostCenter);
    attachDimension(spec, AutoMatchSupport.KEY_PRODUCT_ID, allowed, DIM_PRODUCT,
        Product.class, trx::setProduct);
  }

  /** True when the spec carries a non-blank id for at least one accounting dimension. */
  static boolean requestsAnyDimension(JSONObject spec) {
    return StringUtils.isNotBlank(spec.optString(AutoMatchSupport.KEY_PROJECT_ID, null))
        || StringUtils.isNotBlank(spec.optString(AutoMatchSupport.KEY_COSTCENTER_ID, null))
        || StringUtils.isNotBlank(spec.optString(AutoMatchSupport.KEY_PRODUCT_ID, null));
  }

  private static <T extends BaseOBObject> void attachDimension(JSONObject spec, String specKey,
      Set<String> allowed, String dimensionKey, Class<T> entityClass, Consumer<T> setter) {
    if (!allowed.contains(dimensionKey)) {
      return;
    }
    attachOptional(spec.optString(specKey, null), entityClass, setter);
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /**
   * Reads the header set out of Core's centrally-maintained configuration. Core only emits
   * {@code $Element_<DIM>_<DOCBASETYPE>_<LEVEL>} entries for the dimensions its Client window can
   * configure (organization, business partner, project, product, cost center, user1, user2); the
   * remaining ones (activity, campaign, sales region) have no entry at all, so for those we keep
   * the flat chart-of-accounts answer instead of reading absence as "inactive".
   */
  private static Set<String> centrallyMaintainedHeaderSet(Client client, String docBaseType,
      Set<String> flat) {
    Map<String, String> config = DimensionDisplayUtility.getAccountingDimensionConfiguration(client);
    Set<String> header = new HashSet<>();
    for (Map.Entry<String, String> entry : DIM_BY_ELEMENT.entrySet()) {
      String uiKey = entry.getValue();
      String configured = config.get(sessionKey(entry.getKey(), docBaseType));
      if (configured == null) {
        if (flat.contains(uiKey)) {
          header.add(uiKey);
        }
      } else if ("Y".equals(configured)) {
        header.add(uiKey);
      }
    }
    return header;
  }

  /** {@code $Element_PJ_FAT_H} — the key layout {@code DimensionDisplayUtility} writes. */
  private static String sessionKey(String elementCode, String docBaseType) {
    return DimensionDisplayUtility.ELEMENT + "_" + elementCode + "_"
        + StringUtils.trimToEmpty(docBaseType) + "_" + LEVEL_HEADER;
  }

  private static boolean isCentrallyMaintained(Client client) {
    try {
      OBContext.setAdminMode(true);
      return Boolean.TRUE.equals(client.isAcctdimCentrallyMaintained());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static Client clientOfAccount(String accountId) {
    if (StringUtils.isBlank(accountId)) {
      return null;
    }
    try {
      FIN_FinancialAccount account =
          TenantOwnership.loadOwned(FIN_FinancialAccount.class, accountId);
      return account != null ? account.getClient() : null;
    } catch (Exception e) {
      log.debug("Could not resolve the client of financial account {}: {}", accountId,
          e.getMessage());
      return null;
    }
  }

  private static Client currentClient() {
    try {
      return OBDal.getInstance()
          .get(Client.class, OBContext.getOBContext().getCurrentClient().getId());
    } catch (Exception e) {
      log.debug("Could not resolve the current client: {}", e.getMessage());
      return null;
    }
  }

  /** Runs a single-column dimension-code query and maps the codes to UI dimension keys. */
  private static Set<String> queryDimensions(String sql, String column, String... params)
      throws Exception {
    Set<String> keys = new HashSet<>();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setString(i + 1, params[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String key = DIM_BY_ELEMENT.get(StringUtils.trimToEmpty(rs.getString(column)));
          if (key != null) {
            keys.add(key);
          }
        }
      }
    }
    return keys;
  }
}
