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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.Collections;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;

/**
 * Unit tests for the accounting-dimension half of {@link AutoMatchSupport#buildRuleGroup} and
 * {@link AutoMatchSupport#putRuleDimensions} (ETP-4950).
 *
 * <p>Before ETP-4950 {@link MatchRuleEngine} loaded a rule's project / cost center / product and
 * {@code buildRuleGroup} dropped them on the floor, so the transaction Automatch generated out of a
 * rule never carried them. The producer must now emit the three wire keys in <b>both</b> places the
 * frontend and {@code ReconciliationHandler#createTransactionForRule} read from: the proposed
 * operation (what the suggestion preview shows) and the {@code createPayment} spec (what the apply
 * step consumes). Kept in a sibling class rather than churning the 1400-line
 * {@link AutoMatchSupportTest}.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class AutoMatchSupportRuleDimensionsTest {

  private static final String KEY_CREATE_PAYMENT = "createPayment";
  private static final String KEY_OPERATIONS = "operations";

  private static final String PROJECT_ID = "PJ-1";
  private static final String COSTCENTER_ID = "CC-1";
  private static final String PRODUCT_ID = "PR-1";

  /**
   * Releases the inline mock-maker references created during the test, so they do not accumulate
   * across the module's single test JVM.
   */
  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  // ── fixtures ───────────────────────────────────────────────────────────────

  /** A bank-statement line mock with the credit / debit amounts {@code buildRuleGroup} reads. */
  private static FIN_BankStatementLine line(String id, String credit, String debit) {
    FIN_BankStatementLine line = mock(FIN_BankStatementLine.class);
    lenient().when(line.getId()).thenReturn(id);
    lenient().when(line.getCramount()).thenReturn(new BigDecimal(credit));
    lenient().when(line.getDramount()).thenReturn(new BigDecimal(debit));
    lenient().when(line.getDescription()).thenReturn("BANK FEE 03/2026");
    lenient().when(line.getReferenceNo()).thenReturn("");
    lenient().when(line.getTransactionDate()).thenReturn(null);
    return line;
  }

  /** A rule carrying a GL item plus the three accounting dimensions (or nulls). */
  private static MatchRuleEngine.Rule rule(String glItemId, String projectId, String costCenterId,
      String productId) {
    return new MatchRuleEngine.Rule("R1", "Fee Rule", 10, MatchRuleEngine.COND_CONTAINS, "fee",
        new MatchRuleEngine.RuleOptions(glItemId, "BP-1", "TT-1", projectId, costCenterId,
            productId),
        0L);
  }

  private static void assertDimensions(JSONObject target, String project, String costcenter,
      String product) throws Exception {
    assertEquals(project, target.getString(AutoMatchSupport.KEY_PROJECT_ID));
    assertEquals(costcenter, target.getString(AutoMatchSupport.KEY_COSTCENTER_ID));
    assertEquals(product, target.getString(AutoMatchSupport.KEY_PRODUCT_ID));
  }

  // ── putRuleDimensions (direct) ─────────────────────────────────────────────

  /**
   * The three dimension ids are copied verbatim onto the target payload.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testPutRuleDimensionsCopiesEveryDimensionId() throws Exception {
    JSONObject target = new JSONObject();

    AutoMatchSupport.putRuleDimensions(target,
        rule("GL-1", PROJECT_ID, COSTCENTER_ID, PRODUCT_ID));

    assertDimensions(target, PROJECT_ID, COSTCENTER_ID, PRODUCT_ID);
  }

  /**
   * A rule with no dimensions emits empty strings, never a JSON null: the frontend and the apply
   * step both read these keys with {@code optString}, and a literal {@code "null"} would be taken
   * for an id.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testPutRuleDimensionsEmitsEmptyStringsForAbsentDimensions() throws Exception {
    JSONObject target = new JSONObject();

    AutoMatchSupport.putRuleDimensions(target, rule("GL-1", null, null, null));

    assertDimensions(target, "", "", "");
    assertTrue(target.has(AutoMatchSupport.KEY_PROJECT_ID));
    assertFalse(target.isNull(AutoMatchSupport.KEY_PROJECT_ID));
    assertFalse(target.isNull(AutoMatchSupport.KEY_COSTCENTER_ID));
    assertFalse(target.isNull(AutoMatchSupport.KEY_PRODUCT_ID));
  }

  /**
   * A blank (whitespace-only) dimension id is normalised to an empty string rather than being
   * carried through as a would-be id.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testPutRuleDimensionsNormalisesBlankDimensionIds() throws Exception {
    JSONObject target = new JSONObject();

    AutoMatchSupport.putRuleDimensions(target, rule("GL-1", "   ", "", PRODUCT_ID));

    assertDimensions(target, "", "", PRODUCT_ID);
  }

  /**
   * A partially configured rule keeps the dimensions it declares and blanks only the missing ones.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testPutRuleDimensionsHandlesPartiallyConfiguredRules() throws Exception {
    JSONObject target = new JSONObject();

    AutoMatchSupport.putRuleDimensions(target, rule("GL-1", null, COSTCENTER_ID, null));

    assertDimensions(target, "", COSTCENTER_ID, "");
  }

  // ── buildRuleGroup ────────────────────────────────────────────────────────

  /**
   * REGRESSION (ETP-4950): a rule with project / cost center / product must surface those ids in
   * BOTH the proposed operation and the {@code createPayment} spec — the preview and the apply step
   * read different objects, so emitting them in only one place still loses the dimensions.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testBuildRuleGroupEmitsDimensionsInProposedOpAndCreatePaymentSpec() throws Exception {
    JSONObject group = AutoMatchSupport.buildRuleGroup(line("L1", "0.00", "12.50"),
        rule("GL-1", PROJECT_ID, COSTCENTER_ID, PRODUCT_ID), Collections.emptyList());

    assertTrue(group.getBoolean("isNew"));
    assertEquals(1, group.getJSONArray(KEY_OPERATIONS).length());
    assertDimensions(group.getJSONArray(KEY_OPERATIONS).getJSONObject(0),
        PROJECT_ID, COSTCENTER_ID, PRODUCT_ID);
    assertDimensions(group.getJSONObject(KEY_CREATE_PAYMENT),
        PROJECT_ID, COSTCENTER_ID, PRODUCT_ID);
  }

  /**
   * The dimension keys are always present (as empty strings) even for a rule that declares none, so
   * the consumer never has to branch on a missing key.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testBuildRuleGroupEmitsEmptyDimensionsForAnUndimensionedRule() throws Exception {
    JSONObject group = AutoMatchSupport.buildRuleGroup(line("L2", "0.00", "12.50"),
        rule("GL-1", null, null, null), Collections.emptyList());

    assertDimensions(group.getJSONArray(KEY_OPERATIONS).getJSONObject(0), "", "", "");
    assertDimensions(group.getJSONObject(KEY_CREATE_PAYMENT), "", "", "");
  }

  /**
   * The dimensions ride alongside the pre-existing GL item / business partner / transaction type
   * keys of the {@code createPayment} spec — adding them must not displace anything.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testBuildRuleGroupKeepsTheExistingCreatePaymentKeys() throws Exception {
    JSONObject cp = AutoMatchSupport.buildRuleGroup(line("L3", "25.00", "0.00"),
            rule("GL-1", PROJECT_ID, COSTCENTER_ID, PRODUCT_ID), Collections.emptyList())
        .getJSONObject(KEY_CREATE_PAYMENT);

    assertEquals("R1", cp.getString("ruleId"));
    assertEquals("GL-1", cp.getString("glItemId"));
    assertEquals("BP-1", cp.getString("bpartnerId"));
    assertEquals("TT-1", cp.getString("transactionTypeId"));
    assertEquals(0, new BigDecimal("25.00").compareTo(new BigDecimal(cp.getString("amount"))));
    assertDimensions(cp, PROJECT_ID, COSTCENTER_ID, PRODUCT_ID);
  }

  /**
   * A rule without a GL item produces no proposed operation and no {@code createPayment} spec, so
   * there is nothing to carry dimensions into — the dimension keys must not appear at group level.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testBuildRuleGroupWithoutGlItemEmitsNoDimensionCarrier() throws Exception {
    JSONObject group = AutoMatchSupport.buildRuleGroup(line("L4", "0.00", "12.50"),
        rule(null, PROJECT_ID, COSTCENTER_ID, PRODUCT_ID), Collections.emptyList());

    assertFalse(group.getBoolean("isNew"));
    assertEquals(0, group.getJSONArray(KEY_OPERATIONS).length());
    assertFalse(group.has(KEY_CREATE_PAYMENT));
    assertFalse(group.has(AutoMatchSupport.KEY_PROJECT_ID));
    assertFalse(group.has(AutoMatchSupport.KEY_COSTCENTER_ID));
    assertFalse(group.has(AutoMatchSupport.KEY_PRODUCT_ID));
  }

  /**
   * The wire keys the producer emits are exactly the ones the consumer
   * ({@code ReconciliationHandler#applyRuleDimensions}) reads — a rename on one side alone would
   * silently drop the dimensions again.
   */
  @Test
  public void testWireKeyNamesAreTheContractedOnes() {
    assertEquals("projectId", AutoMatchSupport.KEY_PROJECT_ID);
    assertEquals("costcenterId", AutoMatchSupport.KEY_COSTCENTER_ID);
    assertEquals("productId", AutoMatchSupport.KEY_PRODUCT_ID);
  }
}
