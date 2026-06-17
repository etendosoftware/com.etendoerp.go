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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Mockito-driven unit tests for {@link TransactionTypeHandler} — the create/enrich pre-hook
 * for the user-definable transaction-type lookup served by generic NEO Headless CRUD
 * (ETP-4099).
 *
 * <p>Strategy mirrors the sibling {@code MatchRuleHandlerTest}:
 * <ul>
 *   <li>{@code slugify} is a pure static helper (no DAL) and is exercised directly over a
 *       range of display names — the highest-value pure unit surface.</li>
 *   <li>{@code validateAndEnrich} name validation and search-key injection are pure JSON
 *       mutations; the duplicate-key (409) branch goes through {@code searchKeyExists}, which
 *       hits {@code OBDal} — that branch is stubbed on a spy here and is otherwise covered by
 *       the integration suite (no OBDal boot in a unit test).</li>
 *   <li>{@code handle()} routing is covered with a {@link NeoContext} mock; the admin-mode
 *       seams are stubbed on a spy so {@code OBContext} is never touched.</li>
 * </ul>
 *
 * <p>JUnit 4 + Silent runner mirror the sibling handler tests; {@code clearInlineMocks()}
 * after each test keeps the single test JVM's heap flat.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class TransactionTypeHandlerTest {

  private static final int BAD_REQUEST = HttpServletResponse.SC_BAD_REQUEST;   // 400
  private static final int CONFLICT = HttpServletResponse.SC_CONFLICT;         // 409

  private TransactionTypeHandler handler;

  @Before
  public void setUp() {
    handler = new TransactionTypeHandler();
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

  private static String repeat(String s, int n) {
    StringBuilder sb = new StringBuilder(s.length() * n);
    for (int i = 0; i < n; i++) {
      sb.append(s);
    }
    return sb.toString();
  }

  // ── slugify: pure static, no DAL ─────────────────────────────────────────────

  @Test
  public void slugifyStripsAccentsSpacesAndUppercases() {
    // "Comisión bancaria" → accents stripped, space → underscore, uppercased.
    assertEquals("COMISION_BANCARIA", TransactionTypeHandler.slugify("Comisión bancaria"));
  }

  @Test
  public void slugifyCollapsesRunsOfNonAlphanumericIntoSingleUnderscore() {
    assertEquals("BANK_FEE", TransactionTypeHandler.slugify("Bank   ---  fee"));
  }

  @Test
  public void slugifyTrimsLeadingAndTrailingPunctuation() {
    // Leading/trailing symbols must not leave dangling underscores.
    assertEquals("DEPOSIT", TransactionTypeHandler.slugify("  ...Deposit!!  "));
  }

  @Test
  public void slugifyKeepsDigits() {
    assertEquals("TYPE_43", TransactionTypeHandler.slugify("Type 43"));
  }

  @Test
  public void slugifyFallsBackToTypeForAllSymbolName() {
    // A name with no alphanumeric content slugifies to the empty string → DEFAULT_SLUG.
    assertEquals("TYPE", TransactionTypeHandler.slugify("!!! --- @@@"));
  }

  @Test
  public void slugifyHandlesMultipleAccentedCharacters() {
    assertEquals("DEVOLUCION_ANOS", TransactionTypeHandler.slugify("Devolución años"));
  }

  // ── validateAndEnrich: name validation ───────────────────────────────────────

  @Test
  public void validateAndEnrichRejectsBlankName() throws Exception {
    assertStatus(BAD_REQUEST, handler.validateAndEnrich(body("name", "   "), null));
  }

  @Test
  public void validateAndEnrichRejectsMissingName() throws Exception {
    assertStatus(BAD_REQUEST, handler.validateAndEnrich(new JSONObject(), null));
  }

  @Test
  public void validateAndEnrichRejectsNameTooLong() throws Exception {
    assertStatus(BAD_REQUEST, handler.validateAndEnrich(body("name", repeat("a", 61)), null));
  }

  // ── validateAndEnrich: search-key injection ──────────────────────────────────

  @Test
  public void validateAndEnrichInjectsDerivedSearchKeyWhenAbsent() throws Exception {
    // No DAL collision (spy stub) → the handler must derive and inject the slug.
    TransactionTypeHandler spyHandler = spy(new TransactionTypeHandler());
    doReturn(false).when(spyHandler).searchKeyExists("COMISION_BANCARIA", null);

    JSONObject requestBody = body("name", "Comisión bancaria");
    NeoResponse result = spyHandler.validateAndEnrich(requestBody, null);

    assertNull("a valid create must not be rejected", result);
    assertEquals("COMISION_BANCARIA", requestBody.getString("searchKey"));
  }

  @Test
  public void validateAndEnrichPreservesCallerSuppliedSearchKey() throws Exception {
    // When the caller already sent a searchKey it must NOT be overwritten by the slug.
    TransactionTypeHandler spyHandler = spy(new TransactionTypeHandler());
    doReturn(false).when(spyHandler).searchKeyExists("CUSTOM_KEY", null);

    JSONObject requestBody = body("name", "Comisión bancaria", "searchKey", "CUSTOM_KEY");
    NeoResponse result = spyHandler.validateAndEnrich(requestBody, null);

    assertNull(result);
    assertEquals("CUSTOM_KEY", requestBody.getString("searchKey"));
  }

  @Test
  public void validateAndEnrichReturnsConflictWhenSearchKeyExists() throws Exception {
    // The 409 branch routes through searchKeyExists (OBDal). Booting OBDal in a unit test
    // is out of scope — the live duplicate-key path is covered by the integration suite.
    // Here we stub the collision seam to assert the HTTP 409 mapping only.
    TransactionTypeHandler spyHandler = spy(new TransactionTypeHandler());
    doReturn(true).when(spyHandler).searchKeyExists("BANK_FEE", null);

    assertStatus(CONFLICT, spyHandler.validateAndEnrich(body("name", "Bank fee"), null));
  }

  // ── handle() routing ─────────────────────────────────────────────────────────

  @Test
  public void handleIgnoresOtherSpecs() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("match-rule");
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleLetsReadMethodsThrough() {
    // GET is not a write method → straight through to generic CRUD, no enrichment.
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("transaction-type");
    when(ctx.getHttpMethod()).thenReturn("GET");
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleLetsNullBodyThrough() {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("transaction-type");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRequestBody()).thenReturn(null);
    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleRejectsBlankNameOnPost() throws Exception {
    TransactionTypeHandler spyHandler = spy(new TransactionTypeHandler());
    doNothing().when(spyHandler).enterAdminMode();
    doNothing().when(spyHandler).exitAdminMode();

    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("transaction-type");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRecordId()).thenReturn(null);
    when(ctx.getRequestBody()).thenReturn(body("name", ""));

    assertStatus(BAD_REQUEST, spyHandler.handle(ctx));
    verify(spyHandler).enterAdminMode();
    verify(spyHandler).exitAdminMode();
  }

  @Test
  public void handleEnrichesAndAcceptsValidCreate() throws Exception {
    TransactionTypeHandler spyHandler = spy(new TransactionTypeHandler());
    doNothing().when(spyHandler).enterAdminMode();
    doNothing().when(spyHandler).exitAdminMode();
    doReturn(false).when(spyHandler).searchKeyExists("BANK_FEE", null);

    JSONObject requestBody = body("name", "Bank fee");
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("transaction-type");
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRecordId()).thenReturn(null);
    when(ctx.getRequestBody()).thenReturn(requestBody);

    assertNull("a valid create must proceed to generic CRUD", spyHandler.handle(ctx));
    assertEquals("BANK_FEE", requestBody.getString("searchKey"));
    verify(spyHandler).enterAdminMode();
    verify(spyHandler).exitAdminMode();
  }

  // ── isWriteMethod is exercised indirectly via handle() routing above; assert the
  //    accepted write verbs here as a quick guard against an accidental verb drop. ──

  @Test
  public void handleTreatsPutAndPatchAsWriteMethods() throws Exception {
    for (String method : new String[] {"PUT", "PATCH"}) {
      TransactionTypeHandler spyHandler = spy(new TransactionTypeHandler());
      doNothing().when(spyHandler).enterAdminMode();
      doNothing().when(spyHandler).exitAdminMode();

      NeoContext ctx = mock(NeoContext.class);
      when(ctx.getSpecName()).thenReturn("transaction-type");
      when(ctx.getHttpMethod()).thenReturn(method);
      when(ctx.getRecordId()).thenReturn(null);
      // Blank name short-circuits before searchKeyExists is reached, so no DAL stub needed.
      when(ctx.getRequestBody()).thenReturn(body("name", ""));

      assertStatus(BAD_REQUEST, spyHandler.handle(ctx));
    }
  }

  // ── slugify guard: derived key never empty (feeds the NOT-NULL Value column) ──

  @Test
  public void slugifyNeverReturnsBlank() {
    assertFalse(TransactionTypeHandler.slugify("   ").isBlank());
    assertTrue(TransactionTypeHandler.slugify("a").length() >= 1);
  }
}
