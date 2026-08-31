/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge.util;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openbravo.dal.service.OBDal;

/**
 * Single source of truth for "is this document type accounting-relevant" — the predicate that
 * decides whether a document type/category should be shown in a document-oriented UI at all.
 *
 * <p>Originally lived entirely inside {@code NotPostedDocumentsHandler} (the Not Posted
 * Documents window). Extracted here (ETP-4948 Issue 3) so the Calendar window's
 * {@code documents} entity ({@code C_PeriodControl}, {@code PeriodControlDocOpenCloseHandler})
 * can apply the exact same rule — reusing two different document-type code vocabularies (see
 * below) must never let the two windows silently drift apart on what counts as "relevant".</p>
 *
 * <p>Two vocabularies feed into the same table-id-based check:
 * <ul>
 *   <li>{@code NotPostedDocumentsHandler}'s own {@code DOCUMENT_TYPE_CODE_TO_TABLE_ID} /
 *       {@code DOCUMENT_TYPE_TO_TABLE_ID} maps — keyed by the custom "ETBLKP_Documents"
 *       AD_Reference values / {@code NoPostedDocumentDS} document-type labels;</li>
 *   <li>{@link #DOC_BASE_TYPE_TO_TABLE_ID} here — keyed by the classic "Document Base Type"
 *       AD_Reference values ({@code C_DocType.DocBaseType} / {@code C_PeriodControl
 *       .DocumentCategory}), which {@code PeriodControlDocOpenCloseHandler} needs.</li>
 * </ul>
 * Both vocabularies are just different labels for the same underlying tables — e.g.
 * Not-Posted-Documents' {@code "CA"} (Cost Adjustment) and Calendar's {@code "CAD"} both resolve
 * to {@code M_CostAdjustment} — so keying the actual exclusion/relevance rule on
 * {@code AD_Table_ID} is what lets both windows reuse one rule without reconciling their code
 * spaces field by field.</p>
 */
public final class AccountingDocumentTypeSupport {

  private AccountingDocumentTypeSupport() {
  }

  /**
   * {@code AD_Table_ID}s excluded from every accounting-relevant document view, regardless of
   * {@code c_acctschema_table} state. These tables exist in the accounting schema, but either
   * APRM structurally disables direct bulk-posting on them ({@code POSTED = 'D'} on all
   * documents — accounting flows through {@code FIN_Finacc_Transaction} instead), or the product
   * owner accepted hiding them as a tradeoff (ETP-4452, "BMP, DD, LC, LCC, CA" in
   * Not-Posted-Documents' own code space).
   *
   * <p>ETP-4948: confirmed by the product owner to apply identically to the Calendar window's
   * period-control document breakdown — no divergence between the two windows' document-type
   * universes. To re-enable any of these, remove its table id from this set.
   */
  private static final Set<String> APRM_DISABLED_TABLE_IDS = new HashSet<>(Arrays.asList(
      "D4C23A17190649E7B78F55A05AF3438C", // FIN_BankStatement  — posted='D' ~99.9% (APRM)
      "D1A97202E832470285C9B1EB026D54E2", // FIN_Payment        — posted='D' ~99.9% (APRM)
      "B1B7075C46934F0A9FD4C4D0F1457B42", // FIN_Reconciliation — posted='D' ~89% (APRM)
      "325",                               // M_Production       — globally excluded, ETP-4452
      "30721072789F410E9606D2235CB2A226", // FIN_Doubtful_Debt  — globally excluded, ETP-4452
      "082F967CDF7245EB9A150941F326C45C", // M_LandedCost       — globally excluded, ETP-4452
      "55A984C314FD4C4FB5E7C32DE36BB07B", // M_LC_Cost          — globally excluded, ETP-4452
      "D022B92163074E5E82449C8E0B5AFDF6"  // M_CostAdjustment   — globally excluded, ETP-4452
  ));

  /**
   * Maps each {@code DocBaseType} code (AD_Reference "Document Base Type", the values of
   * {@code C_DocType.DocBaseType} / {@code C_PeriodControl.DocumentCategory}) to the
   * {@code AD_Table_ID} of its backing table.
   *
   * <p>Source: {@code SELECT DISTINCT docbasetype, ad_table_id FROM c_doctype}, a stable,
   * structural correspondence — which table implements a given document base type does not vary
   * per tenant, only whether that table is actually configured for accounting
   * ({@code c_acctschema_table}) does. A code with no entry here (e.g. {@code ARRP},
   * {@code OBCVAT_MS}, {@code CMA}, {@code PJI}, {@code PPR}, {@code WRE} — DocBaseTypes with no
   * {@code C_DocType} configured for any table in current Etendo/Etendo GO modules) resolves to
   * {@code null} and is therefore never accounting-relevant.
   */
  private static final Map<String, String> DOC_BASE_TYPE_TO_TABLE_ID = new HashMap<>();

  static {
    DOC_BASE_TYPE_TO_TABLE_ID.put("AMZ",    "800060");                               // A_Amortization
    DOC_BASE_TYPE_TO_TABLE_ID.put("APC",    "318");                                   // C_Invoice
    DOC_BASE_TYPE_TO_TABLE_ID.put("API",    "318");                                   // C_Invoice
    DOC_BASE_TYPE_TO_TABLE_ID.put("APP",    "D1A97202E832470285C9B1EB026D54E2");      // FIN_Payment
    DOC_BASE_TYPE_TO_TABLE_ID.put("APPP",   "B9437A72163445C59A0A585209C8ECE5");      // FIN_Payment_Proposal
    DOC_BASE_TYPE_TO_TABLE_ID.put("ARC",    "318");                                   // C_Invoice
    DOC_BASE_TYPE_TO_TABLE_ID.put("ARI",    "318");                                   // C_Invoice
    DOC_BASE_TYPE_TO_TABLE_ID.put("ARI_RM", "318");                                   // C_Invoice
    DOC_BASE_TYPE_TO_TABLE_ID.put("ARR",    "D1A97202E832470285C9B1EB026D54E2");      // FIN_Payment
    DOC_BASE_TYPE_TO_TABLE_ID.put("BSF",    "D4C23A17190649E7B78F55A05AF3438C");      // FIN_BankStatement
    DOC_BASE_TYPE_TO_TABLE_ID.put("CAD",    "D022B92163074E5E82449C8E0B5AFDF6");      // M_CostAdjustment
    DOC_BASE_TYPE_TO_TABLE_ID.put("CMB",    "392");                                   // C_BankStatement
    DOC_BASE_TYPE_TO_TABLE_ID.put("CMC",    "407");                                   // C_Cash
    DOC_BASE_TYPE_TO_TABLE_ID.put("DDB",    "30721072789F410E9606D2235CB2A226");      // FIN_Doubtful_Debt
    DOC_BASE_TYPE_TO_TABLE_ID.put("DPM",    "800176");                               // C_DP_Management
    DOC_BASE_TYPE_TO_TABLE_ID.put("FAT",    "4D8C3B3C31D1410DA046140C9F024D17");      // FIN_Finacc_Transaction
    DOC_BASE_TYPE_TO_TABLE_ID.put("GLJ",    "224");                                   // GL_Journal
    DOC_BASE_TYPE_TO_TABLE_ID.put("IAU",    "F6B6AD5679FF4A798D2A3D44B232C52C");      // M_CA_InventoryAmt
    DOC_BASE_TYPE_TO_TABLE_ID.put("LCC",    "55A984C314FD4C4FB5E7C32DE36BB07B");      // M_LC_Cost
    DOC_BASE_TYPE_TO_TABLE_ID.put("LDC",    "082F967CDF7245EB9A150941F326C45C");      // M_LandedCost
    DOC_BASE_TYPE_TO_TABLE_ID.put("MIC",    "800168");                               // M_Internal_Consumption
    DOC_BASE_TYPE_TO_TABLE_ID.put("MMI",    "321");                                   // M_Inventory
    DOC_BASE_TYPE_TO_TABLE_ID.put("MMM",    "323");                                   // M_Movement
    DOC_BASE_TYPE_TO_TABLE_ID.put("MMP",    "325");                                   // M_Production
    DOC_BASE_TYPE_TO_TABLE_ID.put("MMR",    "319");                                   // M_InOut
    DOC_BASE_TYPE_TO_TABLE_ID.put("MMS",    "319");                                   // M_InOut
    DOC_BASE_TYPE_TO_TABLE_ID.put("MXI",    "472");                                   // M_MatchInv
    DOC_BASE_TYPE_TO_TABLE_ID.put("MXP",    "473");                                   // M_MatchPO
    DOC_BASE_TYPE_TO_TABLE_ID.put("POO",    "259");                                   // C_Order
    DOC_BASE_TYPE_TO_TABLE_ID.put("POR",    "259");                                   // C_Order
    DOC_BASE_TYPE_TO_TABLE_ID.put("REC",    "B1B7075C46934F0A9FD4C4D0F1457B42");      // FIN_Reconciliation
    DOC_BASE_TYPE_TO_TABLE_ID.put("SOO",    "259");                                   // C_Order
    DOC_BASE_TYPE_TO_TABLE_ID.put("STM",    "800019");                               // C_Settlement
    DOC_BASE_TYPE_TO_TABLE_ID.put("STT",    "800019");                               // C_Settlement
  }

  /**
   * Returns the set of {@code AD_Table_ID} values that have at least one active accounting
   * schema entry ({@code c_acctschema_table.isactive = 'Y'}). Callers filtering many rows in one
   * request should call this ONCE and reuse the result — never per row.
   */
  @SuppressWarnings("unchecked")
  public static Set<String> loadTablesWithActiveAccounting() {
    List<Object> rows = OBDal.getInstance().getSession()
        .createNativeQuery(
            "SELECT DISTINCT ad_table_id FROM c_acctschema_table WHERE isactive = 'Y'")
        .list();
    Set<String> ids = new HashSet<>();
    for (Object row : rows) {
      if (row instanceof String) {
        ids.add((String) row);
      }
    }
    return ids;
  }

  /** True when {@code tableId} is one of the tables structurally excluded regardless of
   *  accounting-schema configuration (see {@link #APRM_DISABLED_TABLE_IDS}). Null-safe. */
  public static boolean isAprmDisabledTable(String tableId) {
    return tableId != null && APRM_DISABLED_TABLE_IDS.contains(tableId);
  }

  /**
   * True when {@code tableId} is both actively registered in {@code c_acctschema_table}
   * ({@code accountedTableIds}, from {@link #loadTablesWithActiveAccounting()}) and not
   * APRM-disabled. Null-safe on {@code tableId}.
   */
  public static boolean isTableAccountingRelevant(String tableId, Set<String> accountedTableIds) {
    return tableId != null && accountedTableIds.contains(tableId) && !isAprmDisabledTable(tableId);
  }

  /**
   * True when {@code docBaseTypeCode} (a {@code C_PeriodControl.DocumentCategory} /
   * {@code C_DocType.DocBaseType} value) resolves to a table that is both actively registered in
   * {@code c_acctschema_table} and not APRM-disabled.
   *
   * @param docBaseTypeCode the DocBaseType code to check; {@code null} resolves to not-relevant
   * @param accountedTableIds the result of {@link #loadTablesWithActiveAccounting()}, loaded ONCE
   *                          by the caller and reused across every row in the same request
   */
  public static boolean isAccountingRelevant(String docBaseTypeCode, Set<String> accountedTableIds) {
    return isTableAccountingRelevant(DOC_BASE_TYPE_TO_TABLE_ID.get(docBaseTypeCode), accountedTableIds);
  }
}
