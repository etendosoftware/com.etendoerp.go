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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;

import java.util.HashSet;
import java.util.Set;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Access gate of the three NEO report handlers (ETP-5335).
 *
 * <p>Report specs are of type {@code R} with no linked {@code AD_Process} and no {@code AD_TAB_ID}
 * on any entity, so they reach {@code NeoAccessHelper.hasReportSpecAccess} with nothing to compare
 * against and the shared gate answers permissively by design (ETP-4596). Each report handler
 * therefore owns its own rule, and these tests assert that rule where it is enforced.</p>
 *
 * <p>{@link NeoAccessHelper} is mocked statically: the grants it reads live in
 * {@code AD_Window_Access} / {@code AD_Process_Access} rows, and the point here is not how that
 * lookup works (covered by {@code NeoAccessHelperTest}) but that each handler asks the right
 * question and refuses before doing anything else.</p>
 */
class ReportHandlerAccessGateTest {

  /**
   * The grant ids each handler declares. Duplicated from the handlers' own private constants on
   * purpose: a test that read the constant back through reflection would keep passing if somebody
   * repointed it, which is exactly the change that must not go unnoticed — each of these ids was
   * confirmed against a real {@code AD_Menu} foreign key (see the javadoc on each constant).
   */
  private static final String TAX_REPORT_PROCESS_ID = "8C1331B9EC14CED7E040007F010119A0";
  private static final String INVENTORY_STOCK_REPORT_WINDOW_ID = "6346B88619F948F9A42224BDB0B239FA";
  private static final String AGING_RECEIVABLE_PROCESS_ID = "0D37A9F6109549DEB058373EF2DAEB6A";
  private static final String AGING_PAYABLE_PROCESS_ID = "EB4C4053F3B94A17A08D1DD7E89CEB7E";

  private MockedStatic<NeoAccessHelper> accessMock;

  @BeforeEach
  void setUp() {
    accessMock = mockStatic(NeoAccessHelper.class);
  }

  @AfterEach
  void tearDown() {
    accessMock.close();
  }

  private static NeoContext context(String method) {
    return NeoContext.builder().specName("report").entityName("report").httpMethod(method).build();
  }

  // ── tax-report ───────────────────────────────────────────────────────────

  @Nested
  @DisplayName("TaxReportHandler")
  class TaxReport {

    private final TaxReportHandler handler = new TaxReportHandler();

    @Test
    @DisplayName("declares access as the grant on its own AD_Process")
    void declaresProcessGrant() {
      accessMock.when(() -> NeoAccessHelper.hasProcessAccess(TAX_REPORT_PROCESS_ID))
          .thenReturn(true);
      assertTrue(handler.isAccessibleForCurrentRole());

      accessMock.when(() -> NeoAccessHelper.hasProcessAccess(TAX_REPORT_PROCESS_ID))
          .thenReturn(false);
      assertFalse(handler.isAccessibleForCurrentRole());
    }

    /**
     * The audited defect: a role with no grant at all read invoices, amounts, VAT rates and every
     * contact's tax id. POST is the path that returns them.
     */
    @Test
    @DisplayName("POST without the grant is refused with 403")
    void postWithoutGrantIsRefused() {
      accessMock.when(() -> NeoAccessHelper.hasProcessAccess(anyString())).thenReturn(false);

      NeoResponse response = handler.handle(context("POST"));

      assertEquals(403, response.getHttpStatus());
    }

    /**
     * The gate runs BEFORE the GET/POST branch, so the descriptor is not a way around it: the GET
     * answer names the report and describes its shape to the same audience the POST data was
     * being leaked to.
     */
    @Test
    @DisplayName("GET without the grant is refused before the description is built")
    void getWithoutGrantDoesNotLeakTheDescription() throws Exception {
      accessMock.when(() -> NeoAccessHelper.hasProcessAccess(anyString())).thenReturn(false);

      NeoResponse response = handler.handle(context("GET"));

      assertEquals(403, response.getHttpStatus());
      JSONObject body = response.getBody();
      assertFalse(body != null && body.toString().contains("Multidimensional"),
          "A refused GET must not return the report descriptor");
    }

    @Test
    @DisplayName("GET with the grant still returns the description")
    void getWithGrantReturnsTheDescription() throws Exception {
      accessMock.when(() -> NeoAccessHelper.hasProcessAccess(TAX_REPORT_PROCESS_ID))
          .thenReturn(true);

      NeoResponse response = handler.handle(context("GET"));

      assertEquals(200, response.getHttpStatus());
      assertEquals("Tax Report", response.getBody().getString("name"));
    }

    /**
     * {@code handle} must route through the overridable declaration rather than repeat the
     * condition: the declaration is what {@code neo_discover} and the tool publication ask, and
     * two copies of the rule are two places for them to disagree.
     */
    @Test
    @DisplayName("handle() asks isAccessibleForCurrentRole(), not a second copy of the rule")
    void handleDelegatesToTheDeclaration() {
      accessMock.when(() -> NeoAccessHelper.hasProcessAccess(anyString())).thenReturn(true);
      TaxReportHandler spied = spy(new TaxReportHandler());
      doReturn(false).when(spied).isAccessibleForCurrentRole();

      NeoResponse response = spied.handle(context("GET"));

      assertEquals(403, response.getHttpStatus(),
          "The grant lookup says yes and the declaration says no: handle() must follow the "
              + "declaration");
    }
  }

  // ── inventory-stock-report ───────────────────────────────────────────────

  @Nested
  @DisplayName("InventoryStockReportHandler")
  class InventoryStockReport {

    private final InventoryStockReportHandler handler = new InventoryStockReportHandler();

    @Test
    @DisplayName("declares access as the grant on its permission-anchor window")
    void declaresWindowGrant() {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(INVENTORY_STOCK_REPORT_WINDOW_ID))
          .thenReturn(true);
      assertTrue(handler.isAccessibleForCurrentRole());

      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(INVENTORY_STOCK_REPORT_WINDOW_ID))
          .thenReturn(false);
      assertFalse(handler.isAccessibleForCurrentRole());
    }

    @Test
    @DisplayName("POST without the grant is refused with 403")
    void postWithoutGrantIsRefused() {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(false);

      assertEquals(403, handler.handle(context("POST")).getHttpStatus());
    }

    /**
     * The gate precedes the method check, so an ungranted role is told it may not use the report
     * rather than which verbs the report serves.
     */
    @Test
    @DisplayName("the gate runs before the method check: GET without the grant is 403, not 405")
    void gatePrecedesTheMethodCheck() {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(false);

      assertEquals(403, handler.handle(context("GET")).getHttpStatus());

      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);

      assertEquals(405, handler.handle(context("GET")).getHttpStatus(),
          "With the grant, GET reaches the method check — proving the 403 above came from the gate");
    }

    /**
     * Regression guard for the defect introduced mid-review on this very handler: its inline check
     * was replaced by the call to the new method before the override existed, so it inherited the
     * permissive default and the report was open to every role for a deploy.
     */
    @Test
    @DisplayName("handle() asks isAccessibleForCurrentRole(), not a second copy of the rule")
    void handleDelegatesToTheDeclaration() {
      accessMock.when(() -> NeoAccessHelper.hasWindowAccess(anyString())).thenReturn(true);
      InventoryStockReportHandler spied = spy(new InventoryStockReportHandler());
      doReturn(false).when(spied).isAccessibleForCurrentRole();

      assertEquals(403, spied.handle(context("POST")).getHttpStatus());
    }
  }

  // ── aging-report ─────────────────────────────────────────────────────────

  @Nested
  @DisplayName("AgingReportHandler")
  class AgingReport {

    private final AgingReportHandler handler = new AgingReportHandler();

    /**
     * The declaration is deliberately the UNION of the two sides. It answers the catalogue's
     * question — may this role use the report at all — and a role granted only one side must still
     * be offered it; the side-specific grant is enforced in {@code handle}, where the requested
     * side is known. Narrowing this to the receivables grant alone would silently take the report
     * away from a payables-only role.
     */
    @Test
    @DisplayName("the receivables grant alone is enough")
    void receivablesOnlyIsEnough() {
      accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(AGING_RECEIVABLE_PROCESS_ID))
          .thenReturn(true);
      accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(AGING_PAYABLE_PROCESS_ID))
          .thenReturn(false);

      assertTrue(handler.isAccessibleForCurrentRole());
    }

    @Test
    @DisplayName("the payables grant alone is enough")
    void payablesOnlyIsEnough() {
      accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(AGING_RECEIVABLE_PROCESS_ID))
          .thenReturn(false);
      accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(AGING_PAYABLE_PROCESS_ID))
          .thenReturn(true);

      assertTrue(handler.isAccessibleForCurrentRole(),
          "A payables-only role must still be offered the report — this is what a 'simplification' "
              + "to the receivables grant alone would break");
    }

    @Test
    @DisplayName("neither grant refuses")
    void neitherGrantRefuses() {
      accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(anyString())).thenReturn(false);

      assertFalse(handler.isAccessibleForCurrentRole());
    }

    /**
     * Both sides must actually be consulted. Asserting on the ids asked, rather than only on the
     * boolean, is what fails if the union is collapsed to a single grant — a one-sided check still
     * returns false when neither side is granted, so the boolean alone would not notice.
     */
    @Test
    @DisplayName("both sides are consulted, by their confirmed process ids")
    void bothSidesAreConsulted() {
      Set<String> asked = new HashSet<>();
      accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(anyString()))
          .thenAnswer(invocation -> {
            asked.add(invocation.getArgument(0));
            return false;
          });

      handler.isAccessibleForCurrentRole();

      assertEquals(Set.of(AGING_RECEIVABLE_PROCESS_ID, AGING_PAYABLE_PROCESS_ID), asked,
          "The declaration must ask both the receivables and the payables grant");
      assertNotEquals(AGING_RECEIVABLE_PROCESS_ID, AGING_PAYABLE_PROCESS_ID);
    }
  }

  // ── aging-payable (ETP-5483) ────────────────────────────────────────────

  @Nested
  @DisplayName("AgingPayableReportHandler")
  class AgingPayableReport {

    private final AgingPayableReportHandler handler = new AgingPayableReportHandler();

    /**
     * Unlike {@link AgingReportHandler}, this sibling serves ONLY the payables side, so its
     * declaration must be narrow — the receivables grant alone must not be enough.
     */
    @Test
    @DisplayName("only the payables grant is enough; the receivables grant alone is not")
    void onlyPayablesGrantIsEnough() {
      accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(AGING_PAYABLE_PROCESS_ID))
          .thenReturn(true);
      accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(AGING_RECEIVABLE_PROCESS_ID))
          .thenReturn(false);
      assertTrue(handler.isAccessibleForCurrentRole());

      accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(AGING_PAYABLE_PROCESS_ID))
          .thenReturn(false);
      accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(AGING_RECEIVABLE_PROCESS_ID))
          .thenReturn(true);
      assertFalse(handler.isAccessibleForCurrentRole(),
          "A receivables-only role must NOT be offered the payables-only tool");
    }

    @Test
    @DisplayName("POST without the payables grant is refused with 403")
    void postWithoutGrantIsRefused() {
      accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(anyString())).thenReturn(false);

      NeoResponse response = handler.handle(context("POST"));

      assertEquals(403, response.getHttpStatus());
    }
  }
}
