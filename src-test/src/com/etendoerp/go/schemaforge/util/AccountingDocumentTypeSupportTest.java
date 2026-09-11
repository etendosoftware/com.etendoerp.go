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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link AccountingDocumentTypeSupport} — the shared "is this document type
 * accounting-relevant" predicate reused by {@code NotPostedDocumentsHandler} (its own
 * ETBLKP_Documents code space) and {@code PeriodControlDocOpenCloseHandler} (the classic
 * DocBaseType code space, ETP-4948 Issue 3).
 */
public class AccountingDocumentTypeSupportTest {

  private static final String TABLE_C_INVOICE = "318";
  private static final String TABLE_GL_JOURNAL = "224";
  private static final String TABLE_M_PRODUCTION = "325"; // BMP / MMP — ETP-4452
  private static final String TABLE_FIN_DOUBTFUL_DEBT = "30721072789F410E9606D2235CB2A226"; // DD / DDB
  private static final String TABLE_M_LANDED_COST = "082F967CDF7245EB9A150941F326C45C"; // LC / LDC
  private static final String TABLE_M_LC_COST = "55A984C314FD4C4FB5E7C32DE36BB07B"; // LCC
  private static final String TABLE_M_COST_ADJUSTMENT = "D022B92163074E5E82449C8E0B5AFDF6"; // CA / CAD
  private static final String TABLE_FIN_BANK_STATEMENT = "D4C23A17190649E7B78F55A05AF3438C"; // BS
  private static final String TABLE_FIN_PAYMENT = "D1A97202E832470285C9B1EB026D54E2"; // PIN/POT/APP/ARR
  private static final String TABLE_FIN_RECONCILIATION = "B1B7075C46934F0A9FD4C4D0F1457B42"; // R / REC

  // ── isAprmDisabledTable ────────────────────────────────────────────────────────

  @Test
  public void isAprmDisabledTableIsNullSafe() {
    assertFalse(AccountingDocumentTypeSupport.isAprmDisabledTable(null));
  }

  @Test
  public void isAprmDisabledTableRecognizesAllEtp4452Tables() {
    assertTrue(AccountingDocumentTypeSupport.isAprmDisabledTable(TABLE_M_PRODUCTION));
    assertTrue(AccountingDocumentTypeSupport.isAprmDisabledTable(TABLE_FIN_DOUBTFUL_DEBT));
    assertTrue(AccountingDocumentTypeSupport.isAprmDisabledTable(TABLE_M_LANDED_COST));
    assertTrue(AccountingDocumentTypeSupport.isAprmDisabledTable(TABLE_M_LC_COST));
    assertTrue(AccountingDocumentTypeSupport.isAprmDisabledTable(TABLE_M_COST_ADJUSTMENT));
  }

  @Test
  public void isAprmDisabledTableRecognizesStructurallyDisabledTables() {
    assertTrue(AccountingDocumentTypeSupport.isAprmDisabledTable(TABLE_FIN_BANK_STATEMENT));
    assertTrue(AccountingDocumentTypeSupport.isAprmDisabledTable(TABLE_FIN_PAYMENT));
    assertTrue(AccountingDocumentTypeSupport.isAprmDisabledTable(TABLE_FIN_RECONCILIATION));
  }

  @Test
  public void isAprmDisabledTableIsFalseForOrdinaryTable() {
    assertFalse(AccountingDocumentTypeSupport.isAprmDisabledTable(TABLE_C_INVOICE));
  }

  // ── isTableAccountingRelevant ──────────────────────────────────────────────────

  @Test
  public void isTableAccountingRelevantIsNullSafe() {
    assertFalse(AccountingDocumentTypeSupport.isTableAccountingRelevant(null, Collections.emptySet()));
  }

  @Test
  public void isTableAccountingRelevantRequiresActiveAccountingSchemaEntry() {
    Set<String> accounted = new HashSet<>(Collections.singletonList(TABLE_GL_JOURNAL));
    assertFalse(AccountingDocumentTypeSupport.isTableAccountingRelevant(TABLE_C_INVOICE, accounted));
  }

  @Test
  public void isTableAccountingRelevantExcludesAprmDisabledTableEvenWhenAccounted() {
    Set<String> accounted = new HashSet<>(Collections.singletonList(TABLE_M_PRODUCTION));
    assertFalse(AccountingDocumentTypeSupport.isTableAccountingRelevant(TABLE_M_PRODUCTION, accounted));
  }

  @Test
  public void isTableAccountingRelevantTrueWhenAccountedAndNotDisabled() {
    Set<String> accounted = new HashSet<>(Collections.singletonList(TABLE_C_INVOICE));
    assertTrue(AccountingDocumentTypeSupport.isTableAccountingRelevant(TABLE_C_INVOICE, accounted));
  }

  // ── isAccountingRelevant(docBaseTypeCode, accountedTableIds) ───────────────────

  @Test
  public void isAccountingRelevantIsFalseForNullCode() {
    assertFalse(AccountingDocumentTypeSupport.isAccountingRelevant(null, Collections.emptySet()));
  }

  @Test
  public void isAccountingRelevantIsFalseForUnmappedCode() {
    // DocBaseTypes with no C_DocType configured for any table in current Etendo/Etendo GO
    // modules (ARRP, OBCVAT_MS, CMA, PJI, PPR, WRE) resolve to null and are never relevant.
    Set<String> accounted = new HashSet<>(Arrays.asList(TABLE_C_INVOICE, TABLE_GL_JOURNAL));
    assertFalse(AccountingDocumentTypeSupport.isAccountingRelevant("PJI", accounted));
  }

  @Test
  public void isAccountingRelevantIsFalseForNonPostableOrderBaseTypes() {
    // Issue 3's own reported bug: SOO/POO/POR (C_Order) are never registered in
    // c_acctschema_table on any real tenant — orders don't post to accounting at all — so they
    // must be excluded here even though C_Order is a perfectly valid, mapped table.
    Set<String> accounted = new HashSet<>(Collections.singletonList(TABLE_C_INVOICE));
    assertFalse(AccountingDocumentTypeSupport.isAccountingRelevant("SOO", accounted));
    assertFalse(AccountingDocumentTypeSupport.isAccountingRelevant("POO", accounted));
    assertFalse(AccountingDocumentTypeSupport.isAccountingRelevant("POR", accounted));
  }

  @Test
  public void isAccountingRelevantIsTrueForActivelyAccountedInvoiceBaseTypes() {
    Set<String> accounted = new HashSet<>(Collections.singletonList(TABLE_C_INVOICE));
    assertTrue(AccountingDocumentTypeSupport.isAccountingRelevant("ARI", accounted));
    assertTrue(AccountingDocumentTypeSupport.isAccountingRelevant("API", accounted));
    assertTrue(AccountingDocumentTypeSupport.isAccountingRelevant("ARC", accounted));
    assertTrue(AccountingDocumentTypeSupport.isAccountingRelevant("APC", accounted));
  }

  @Test
  public void isAccountingRelevantIsTrueForGlJournal() {
    Set<String> accounted = new HashSet<>(Collections.singletonList(TABLE_GL_JOURNAL));
    assertTrue(AccountingDocumentTypeSupport.isAccountingRelevant("GLJ", accounted));
  }

  /**
   * The five ETP-4452 globally-excluded codes, in Calendar's own DocBaseType vocabulary
   * (MMP, DDB, LDC, LCC, CAD — not the same literal codes Not-Posted-Documents uses, BMP/DD/
   * LC/LCC/CA, but the same underlying tables), must stay excluded even when their table is
   * actively configured for accounting — no divergence between the two windows.
   */
  @Test
  public void isAccountingRelevantExcludesAllFiveEtp4452EquivalentBaseTypesEvenWhenAccounted() {
    Set<String> accounted = new HashSet<>(Arrays.asList(
        TABLE_M_PRODUCTION, TABLE_FIN_DOUBTFUL_DEBT, TABLE_M_LANDED_COST,
        TABLE_M_LC_COST, TABLE_M_COST_ADJUSTMENT));

    assertFalse(AccountingDocumentTypeSupport.isAccountingRelevant("MMP", accounted));
    assertFalse(AccountingDocumentTypeSupport.isAccountingRelevant("DDB", accounted));
    assertFalse(AccountingDocumentTypeSupport.isAccountingRelevant("LDC", accounted));
    assertFalse(AccountingDocumentTypeSupport.isAccountingRelevant("LCC", accounted));
    assertFalse(AccountingDocumentTypeSupport.isAccountingRelevant("CAD", accounted));
  }

  @Test
  public void isAccountingRelevantExcludesStructurallyDisabledBaseTypesEvenWhenAccounted() {
    Set<String> accounted = new HashSet<>(Arrays.asList(
        TABLE_FIN_BANK_STATEMENT, TABLE_FIN_PAYMENT, TABLE_FIN_RECONCILIATION));

    assertFalse(AccountingDocumentTypeSupport.isAccountingRelevant("BSF", accounted));
    assertFalse(AccountingDocumentTypeSupport.isAccountingRelevant("APP", accounted));
    assertFalse(AccountingDocumentTypeSupport.isAccountingRelevant("ARR", accounted));
    assertFalse(AccountingDocumentTypeSupport.isAccountingRelevant("REC", accounted));
  }

  @Test
  public void isAccountingRelevantIsFalseWhenBaseTypeTableIsNotActivelyAccounted() {
    // MIC (M_Internal_Consumption) mapped but not yet configured for accounting on this tenant.
    Set<String> accounted = new HashSet<>(Collections.singletonList(TABLE_C_INVOICE));
    assertFalse(AccountingDocumentTypeSupport.isAccountingRelevant("MIC", accounted));
  }

  // ── loadTablesWithActiveAccounting ─────────────────────────────────────────────

  @Test
  @SuppressWarnings("unchecked")
  public void loadTablesWithActiveAccountingReturnsDistinctActiveTableIds() {
    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      Session session = mock(Session.class);
      NativeQuery<Object> query = mock(NativeQuery.class);
      when(dal.getSession()).thenReturn(session);
      when(session.createNativeQuery(anyString())).thenReturn(query);
      when(query.list()).thenReturn(Arrays.asList(TABLE_C_INVOICE, TABLE_GL_JOURNAL));

      Set<String> result = AccountingDocumentTypeSupport.loadTablesWithActiveAccounting();

      assertEquals(2, result.size());
      assertTrue(result.contains(TABLE_C_INVOICE));
      assertTrue(result.contains(TABLE_GL_JOURNAL));
    }
  }
}
