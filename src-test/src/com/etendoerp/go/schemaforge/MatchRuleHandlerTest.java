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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;


/**
 * Mockito-driven unit tests for {@link MatchRuleHandler} — the validation pre-hook for
 * the Bank Reconciliation matching-rules catalog (T5 / ETP-4099).
 *
 * <p>Strategy:
 * <ul>
 *   <li>{@code validateContent} / {@code validateRegex} are pure (no DAL) and are exercised
 *       directly over in-memory JSON bodies.</li>
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
  private static final int SERVER_ERROR = HttpServletResponse.SC_INTERNAL_SERVER_ERROR; // 500

  private static final String SPEC = "match-rule";
  private static final String FAT = AccountingDimensionsSupport.DOCBASETYPE_FAT;

  /** Wire field names of the three accounting dimensions a rule can carry. */
  private static final String F_PROJECT = "project";
  private static final String F_COST_CENTER = "costCenter";
  private static final String F_PRODUCT = "product";

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

  /**
   * A blank name is rejected with HTTP 400.
   *
   * @throws Exception if building the JSON body fails
   */
  @Test
  public void testRejectsBlankName() throws Exception {
    assertStatus(BAD_REQUEST, handler.validateContent(
        body("name", "  ", "textCondition", "C", "textPattern", "X")));
  }

  /**
   * A name longer than the allowed maximum is rejected with HTTP 400.
   *
   * @throws Exception if building the JSON body fails
   */
  @Test
  public void testRejectsNameTooLong() throws Exception {
    String longName = repeat("a", 61);
    assertStatus(BAD_REQUEST, handler.validateContent(
        body("name", longName, "textCondition", "C", "textPattern", "X")));
  }

  /**
   * A text condition outside the allowed set (C/S/R) is rejected with HTTP 400.
   *
   * @throws Exception if building the JSON body fails
   */
  @Test
  public void testRejectsUnknownTextCondition() throws Exception {
    assertStatus(BAD_REQUEST, handler.validateContent(
        body("name", "n", "textCondition", "X", "textPattern", "p")));
  }

  /**
   * A blank text pattern is rejected with HTTP 400.
   *
   * @throws Exception if building the JSON body fails
   */
  @Test
  public void testRejectsBlankPattern() throws Exception {
    assertStatus(BAD_REQUEST, handler.validateContent(
        body("name", "n", "textCondition", "C", "textPattern", "")));
  }

  /**
   * A text pattern longer than the allowed maximum is rejected with HTTP 400.
   *
   * @throws Exception if building the JSON body fails
   */
  @Test
  public void testRejectsPatternTooLong() throws Exception {
    assertStatus(BAD_REQUEST, handler.validateContent(
        body("name", "n", "textCondition", "C", "textPattern", repeat("p", 256))));
  }

  /**
   * A valid Contains rule with a non-blank accounting concept is accepted (null).
   *
   * @throws Exception if building the JSON body fails
   */
  @Test
  public void testAcceptsContainsCondition() throws Exception {
    assertNull(handler.validateContent(
        body("name", "n", "textCondition", "C", "textPattern", "p", "transactionType", "B",
            "accountingConcept", "GL-001")));
  }

  /**
   * A valid Starts-with rule with a non-blank accounting concept is accepted (null).
   *
   * @throws Exception if building the JSON body fails
   */
  @Test
  public void testAcceptsStartsWithCondition() throws Exception {
    assertNull(handler.validateContent(
        body("name", "n", "textCondition", "S", "textPattern", "REF-",
            "accountingConcept", "GL-001")));
  }

  /**
   * A JSON null transaction type must NOT be treated as an invalid value — the optional field is
   * simply unset and the rule is accepted (null).
   *
   * @throws Exception if building the JSON body fails
   */
  @Test
  public void testAcceptsJsonNullTransactionType() throws Exception {
    // Editing a rule with no transaction type sends {"transactionType": null}. Jettison's
    // optString returns the literal "null" for a JSON null, which must NOT be treated as an
    // invalid value — the optional field is simply unset.
    JSONObject b = body("name", "n", "textCondition", "C", "textPattern", "p");
    b.put("accountingConcept", "GL-001");
    b.put("transactionType", JSONObject.NULL);
    assertNull(handler.validateContent(b));
  }

  // ── validateContent: regex condition (R) ─────────────────────────────────────

  /**
   * A safe Regex rule with a non-blank accounting concept is accepted (null).
   *
   * @throws Exception if building the JSON body fails
   */
  @Test
  public void testAcceptsSafeRegexCondition() throws Exception {
    assertNull(handler.validateContent(
        body("name", "n", "textCondition", "R", "textPattern", "INV-[0-9]+",
            "accountingConcept", "GL-001")));
  }

  /**
   * A regex with invalid syntax (unclosed character class) is rejected with HTTP 400.
   *
   * @throws Exception if building the JSON body fails
   */
  @Test
  public void testRejectsRegexWithInvalidSyntax() throws Exception {
    // Unclosed character class — PatternSyntaxException → HTTP 400.
    assertStatus(BAD_REQUEST, handler.validateContent(
        body("name", "n", "textCondition", "R", "textPattern", "[")));
  }

  /**
   * A catastrophic-backtracking regex is rejected with HTTP 400 once the 200ms cap is hit.
   *
   * @throws Exception if building the JSON body fails
   */
  @Test
  public void testRejectsCatastrophicRegex() throws Exception {
    // Triple-nested quantifier: exponential backtracking against the adversarial probe
    // (Java 17 optimizes the single-nested "(a+)+$", so a deeper nest is needed) → 200ms
    // cap → HTTP 400.
    assertStatus(BAD_REQUEST, handler.validateContent(
        body("name", "n", "textCondition", "R", "textPattern", "((a+)+)+$")));
  }

  // ── validateRegex (direct) ───────────────────────────────────────────────────

  /** A safe regex pattern validates to null (no error). */
  @Test
  public void testValidateRegexReturnsNullForSafePattern() {
    assertNull(handler.validateRegex("abc.*\\d{2}"));
  }

  /** An invalid regex syntax produces an error message mentioning "invalid". */
  @Test
  public void testValidateRegexFlagsInvalidSyntax() {
    String error = handler.validateRegex("(unclosed");
    assertNotNull(error);
    assertTrue(error.toLowerCase().contains("invalid"));
  }

  /** A catastrophic-backtracking regex produces an error message mentioning "complex". */
  @Test
  public void testValidateRegexFlagsCatastrophicBacktracking() {
    // Triple-nested quantifier reliably exceeds the 200ms cap on Java 17 (the
    // single-nested form is optimized away by the engine).
    String error = handler.validateRegex("((a+)+)+$");
    assertNotNull(error);
    assertTrue(error.toLowerCase().contains("complex"));
  }

  // ── handle() routing ─────────────────────────────────────────────────────────

  /** A request for a different spec is ignored (null passthrough). */
  @Test
  public void testHandleIgnoresOtherSpecs() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("financial-account");
    assertNull(handler.handle(ctx));
  }

  /** A read method (GET) passes straight through to the generic CRUD (null). */
  @Test
  public void testHandleLetsReadMethodsThrough() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("match-rule");
    when(ctx.getHttpMethod()).thenReturn("GET");
    assertNull(handler.handle(ctx));
  }

  /** A write with a null body passes through without validation (null). */
  @Test
  public void testHandleLetsNullBodyThrough() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("match-rule");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(null);
    assertNull(handler.handle(ctx));
  }

  /**
   * A POST with invalid content is rejected with HTTP 400, and the admin-mode seams run.
   *
   * @throws Exception if building the JSON body fails
   */
  @Test
  public void testHandleRejectsInvalidContentOnPost() throws Exception {
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

  /**
   * A valid create (POST) with a non-blank accounting concept passes to the generic CRUD (null).
   *
   * @throws Exception if building the JSON body fails
   */
  @Test
  public void testHandleAcceptsValidCreate() throws Exception {
    MatchRuleHandler spyHandler = spy(new MatchRuleHandler());
    doNothing().when(spyHandler).enterAdminMode();
    doNothing().when(spyHandler).exitAdminMode();

    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("match-rule");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRecordId()).thenReturn(null);
    when(ctx.getRequestBody()).thenReturn(
        body("name", "Bank fee", "textCondition", "C", "textPattern", "COMM", "priority", 10,
            "accountingConcept", "GL-001"));

    // Valid content and no uniqueness checks (priority may repeat) → null, so the
    // generic CRUD proceeds. handle() no longer touches OBDal.
    assertNull(spyHandler.handle(ctx));
  }

  /**
   * A PATCH carrying only {@code active} skips content validation and passes through (null).
   *
   * @throws Exception if building the JSON body fails
   */
  @Test
  public void testHandleSkipsContentValidationForPartialPatch() throws Exception {
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

  // ── accountingConcept validation (T7) ────────────────────────────────────────

  /**
   * A POST body that omits {@code accountingConcept} entirely must be rejected with 400 —
   * the GL item is mandatory for the automatch payment-creation step.
   *
   * @throws Exception if building the JSON body fails
   */
  @Test
  public void testRejectsPostWithoutAccountingConcept() throws Exception {
    assertStatus(BAD_REQUEST, handler.validateContent(
        body("name", "Fee Rule", "textCondition", "C", "textPattern", "fee")));
  }

  /**
   * A POST body that provides a blank (whitespace-only) {@code accountingConcept} must also be
   * rejected with 400.
   *
   * @throws Exception if building the JSON body fails
   */
  @Test
  public void testRejectsPostWithBlankAccountingConcept() throws Exception {
    assertStatus(BAD_REQUEST, handler.validateContent(
        body("name", "Fee Rule", "textCondition", "C", "textPattern", "fee",
            "accountingConcept", "   ")));
  }

  /**
   * A PATCH body that does NOT carry any content fields (no name / textCondition / textPattern)
   * must bypass the full content validation, so the absence of {@code accountingConcept} in
   * the body is NOT treated as an error.
   *
   * @throws Exception if building the JSON body fails
   */
  @Test
  public void testPatchWithoutContentFieldsBypassesAccountingConceptRequirement() throws Exception {
    MatchRuleHandler spyHandler = spy(new MatchRuleHandler());
    doNothing().when(spyHandler).enterAdminMode();
    doNothing().when(spyHandler).exitAdminMode();

    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("match-rule");
    when(ctx.getHttpMethod()).thenReturn("PATCH");
    when(ctx.getRecordId()).thenReturn("RULE-1");
    // Partial PATCH: no name / textCondition / textPattern → full validation must be skipped.
    when(ctx.getRequestBody()).thenReturn(body("priority", 20));

    // Expect null (valid — passes to CRUD).
    assertNull(spyHandler.handle(ctx));
  }

  // ── accounting dimensions (ETP-4950) ─────────────────────────────────────────

  /** A handler spy whose admin-mode seams are inert, so {@code OBContext} is never touched. */
  private static MatchRuleHandler quietSpy() {
    MatchRuleHandler spyHandler = spy(new MatchRuleHandler());
    doNothing().when(spyHandler).enterAdminMode();
    doNothing().when(spyHandler).exitAdminMode();
    return spyHandler;
  }

  /** A {@code match-rule} GET context carrying {@code ?action=<action>} (null → no action). */
  private static NeoContext getActionCtx(String action) {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn(SPEC);
    when(ctx.getHttpMethod()).thenReturn("GET");
    Map<String, String> qp = new HashMap<>();
    if (action != null) {
      qp.put("action", action);
    }
    when(ctx.getQueryParams()).thenReturn(qp);
    return ctx;
  }

  private static Set<String> dims(String... keys) {
    return new HashSet<>(Arrays.asList(keys));
  }

  /** Pins the tenant's active FAT header dimensions for the duration of the mocked static. */
  private static void stubActive(MockedStatic<AccountingDimensionsSupport> mocked,
      Set<String> active) {
    mocked.when(() -> AccountingDimensionsSupport.activeHeaderDimensionsForCurrentClient(FAT))
        .thenReturn(active);
  }

  /** Unwraps {@code {response:{data:{dimensions:[...]}}}}. */
  private static JSONArray dimensionsOf(NeoResponse response) throws Exception {
    assertNotNull("expected a response, got null", response);
    return response.getBody().getJSONObject("response").getJSONObject("data")
        .getJSONArray("dimensions");
  }

  /**
   * {@code GET ?action=activeDimensions} lists the active dimensions in the canonical
   * {@code DIM_ORDER}, not in the (unordered) set's iteration order — the rule form renders its
   * selectors straight off this array.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testBuildActiveDimensionsListsActiveKeysInCanonicalOrder() throws Exception {
    try (MockedStatic<AccountingDimensionsSupport> mocked =
             mockStatic(AccountingDimensionsSupport.class)) {
      stubActive(mocked, dims(AccountingDimensionsSupport.DIM_PRODUCT,
          AccountingDimensionsSupport.DIM_PROJECT,
          AccountingDimensionsSupport.DIM_ORGANIZATION));

      MatchRuleHandler spyHandler = quietSpy();
      NeoResponse response = spyHandler.buildActiveDimensions();

      assertEquals(200, response.getHttpStatus());
      JSONArray arr = dimensionsOf(response);
      assertEquals(3, arr.length());
      assertEquals(AccountingDimensionsSupport.DIM_ORGANIZATION, arr.getString(0));
      assertEquals(AccountingDimensionsSupport.DIM_PROJECT, arr.getString(1));
      assertEquals(AccountingDimensionsSupport.DIM_PRODUCT, arr.getString(2));
      verify(spyHandler).enterAdminMode();
      verify(spyHandler).exitAdminMode();
    }
  }

  /**
   * A dimension switched off for the tenant is absent from the payload, so the rule form stops
   * offering it (the same way the New Movement wizard does).
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testBuildActiveDimensionsOmitsInactiveDimensions() throws Exception {
    try (MockedStatic<AccountingDimensionsSupport> mocked =
             mockStatic(AccountingDimensionsSupport.class)) {
      stubActive(mocked, Collections.singleton(AccountingDimensionsSupport.DIM_COSTCENTER));

      JSONArray arr = dimensionsOf(quietSpy().buildActiveDimensions());

      assertEquals(1, arr.length());
      assertEquals(AccountingDimensionsSupport.DIM_COSTCENTER, arr.getString(0));
    }
  }

  /**
   * With no dimension active the envelope is still well-formed, carrying an empty array rather than
   * a missing key.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testBuildActiveDimensionsReturnsAnEmptyArrayWhenNothingIsActive() throws Exception {
    try (MockedStatic<AccountingDimensionsSupport> mocked =
             mockStatic(AccountingDimensionsSupport.class)) {
      stubActive(mocked, Collections.emptySet());

      assertEquals(0, dimensionsOf(quietSpy().buildActiveDimensions()).length());
    }
  }

  /** An unreadable accounting configuration surfaces as a 500, with admin mode still released. */
  @Test
  public void testBuildActiveDimensionsReturns500WhenResolutionThrows() {
    try (MockedStatic<AccountingDimensionsSupport> mocked =
             mockStatic(AccountingDimensionsSupport.class)) {
      mocked.when(() -> AccountingDimensionsSupport.activeHeaderDimensionsForCurrentClient(FAT))
          .thenThrow(new IllegalStateException("no accounting schema"));

      MatchRuleHandler spyHandler = quietSpy();
      assertStatus(SERVER_ERROR, spyHandler.buildActiveDimensions());
      verify(spyHandler).exitAdminMode();
    }
  }

  /**
   * {@code handle()} routes {@code GET ?action=activeDimensions} to the read action instead of
   * letting it fall through to the generic CRUD list.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testHandleRoutesTheActiveDimensionsAction() throws Exception {
    try (MockedStatic<AccountingDimensionsSupport> mocked =
             mockStatic(AccountingDimensionsSupport.class)) {
      stubActive(mocked, Collections.singleton(AccountingDimensionsSupport.DIM_PROJECT));

      NeoResponse response = quietSpy().handle(getActionCtx("activeDimensions"));

      assertNotNull(response);
      assertEquals(200, response.getHttpStatus());
      assertEquals(AccountingDimensionsSupport.DIM_PROJECT, dimensionsOf(response).getString(0));
    }
  }

  /** A GET carrying some other action still falls through to the generic CRUD (null). */
  @Test
  public void testHandleIgnoresUnknownGetActions() {
    try (MockedStatic<AccountingDimensionsSupport> mocked =
             mockStatic(AccountingDimensionsSupport.class)) {
      assertNull(quietSpy().handle(getActionCtx("somethingElse")));
      mocked.verifyNoInteractions();
    }
  }

  /** A GET with no action at all falls through to the generic CRUD list (null). */
  @Test
  public void testHandleIgnoresGetWithoutAction() {
    try (MockedStatic<AccountingDimensionsSupport> mocked =
             mockStatic(AccountingDimensionsSupport.class)) {
      assertNull(quietSpy().handle(getActionCtx(null)));
      mocked.verifyNoInteractions();
    }
  }

  /** The activeDimensions action belongs to the match-rule spec only. */
  @Test
  public void testHandleIgnoresActiveDimensionsForAnotherSpec() {
    try (MockedStatic<AccountingDimensionsSupport> mocked =
             mockStatic(AccountingDimensionsSupport.class)) {
      NeoContext ctx = getActionCtx("activeDimensions");
      when(ctx.getSpecName()).thenReturn("financial-account");

      assertNull(quietSpy().handle(ctx));
      mocked.verifyNoInteractions();
    }
  }

  // ── stripInactiveDimensions ──────────────────────────────────────────────────

  /**
   * THE TICKET'S WRITE-SIDE REQUIREMENT: a dimension field whose dimension is not active is
   * REMOVED from the request body (never rejected, never cleared on the record), while the active
   * ones and every unrelated field are left exactly as sent.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testStripInactiveDimensionsDropsOnlyTheInactiveFields() throws Exception {
    JSONObject b = body(F_PROJECT, "PJ-1", F_COST_CENTER, "CC-1", F_PRODUCT, "PR-1",
        "name", "Bank fee");
    try (MockedStatic<AccountingDimensionsSupport> mocked =
             mockStatic(AccountingDimensionsSupport.class)) {
      stubActive(mocked, dims(AccountingDimensionsSupport.DIM_COSTCENTER,
          AccountingDimensionsSupport.DIM_PRODUCT));

      handler.stripInactiveDimensions(b);

      assertFalse("an inactive dimension must be dropped from the body", b.has(F_PROJECT));
      assertEquals("CC-1", b.getString(F_COST_CENTER));
      assertEquals("PR-1", b.getString(F_PRODUCT));
      assertEquals("Bank fee", b.getString("name"));
    }
  }

  /**
   * With no dimension active, all three fields are dropped and nothing else is touched.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testStripInactiveDimensionsDropsAllThreeWhenNoneIsActive() throws Exception {
    JSONObject b = body(F_PROJECT, "PJ-1", F_COST_CENTER, "CC-1", F_PRODUCT, "PR-1",
        "textPattern", "COMM");
    try (MockedStatic<AccountingDimensionsSupport> mocked =
             mockStatic(AccountingDimensionsSupport.class)) {
      stubActive(mocked, Collections.emptySet());

      handler.stripInactiveDimensions(b);

      assertFalse(b.has(F_PROJECT));
      assertFalse(b.has(F_COST_CENTER));
      assertFalse(b.has(F_PRODUCT));
      assertEquals("COMM", b.getString("textPattern"));
    }
  }

  /**
   * A body that carries none of the three dimension fields must not trigger the accounting-
   * configuration lookup at all — an inline {@code active} PATCH costs no extra query.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testStripInactiveDimensionsSkipsResolutionWhenNoDimensionFieldIsPresent()
      throws Exception {
    JSONObject b = body("name", "Bank fee", "active", Boolean.FALSE);
    try (MockedStatic<AccountingDimensionsSupport> mocked =
             mockStatic(AccountingDimensionsSupport.class)) {
      handler.stripInactiveDimensions(b);

      mocked.verifyNoInteractions();
      assertEquals("Bank fee", b.getString("name"));
    }
  }

  /**
   * Fail OPEN: an unreadable accounting configuration must not block saving a rule, so the body is
   * kept exactly as sent.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testStripInactiveDimensionsFailsOpenAndKeepsTheBodyIntact() throws Exception {
    JSONObject b = body(F_PROJECT, "PJ-1", F_COST_CENTER, "CC-1", F_PRODUCT, "PR-1");
    try (MockedStatic<AccountingDimensionsSupport> mocked =
             mockStatic(AccountingDimensionsSupport.class)) {
      mocked.when(() -> AccountingDimensionsSupport.activeHeaderDimensionsForCurrentClient(FAT))
          .thenThrow(new IllegalStateException("no accounting schema"));

      handler.stripInactiveDimensions(b);

      assertEquals("PJ-1", b.getString(F_PROJECT));
      assertEquals("CC-1", b.getString(F_COST_CENTER));
      assertEquals("PR-1", b.getString(F_PRODUCT));
    }
  }

  /**
   * A dimension present in the body with a blank value is dropped too when its dimension is
   * inactive: the strip is decided by the dimension, not by the value.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testStripInactiveDimensionsDropsAnInactiveFieldEvenWhenBlank() throws Exception {
    JSONObject b = body(F_PROJECT, "");
    try (MockedStatic<AccountingDimensionsSupport> mocked =
             mockStatic(AccountingDimensionsSupport.class)) {
      stubActive(mocked, Collections.singleton(AccountingDimensionsSupport.DIM_PRODUCT));

      handler.stripInactiveDimensions(b);

      assertFalse(b.has(F_PROJECT));
    }
  }

  // ── validateWrite wiring ─────────────────────────────────────────────────────

  /**
   * {@code validateWrite} strips the inactive dimensions BEFORE validating the content, so a
   * clone/edit form pre-filled from a row that still holds a now-inactive dimension saves cleanly
   * instead of being rejected.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testValidateWriteStripsInactiveDimensionsBeforeValidating() throws Exception {
    JSONObject b = body("name", "Bank fee", "textCondition", "C", "textPattern", "COMM",
        "accountingConcept", "GL-001", F_PROJECT, "PJ-1", F_COST_CENTER, "CC-1");
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn("POST");

    try (MockedStatic<AccountingDimensionsSupport> mocked =
             mockStatic(AccountingDimensionsSupport.class)) {
      stubActive(mocked, Collections.singleton(AccountingDimensionsSupport.DIM_COSTCENTER));

      assertNull(handler.validateWrite(ctx, b));
      assertFalse(b.has(F_PROJECT));
      assertEquals("CC-1", b.getString(F_COST_CENTER));
    }
  }

  /**
   * A PATCH carrying ONLY an inactive dimension is stripped and then passes through: with no
   * content fields left to check, {@code validateWrite} must not manufacture a validation error.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testValidateWriteStripsInactiveDimensionOnPartialPatch() throws Exception {
    JSONObject b = body(F_PROJECT, "PJ-1");
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn("PATCH");

    try (MockedStatic<AccountingDimensionsSupport> mocked =
             mockStatic(AccountingDimensionsSupport.class)) {
      stubActive(mocked, Collections.emptySet());

      assertNull(handler.validateWrite(ctx, b));
      assertFalse(b.has(F_PROJECT));
    }
  }

  /**
   * Stripping a dimension never masks a content problem: an invalid body is still rejected with
   * HTTP 400 after the strip.
   *
   * @throws Exception if the JSON plumbing fails
   */
  @Test
  public void testValidateWriteStillRejectsInvalidContentAfterStripping() throws Exception {
    JSONObject b = body("name", "", "textCondition", "C", "textPattern", "COMM", F_PROJECT, "PJ-1");
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getHttpMethod()).thenReturn("POST");

    try (MockedStatic<AccountingDimensionsSupport> mocked =
             mockStatic(AccountingDimensionsSupport.class)) {
      stubActive(mocked, Collections.emptySet());

      assertStatus(BAD_REQUEST, handler.validateWrite(ctx, b));
      assertFalse(b.has(F_PROJECT));
    }
  }

  private static String repeat(String s, int n) {
    StringBuilder sb = new StringBuilder(s.length() * n);
    for (int i = 0; i < n; i++) {
      sb.append(s);
    }
    return sb.toString();
  }
}
