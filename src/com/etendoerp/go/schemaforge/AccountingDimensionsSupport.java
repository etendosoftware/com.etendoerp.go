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
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.accounting.Costcenter;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.project.Project;

/**
 * Single source of truth for "which accounting dimensions are active right now", for every
 * consumer of the {@code FIN_Finacc_Transaction} entity — the New/Edit Movement UI, the
 * accounting-dimensions grid card, Automatch's generated transactions, and reconciliation
 * difference postings.
 *
 * <p><b>ETP-5101 QA finding (this class's history):</b> an earlier version of this class read
 * {@code AD_Client_AcctDimension}'s per-{@code docBaseType}/level matrix — the fine-grained
 * override Core's centrally-maintained dimension configuration exposes — for
 * {@code docBaseType = FAT}, on the theory that a {@code FIN_Finacc_Transaction} needed the same
 * document-type-scoped treatment a real header+lines document (an invoice, a shipment) gets. Two
 * problems surfaced live: (1) the level actually queried was Header, but a
 * {@code FIN_Finacc_Transaction} is tab level 1 under {@code FIN_Financial_Account} (tab level 0)
 * per {@code AD_Tab} — a document <i>line</i>, never a header — so even the "fixed" Lines-level
 * query was reading Core configuration a Classic admin has no reason to associate with financial
 * account movements at all; (2) explicitly, per product direction: FAT dimension visibility must
 * come from the <b>same</b> flat, per-tenant switch every other GO window already uses (the
 * "Ledger Configuration" screen, {@code C_AcctSchema_Element.IsActive}) — not a document-type
 * override no other GO surface consults. This class is now a thin, single-source wrapper around
 * that flat switch; the {@code Acctdim_Centrally_Maintained} / {@code AD_Client_AcctDimension}
 * machinery was removed entirely rather than left unused.
 *
 * <p>Dimension codes are Core's ({@code PJ}, {@code CC}, {@code PR}, …, mapped in
 * {@code DimensionDisplayUtility}); the keys this class returns are the lowercase UI keys the
 * Etendo GO frontend already speaks ({@code project}, {@code costcenter}, {@code product}, …).
 */
final class AccountingDimensionsSupport {

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

  private AccountingDimensionsSupport() {
  }

  // ---------------------------------------------------------------------------
  // Flat source (C_AcctSchema_Element) — the single source of truth for every caller
  // ---------------------------------------------------------------------------

  /** Active chart-of-accounts elements of the account's client, as UI dimension keys. */
  static Set<String> flatActiveDimensionsForAccount(String accountId) throws Exception {
    return queryDimensions(FLAT_ACTIVE_BY_ACCOUNT_SQL, "elementtype", accountId);
  }

  /** Active chart-of-accounts elements of the given client, as UI dimension keys. */
  static Set<String> flatActiveDimensionsForClient(String clientId) throws Exception {
    return queryDimensions(FLAT_ACTIVE_BY_CLIENT_SQL, "elementtype", clientId);
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
