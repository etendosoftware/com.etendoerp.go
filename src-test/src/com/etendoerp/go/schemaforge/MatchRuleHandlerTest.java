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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.go.schemaforge.data.MatchRule;

/**
 * Mockito-driven unit tests for {@link MatchRuleHandler} — the validation pre-hook for
 * the Bank Reconciliation matching-rules catalog (T5 / ETP-4099).
 *
 * <p>Strategy:
 * <ul>
 *   <li>{@code validateContent} / {@code validateRegex} are pure (no DAL) and are exercised
 *       directly over in-memory JSON bodies.</li>
 *   <li>{@code priorityExists} / {@code validatePriorityScope} / {@code resolveScopeAccount}
 *       hit {@code OBDal} statics, mocked per-test with {@link MockedStatic}.</li>
 *   <li>{@code handle()} routing is covered with a {@link NeoContext} mock; the admin-mode
 *       seams are stubbed on a spy so {@code OBContext} is never touched.</li>
 * </ul>
 *
 * <p>JUnit 4 + Silent runner mirror the sibling handler tests; {@code clearInlineMocks()}
 * after each test keeps the single test JVM's heap flat.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class MatchRuleHandlerTest {

  private static final int BAD_REQUEST = HttpServletResponse.SC_BAD_REQUEST;   // 400
  private static final int CONFLICT = HttpServletResponse.SC_CONFLICT;         // 409

  private MatchRuleHandler handler;

  @Before
  public void setUp() {
    handler = new MatchRuleHandler();
  }

  /**
   * Releases the inline mock-maker references created during the test. Without this they
   * accumulate across the module's single test JVM and push the fork past its heap cap.
   */
  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /** Builds a JSON body from alternating key/value pairs. */
  private static JSONObject body(Object... kv) throws Exception {
    JSONObject o = new JSONObject();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      o.put((String) kv[i], kv[i + 1]);
    }
    return o;
  }

  private static void assertStatus(int expected, NeoResponse response) {
    assertNotNull("expected a rejection response, got null (accepted)", response);
    assertEquals(expected, response.getHttpStatus());
  }

  // ── validateContent: required / length / enum ────────────────────────────────

  @Test
  public void rejectsBlankName() throws Exception {
    assertStatus(BAD_REQUEST, handler.validateContent(
        body("name", "  ", "textCondition", "C", "textPattern", "X")));
  }

  @Test
  public void rejectsNameTooLong() throws Exception {
    String longName = repeat("a", 61);
    assertStatus(BAD_REQUEST, handler.validateContent(
        body("name", longName, "textCondition", "C", "textPattern", "X")));
  }

  @Test
  public void rejectsUnknownTextCondition() throws Exception {
    assertStatus(BAD_REQUEST, handler.validateContent(
        body("name", "n", "textCondition", "X", "textPattern", "p")));
  }

  @Test
  public void rejectsBlankPattern() throws Exception {
    assertStatus(BAD_REQUEST, handler.validateContent(
        body("name", "n", "textCondition", "C", "textPattern", "")));
  }

  @Test
  public void rejectsPatternTooLong() throws Exception {
    assertStatus(BAD_REQUEST, handler.validateContent(
        body("name", "n", "textCondition", "C", "textPattern", repeat("p", 256))));
  }

  @Test
  public void rejectsInvalidTransactionType() throws Exception {
    assertStatus(BAD_REQUEST, handler.validateContent(
        body("name", "n", "textCondition", "C", "textPattern", "p", "transactionType", "Z")));
  }

  @Test
  public void acceptsContainsCondition() throws Exception {
    assertNull(handler.validateContent(
        body("name", "n", "textCondition", "C", "textPattern", "p", "transactionType", "B")));
  }

  @Test
  public void acceptsStartsWithCondition() throws Exception {
    assertNull(handler.validateContent(
        body("name", "n", "textCondition", "S", "textPattern", "REF-")));
  }

  // ── validateContent: regex condition (R) ─────────────────────────────────────

  @Test
  public void acceptsSafeRegexCondition() throws Exception {
    assertNull(handler.validateContent(
        body("name", "n", "textCondition", "R", "textPattern", "INV-[0-9]+")));
  }

  @Test
  public void rejectsRegexWithInvalidSyntax() throws Exception {
    // Unclosed character class — PatternSyntaxException → HTTP 400.
    assertStatus(BAD_REQUEST, handler.validateContent(
        body("name", "n", "textCondition", "R", "textPattern", "[")));
  }

  @Test
  public void rejectsCatastrophicRegex() throws Exception {
    // Triple-nested quantifier: exponential backtracking against the adversarial probe
    // (Java 17 optimizes the single-nested "(a+)+$", so a deeper nest is needed) → 200ms
    // cap → HTTP 400.
    assertStatus(BAD_REQUEST, handler.validateContent(
        body("name", "n", "textCondition", "R", "textPattern", "((a+)+)+$")));
  }

  // ── validateRegex (direct) ───────────────────────────────────────────────────

  @Test
  public void validateRegexReturnsNullForSafePattern() {
    assertNull(handler.validateRegex("abc.*\\d{2}"));
  }

  @Test
  public void validateRegexFlagsInvalidSyntax() {
    String error = handler.validateRegex("(unclosed");
    assertNotNull(error);
    assertTrue(error.toLowerCase().contains("invalid"));
  }

  @Test
  public void validateRegexFlagsCatastrophicBacktracking() {
    // Triple-nested quantifier reliably exceeds the 200ms cap on Java 17 (the
    // single-nested form is optimized away by the engine).
    String error = handler.validateRegex("((a+)+)+$");
    assertNotNull(error);
    assertTrue(error.toLowerCase().contains("complex"));
  }

  // ── priority uniqueness (OBDal) ──────────────────────────────────────────────

  @Test
  public void priorityFreeReturnsNoConflict() throws Exception {
    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<MatchRule> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(MatchRule.class)).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.emptyList());

      assertNull(handler.validatePriorityScope(
          body("priority", 10, "financialAccount", "ACC-1"), null));
    }
  }

  @Test
  public void duplicatePriorityReturnsConflict() throws Exception {
    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
      when(dal.get(FIN_FinancialAccount.class, "ACC-1")).thenReturn(account);
      @SuppressWarnings("unchecked")
      OBCriteria<MatchRule> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(MatchRule.class)).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(mock(MatchRule.class)));

      assertStatus(CONFLICT, handler.validatePriorityScope(
          body("priority", 10, "financialAccount", "ACC-1"), null));
    }
  }

  @Test
  public void priorityExistsScopesToAllAccountsWhenNoAccount() throws Exception {
    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<MatchRule> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(MatchRule.class)).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.emptyList());

      // null account → "all accounts" scope; no collision when the list is empty.
      assertTrue(!handler.priorityExists(10L, null, null));
      verify(dal).createCriteria(MatchRule.class);
    }
  }

  // ── resolveScopeAccount (OBDal) ──────────────────────────────────────────────

  @Test
  public void resolveScopeAccountReadsFromBody() throws Exception {
    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
      when(dal.get(FIN_FinancialAccount.class, "ACC-9")).thenReturn(account);

      assertEquals(account, handler.resolveScopeAccount(body("financialAccount", "ACC-9"), null));
    }
  }

  @Test
  public void resolveScopeAccountFallsBackToPersistedRuleOnPatch() throws Exception {
    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
      MatchRule rule = mock(MatchRule.class);
      when(rule.getFinancialAccount()).thenReturn(account);
      when(dal.get(MatchRule.class, "RULE-1")).thenReturn(rule);

      // Body omits financialAccount → scope comes from the persisted rule.
      assertEquals(account, handler.resolveScopeAccount(new JSONObject(), "RULE-1"));
    }
  }

  @Test
  public void resolveScopeAccountReturnsNullForAllAccounts() throws Exception {
    // Body explicitly sets an empty financialAccount → "all accounts" (null), no DAL get.
    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertNull(handler.resolveScopeAccount(body("financialAccount", ""), null));
    }
  }

  // ── handle() routing ─────────────────────────────────────────────────────────

  @Test
  public void handleIgnoresOtherSpecs() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("financial-account");
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleLetsReadMethodsThrough() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("match-rule");
    when(ctx.getHttpMethod()).thenReturn("GET");
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleLetsNullBodyThrough() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("match-rule");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(null);
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleRejectsInvalidContentOnPost() throws Exception {
    MatchRuleHandler spyHandler = spy(new MatchRuleHandler());
    doNothing().when(spyHandler).enterAdminMode();
    doNothing().when(spyHandler).exitAdminMode();

    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("match-rule");
    when(ctx.getHttpMethod()).thenReturn("POST");
    // Blank name fails in validateContent before the priority/DAL check is reached.
    when(ctx.getRequestBody()).thenReturn(body("name", "", "textCondition", "C", "textPattern", "p"));

    assertStatus(BAD_REQUEST, spyHandler.handle(ctx));
    verify(spyHandler).enterAdminMode();
    verify(spyHandler).exitAdminMode();
  }

  @Test
  public void handleAcceptsValidCreate() throws Exception {
    MatchRuleHandler spyHandler = spy(new MatchRuleHandler());
    doNothing().when(spyHandler).enterAdminMode();
    doNothing().when(spyHandler).exitAdminMode();

    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("match-rule");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRecordId()).thenReturn(null);
    when(ctx.getRequestBody()).thenReturn(
        body("name", "Bank fee", "textCondition", "C", "textPattern", "COMM", "priority", 10));

    try (MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<MatchRule> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(MatchRule.class)).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.emptyList());

      // null → no rejection; generic CRUD proceeds.
      assertNull(spyHandler.handle(ctx));
    }
  }

  @Test
  public void handleSkipsContentValidationForPartialPatch() throws Exception {
    // A PATCH carrying only `active` (no content fields, no priority) must pass straight
    // through without content validation or a DAL hit.
    MatchRuleHandler spyHandler = spy(new MatchRuleHandler());
    doNothing().when(spyHandler).enterAdminMode();
    doNothing().when(spyHandler).exitAdminMode();

    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("match-rule");
    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getRecordId()).thenReturn("RULE-1");
    when(ctx.getRequestBody()).thenReturn(body("active", false));

    assertNull(spyHandler.handle(ctx));
  }

  private static String repeat(String s, int n) {
    StringBuilder sb = new StringBuilder(s.length() * n);
    for (int i = 0; i < n; i++) {
      sb.append(s);
    }
    return sb.toString();
  }
}
