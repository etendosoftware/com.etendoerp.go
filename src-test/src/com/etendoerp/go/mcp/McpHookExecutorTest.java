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
package com.etendoerp.go.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoQuotaWriteGate;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.usage.quota.QuotaDefinition;
import com.etendoerp.go.usage.quota.QuotaEvaluation;
import com.etendoerp.go.usage.quota.QuotaVerdict;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;

/**
 * Unit tests for {@link McpHookExecutor}.
 * <p>
 * Tests cover the pure-logic methods that require no DAL session:
 * {@code neoResponseToMcpResult}, {@code runPreHook}, {@code runPostHook}, and
 * the early-exit paths of {@code resolveEntityHandler} (blank/null qualifier).
 * The CDI lookup path of {@code resolveEntityHandler} is covered by integration tests.
 * {@code buildActionHookContext} is covered here with a statically mocked
 * {@code OBContext}; the CRUD/DEFAULTS context builders remain integration-covered.
 *
 * @covers com.etendoerp.go.mcp.McpHookExecutor
 * @covers com.etendoerp.go.schemaforge.NeoQuotaWriteGate
 * @covers com.etendoerp.go.mcp.McpMessageKeyHints
 * @covers com.etendoerp.go.mcp.McpToolResponses
 */
public class McpHookExecutorTest {

  private static final String FIELD_CONTENT = "content";
  private static final String FIELD_IS_ERROR = "isError";
  private static final String FIELD_TYPE = "type";
  private static final String FIELD_TEXT = "text";
  private static final String FIELD_STATUS = "status";
  private static final String FIELD_DELETED = "deleted";
  private static final String FIELD_ID = "id";
  private static final String DELETE_RECORD_ID = "FA-DELETE-1";
  private static final String DELETE_BLOCKED_REASON =
      "Cannot delete this account. This account has registered transactions.";

  /** Initialises the class-level {@code @Mock} fields used by the delete pre-hook tests. */
  @Rule
  public MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock
  private NeoHandler deleteHandler;

  @Mock
  private NeoContext deleteCtx;

  // ── neoResponseToMcpResult ────────────────────────────────────────────

  @Test
  public void testNeoResponseToMcpResultSuccessStatusReturnsTextContentWithoutIsError()
      throws Exception {
    JSONObject body = new JSONObject();
    body.put("id", "abc123");
    NeoResponse response = NeoResponse.ok(body);

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(response);

    assertNotNull(result);
    JSONArray content = result.getJSONArray(FIELD_CONTENT);
    assertEquals(1, content.length());
    assertEquals("text", content.getJSONObject(0).getString(FIELD_TYPE));
    assertFalse("isError must not be set on 200", result.has(FIELD_IS_ERROR));
  }

  @Test
  public void testNeoResponseToMcpResultCreatedStatusReturnsTextContent() throws Exception {
    JSONObject body = new JSONObject();
    body.put("id", "new-record");
    NeoResponse response = NeoResponse.created(body);

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(response);

    assertNotNull(result);
    assertFalse("201 is not an error", result.has(FIELD_IS_ERROR));
    JSONArray content = result.getJSONArray(FIELD_CONTENT);
    assertTrue(content.getJSONObject(0).getString(FIELD_TEXT).contains("new-record"));
  }

  @Test
  public void testNeoResponseToMcpResult400StatusSetsIsError() throws Exception {
    NeoResponse response = NeoResponse.error(400, "Name is required");

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(response);

    assertNotNull(result);
    assertTrue("400 must set isError", result.getBoolean(FIELD_IS_ERROR));
  }

  /**
   * ETP-5051 — an {@code etendo_create} refused by a plan quota reaches the agent as an MCP error
   * that still carries the 403, the {@code QUOTA_EXCEEDED} code, the sentence and the QuotaItem.
   */
  @Test
  public void testNeoResponseToMcpResultKeepsAQuotaRefusalMachineReadable() throws Exception {
    QuotaDefinition quota = QuotaDefinition.builder().resourceId("RES-DOCS")
        .resourceKey("POSTED_SALES_INVOICES").resourceName("Posted sales invoices")
        .unitLabel("Inv").enforcementMode("block").includedQty(100L).build();
    NeoResponse refusal = NeoQuotaWriteGate.refusalResponse(
        new QuotaEvaluation(quota, "month", null, 100L, QuotaVerdict.DENY));

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(refusal);

    assertTrue(result.getBoolean(FIELD_IS_ERROR));
    JSONObject body = new JSONObject(
        result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString(FIELD_TEXT));
    assertEquals(403, body.getInt(FIELD_STATUS));
    assertEquals("QUOTA_EXCEEDED", body.getString("code"));
    assertEquals("Quota exceeded: Posted sales invoices (100/100 Inv)", body.getString("detail"));
    assertEquals("POSTED_SALES_INVOICES",
        body.getJSONObject("quota").getString("resourceKey"));
    assertEquals(100L, body.getJSONObject("quota").getLong("consumed"));
  }

  @Test
  public void testNeoResponseToMcpResult409StatusSetsIsError() throws Exception {
    NeoResponse response = NeoResponse.error(409, "Duplicate priority");

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(response);

    assertTrue(result.getBoolean(FIELD_IS_ERROR));
  }

  @Test
  public void testNeoResponseToMcpResult500StatusSetsIsError() throws Exception {
    NeoResponse response = NeoResponse.error(500, "Unexpected error");

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(response);

    assertTrue(result.getBoolean(FIELD_IS_ERROR));
  }

  @Test
  public void testNeoResponseToMcpResultNullBodyReturnsEmptyJsonText() throws Exception {
    NeoResponse response = NeoResponse.noContent(); // 204 + null body

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(response);

    assertNotNull(result);
    assertFalse(result.has(FIELD_IS_ERROR));
    JSONArray content = result.getJSONArray(FIELD_CONTENT);
    assertEquals("{}", content.getJSONObject(0).getString(FIELD_TEXT));
  }

  @Test
  public void testNeoResponseToMcpResultErrorWithNullBodyIncludesStatusInText() throws Exception {
    NeoResponse response = new NeoResponse(503, null);

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(response);

    assertTrue(result.getBoolean(FIELD_IS_ERROR));
    String text = result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString(FIELD_TEXT);
    assertTrue("Text should mention status 503", text.contains("503"));
  }

  /**
   * The fourth error funnel, asserted at the funnel rather than only at the helper
   * (ETP-4793 / IMP-5 clause (iv)).
   *
   * <p>The tests above pass on the pre-fix code too: they check {@code isError} and that the text
   * mentions the status, which the verbatim body already did. That is why the defect survived —
   * nothing here ever looked at the <em>shape</em> an agent has to parse. This one does, and it is
   * the reason the assertion is on {@code error} being a code string: that key held a nested object
   * before, so an agent branching on it got neither a match nor an exception, just silence.</p>
   */
  @Test
  public void testNeoResponseToMcpResultErrorIsNormalizedToFlatEnvelope() throws Exception {
    NeoResponse response = NeoResponse.error(422,
        "No accounting schema with currency is configured for organization 6184");

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(response);

    assertTrue(result.getBoolean(FIELD_IS_ERROR));
    JSONObject envelope = new JSONObject(
        result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString(FIELD_TEXT));
    assertEquals("validation_error", envelope.getString("error"));
    assertEquals(422, envelope.getInt(FIELD_STATUS));
    assertTrue(envelope.getString("detail").startsWith("No accounting schema"));
  }

  /**
   * ETP-5529: a posting customization answers a locked document with a flat 422 body
   * ({@code success}, the GO-locale {@code message}, {@code messageKeys}). Normalizing it for an
   * agent must keep both — the message is the only localized text an MCP client gets, and the key
   * is how it can tell this transient lock apart from a real posting error.
   */
  @Test
  public void testNeoResponseToMcpResultKeepsPostingFailureMessageAndKeys() throws Exception {
    JSONObject body = new JSONObject();
    body.put("success", false);
    body.put("message", "Este registro está siendo contabilizado por otro proceso");
    body.put("messageKeys", new JSONArray().put("OtherPostingProcessActive"));
    NeoResponse response = NeoResponse.error(422, body);

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(response);

    assertTrue(result.getBoolean(FIELD_IS_ERROR));
    JSONObject envelope = new JSONObject(
        result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString(FIELD_TEXT));
    assertEquals("validation_error", envelope.getString("error"));
    assertEquals(422, envelope.getInt(FIELD_STATUS));
    assertEquals("Este registro está siendo contabilizado por otro proceso", envelope.getString("message"));
    assertEquals("OtherPostingProcessActive", envelope.getJSONArray("messageKeys").getString(0));
  }

  /**
   * ETP-5577: a refusal keyed {@code PeriodNotAvailable} — a completion's flat body or a payment's
   * nested {@code error} — reaches the agent with a hint naming the {@code openClose} action.
   */
  @Test
  public void testNeoResponseToMcpResultAddsPeriodNotAvailableHint() throws Exception {
    JSONObject flat = new JSONObject();
    flat.put("status", "error");
    flat.put("message", "El Periodo no existe o no está abierto");
    flat.put("messageKeys", new JSONArray().put("PeriodNotAvailable"));
    JSONObject nested = new JSONObject().put("error", new JSONObject()
        .put("message", "El Periodo no existe o no está abierto").put("status", 400)
        .put("messageKeys", new JSONArray().put("PeriodNotAvailable")));

    for (JSONObject body : List.of(flat, nested)) {
      JSONObject result = McpHookExecutor.neoResponseToMcpResult(NeoResponse.error(400, body));
      JSONObject envelope = new JSONObject(
          result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString(FIELD_TEXT));
      assertEquals(McpMessageKeyHints.PERIOD_NOT_AVAILABLE_HINT, envelope.getString("hint"));
    }
  }

  /**
   * ETP-5577: core's raw {@code @ETPR_PeriodClosedForUnRemovePayment@} is named on MCP as on REST,
   * so the agent gets the keyed refusal and the hint, never the raw token.
   */
  @Test
  public void testNeoResponseToMcpResultNamesTheRawCorePeriodToken() throws Exception {
    JSONObject result = McpHookExecutor.neoResponseToMcpResult(
        NeoResponse.error(400, "@ETPR_PeriodClosedForUnRemovePayment@"));
    String text = result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString(FIELD_TEXT);
    JSONObject envelope = new JSONObject(text);
    assertFalse(text.contains("ETPR_"));
    assertEquals(McpMessageKeyHints.PERIOD_NOT_AVAILABLE_HINT, envelope.getString("hint"));
  }

  /** Any other refusal keeps its envelope without the period hint. */
  @Test
  public void testNeoResponseToMcpResultHasNoPeriodHintForOtherKeys() throws Exception {
    JSONObject body = new JSONObject();
    body.put("message", "locked");
    body.put("messageKeys", new JSONArray().put("OtherPostingProcessActive"));

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(NeoResponse.error(422, body));

    JSONObject envelope = new JSONObject(
        result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString(FIELD_TEXT));
    assertFalse(envelope.has("hint"));
  }

  /**
   * ETP-5714: core's "Documento ya procesado" (message key {@code 20501}) gets a hint that names
   * reactivation and the actions view, wherever the key sits — keyed on the message identity, never
   * on the entity — and a body that already carries its own hint keeps it.
   */
  @Test
  public void testNeoResponseToMcpResultAddsProcessedDocumentHint() throws Exception {
    JSONObject flat = new JSONObject().put("message", "Documento ya procesado")
        .put("messageKeys", new JSONArray().put("20501"));
    JSONObject nested = new JSONObject().put("error", new JSONObject()
        .put("message", "Documento ya procesado").put("status", 400)
        .put("messageKeys", new JSONArray().put("20501")));

    for (JSONObject body : List.of(flat, nested)) {
      JSONObject result = McpHookExecutor.neoResponseToMcpResult(NeoResponse.error(400, body));
      JSONObject envelope = new JSONObject(
          result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString(FIELD_TEXT));
      assertEquals(McpMessageKeyHints.DOCUMENT_PROCESSED_HINT, envelope.getString("hint"));
    }
    assertTrue(McpMessageKeyHints.DOCUMENT_PROCESSED_HINT.contains("documentAction RE"));
    assertTrue(McpMessageKeyHints.DOCUMENT_PROCESSED_HINT.contains("view:'actions'"));
    assertFalse("the hint is keyed by message, not entity: no goods-specific wording",
        McpMessageKeyHints.DOCUMENT_PROCESSED_HINT.contains("goods"));

    JSONObject own = new JSONObject().put("message", "x").put("hint", "mine")
        .put("messageKeys", new JSONArray().put("20501"));
    JSONObject kept = new JSONObject(McpHookExecutor.neoResponseToMcpResult(
        NeoResponse.error(400, own)).getJSONArray(FIELD_CONTENT).getJSONObject(0)
        .getString(FIELD_TEXT));
    assertEquals("mine", kept.getString("hint"));
  }

  /**
   * ETP-5714: a classified write failure (the DAL path) that carries message key {@code 20501}
   * gets the keyed hint, not the generic one of its class; without a known key the class hint
   * stays. Covers purchase and sales lines alike: the key is the identity, never the entity.
   */
  @Test
  public void testClassifiedEnvelopePrefersTheMessageKeyHint() throws Exception {
    JSONObject keyed = McpToolResponses.classifiedEnvelope(400, "business_rule",
        "Documento ya procesado", List.of("20501"));
    JSONObject plain = McpToolResponses.classifiedEnvelope(400, "business_rule", "rule",
        List.of("OtherKey"));
    JSONObject none = McpToolResponses.classifiedEnvelope(400, "business_rule", "rule", null);

    assertEquals(McpMessageKeyHints.DOCUMENT_PROCESSED_HINT, keyed.getString("hint"));
    assertTrue(plain.getString("hint").startsWith("A business rule rejected"));
    assertTrue(none.getString("hint").startsWith("A business rule rejected"));
  }

  /** The two keyed hints do not leak into each other, and a key that merely contains 20501 is not it. */
  @Test
  public void testMessageKeyHintsAreKeyedExactly() throws Exception {
    JSONObject period = new JSONObject().put("message", "p")
        .put("messageKeys", new JSONArray().put("PeriodNotAvailable"));
    JSONObject lookalike = new JSONObject().put("message", "l")
        .put("messageKeys", new JSONArray().put("205010"));

    JSONObject periodEnvelope = new JSONObject(McpHookExecutor.neoResponseToMcpResult(
        NeoResponse.error(400, period)).getJSONArray(FIELD_CONTENT).getJSONObject(0)
        .getString(FIELD_TEXT));
    JSONObject lookalikeEnvelope = new JSONObject(McpHookExecutor.neoResponseToMcpResult(
        NeoResponse.error(400, lookalike)).getJSONArray(FIELD_CONTENT).getJSONObject(0)
        .getString(FIELD_TEXT));

    assertEquals(McpMessageKeyHints.PERIOD_NOT_AVAILABLE_HINT, periodEnvelope.getString("hint"));
    assertFalse(lookalikeEnvelope.has("hint"));
  }

  /**
   * ETP-5714: the retryable posting refusal keeps its own code through the MCP funnel, with the
   * 409, {@code retryable} and {@code retryAfterSeconds}, instead of the generic code the status
   * would give it.
   */
  @Test
  public void testNeoResponseToMcpResultKeepsTheCostNotCalculatedCode() throws Exception {
    JSONObject body = new JSONObject().put("success", false)
        .put("message", "Cost has not yet been calculated for all products in the document.")
        .put("messageKeys", new JSONArray().put("NotCalculatedCost"))
        .put("error", "costNotCalculated").put("status", 409).put("retryable", true)
        .put("retryAfterSeconds", 30).put("hint", "wait");

    JSONObject result = McpHookExecutor.neoResponseToMcpResult(NeoResponse.error(409, body));

    assertTrue(result.getBoolean(FIELD_IS_ERROR));
    JSONObject envelope = new JSONObject(
        result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString(FIELD_TEXT));
    assertEquals("costNotCalculated", envelope.getString("error"));
    assertEquals(409, envelope.getInt(FIELD_STATUS));
    assertTrue(envelope.getBoolean("retryable"));
    assertEquals(30, envelope.getInt("retryAfterSeconds"));
    assertEquals("wait", envelope.getString("hint"));
  }

  // ── runPreHook ────────────────────────────────────────────────────────

  @Test
  public void testRunPreHookNullHandlerReturnsNull() throws Exception {
    NeoContext ctx = mock(NeoContext.class);

    JSONObject result = McpHookExecutor.runPreHook(null, ctx);

    assertNull(result);
  }

  @Test
  public void testRunPreHookHandlerReturnsNullReturnsNull() throws Exception {
    NeoHandler handler = mock(NeoHandler.class);
    NeoContext ctx = mock(NeoContext.class);
    when(handler.handle(ctx)).thenReturn(null);

    JSONObject result = McpHookExecutor.runPreHook(handler, ctx);

    assertNull(result);
    verify(handler).handle(ctx);
  }

  @Test
  public void testRunPreHookHandlerReturns200ReturnsMcpTextContent() throws Exception {
    NeoHandler handler = mock(NeoHandler.class);
    NeoContext ctx = mock(NeoContext.class);
    JSONObject body = new JSONObject();
    body.put("ok", true);
    when(handler.handle(ctx)).thenReturn(NeoResponse.ok(body));

    JSONObject result = McpHookExecutor.runPreHook(handler, ctx);

    assertNotNull(result);
    assertFalse(result.has(FIELD_IS_ERROR));
  }

  @Test
  public void testRunPreHookHandlerReturns400ReturnsMcpErrorContent() throws Exception {
    NeoHandler handler = mock(NeoHandler.class);
    NeoContext ctx = mock(NeoContext.class);
    when(handler.handle(ctx)).thenReturn(NeoResponse.error(400, "Invalid"));

    JSONObject result = McpHookExecutor.runPreHook(handler, ctx);

    assertNotNull(result);
    assertTrue(result.getBoolean(FIELD_IS_ERROR));
  }

  // ── runDeletePreHook (ETP-5474) ───────────────────────────────────────

  /**
   * Parses the single text content item of an MCP result back into the JSON the agent reads.
   */
  private static JSONObject parseTextContent(JSONObject result) throws Exception {
    return new JSONObject(result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString(FIELD_TEXT));
  }

  /**
   * The live bug: a handler that fully performs the delete answers {@code noContent()} (204, null
   * body), and MCP used to forward that as the literal text {@code "{}"}. The delete pre-hook must
   * turn it into the same confirmation the generic delete path returns.
   */
  @Test
  public void testRunDeletePreHookNoContentReturnsDeleteConfirmation() throws Exception {
    when(deleteHandler.handle(deleteCtx)).thenReturn(NeoResponse.noContent());

    JSONObject result = McpHookExecutor.runDeletePreHook(deleteHandler, deleteCtx,
        DELETE_RECORD_ID);

    assertNotNull(result);
    assertFalse("a successful delete must not be flagged as an error",
        result.has(FIELD_IS_ERROR));
    JSONObject payload = parseTextContent(result);
    assertTrue(payload.getBoolean(FIELD_DELETED));
    assertEquals(DELETE_RECORD_ID, payload.getString(FIELD_ID));
  }

  /** A 2xx with an empty JSON body carries no information either — same confirmation. */
  @Test
  public void testRunDeletePreHookOkWithEmptyBodyReturnsDeleteConfirmation() throws Exception {
    when(deleteHandler.handle(deleteCtx)).thenReturn(NeoResponse.ok(new JSONObject()));

    JSONObject result = McpHookExecutor.runDeletePreHook(deleteHandler, deleteCtx,
        DELETE_RECORD_ID);

    assertFalse(result.has(FIELD_IS_ERROR));
    JSONObject payload = parseTextContent(result);
    assertTrue(payload.getBoolean(FIELD_DELETED));
    assertEquals(DELETE_RECORD_ID, payload.getString(FIELD_ID));
  }

  /**
   * A hypothetical handler that answers a DELETE with its own payload keeps that body — the
   * confirmation only replaces an empty 2xx answer.
   */
  @Test
  public void testRunDeletePreHookOkWithOwnBodyKeepsBodyIntact() throws Exception {
    JSONObject body = new JSONObject();
    body.put("handled", true);
    body.put(FIELD_ID, "HANDLER-PAYLOAD-1");
    when(deleteHandler.handle(deleteCtx)).thenReturn(NeoResponse.ok(body));

    JSONObject result = McpHookExecutor.runDeletePreHook(deleteHandler, deleteCtx,
        DELETE_RECORD_ID);

    assertFalse(result.has(FIELD_IS_ERROR));
    JSONObject payload = parseTextContent(result);
    assertTrue(payload.getBoolean("handled"));
    assertEquals("HANDLER-PAYLOAD-1", payload.getString(FIELD_ID));
    assertFalse("the handler's own body must not be replaced by a confirmation",
        payload.has(FIELD_DELETED));
  }

  /**
   * 202 Accepted means the delete was only queued, not performed: even with an empty body it must
   * NOT become a {@code deleted: true} confirmation, and is rendered exactly as the shared funnel
   * renders it.
   */
  @Test
  public void testRunDeletePreHookAcceptedWithEmptyBodyIsNotDeleteConfirmation()
      throws Exception {
    when(deleteHandler.handle(deleteCtx)).thenReturn(new NeoResponse(202, null));

    JSONObject result = McpHookExecutor.runDeletePreHook(deleteHandler, deleteCtx,
        DELETE_RECORD_ID);

    assertNotNull(result);
    assertEquals(McpHookExecutor.neoResponseToMcpResult(new NeoResponse(202, null)).toString(),
        result.toString());
    assertFalse("a 202 must never read as a delete confirmation",
        parseTextContent(result).has(FIELD_DELETED));
  }

  /**
   * A 3xx (here 304 Not Modified) with an empty body is not a completed delete either: it goes
   * through the shared funnel unchanged instead of becoming a confirmation.
   */
  @Test
  public void testRunDeletePreHookRedirectionWithEmptyBodyIsNotDeleteConfirmation()
      throws Exception {
    when(deleteHandler.handle(deleteCtx)).thenReturn(new NeoResponse(304, null));

    JSONObject result = McpHookExecutor.runDeletePreHook(deleteHandler, deleteCtx,
        DELETE_RECORD_ID);

    assertNotNull(result);
    assertEquals(McpHookExecutor.neoResponseToMcpResult(new NeoResponse(304, null)).toString(),
        result.toString());
    assertFalse("a 3xx must never read as a delete confirmation",
        result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString(FIELD_TEXT)
            .contains("\"" + FIELD_DELETED + "\""));
  }

  /**
   * A blocked delete (CA3) stays an error: {@code isError} set and the normalized
   * {@code {error,status,detail}} envelope carrying the handler's reason, so the agent can tell
   * the user why the account was not deleted.
   */
  @Test
  public void testRunDeletePreHookConflictReturnsNormalizedErrorWithReason() throws Exception {
    when(deleteHandler.handle(deleteCtx))
        .thenReturn(NeoResponse.error(409, DELETE_BLOCKED_REASON));

    JSONObject result = McpHookExecutor.runDeletePreHook(deleteHandler, deleteCtx,
        DELETE_RECORD_ID);

    assertTrue(result.getBoolean(FIELD_IS_ERROR));
    JSONObject envelope = parseTextContent(result);
    assertTrue(envelope.has("error"));
    assertEquals(409, envelope.getInt(FIELD_STATUS));
    assertTrue(envelope.getString("detail").contains("registered transactions"));
    assertFalse("an error must never read as a delete confirmation",
        envelope.has(FIELD_DELETED));
  }

  /** A 404 from the handler (unknown record) is an error, not a confirmation. */
  @Test
  public void testRunDeletePreHookNotFoundReturnsError() throws Exception {
    when(deleteHandler.handle(deleteCtx)).thenReturn(NeoResponse.error(404, "Account not found"));

    JSONObject result = McpHookExecutor.runDeletePreHook(deleteHandler, deleteCtx,
        DELETE_RECORD_ID);

    assertTrue(result.getBoolean(FIELD_IS_ERROR));
    assertEquals(404, parseTextContent(result).getInt(FIELD_STATUS));
  }

  /** No handler means no short-circuit: the caller proceeds with the generic delete. */
  @Test
  public void testRunDeletePreHookNullHandlerReturnsNull() throws Exception {
    assertNull(McpHookExecutor.runDeletePreHook(null, deleteCtx, DELETE_RECORD_ID));
  }

  /** A handler that declines (returns null) also lets the generic delete run. */
  @Test
  public void testRunDeletePreHookHandlerReturnsNullReturnsNull() throws Exception {
    when(deleteHandler.handle(deleteCtx)).thenReturn(null);

    assertNull(McpHookExecutor.runDeletePreHook(deleteHandler, deleteCtx, DELETE_RECORD_ID));
    verify(deleteHandler).handle(deleteCtx);
  }

  /**
   * Guard: the delete confirmation is scoped to the delete pre-hook. The shared funnel used by
   * every other hook path still renders a bare 204 as {@code "{}"}, so no non-delete write starts
   * claiming {@code deleted: true}.
   */
  @Test
  public void testRunPreHookNoContentIsNotTurnedIntoDeleteConfirmation() throws Exception {
    when(deleteHandler.handle(deleteCtx)).thenReturn(NeoResponse.noContent());

    JSONObject viaPreHook = McpHookExecutor.runPreHook(deleteHandler, deleteCtx);
    JSONObject viaFunnel = McpHookExecutor.neoResponseToMcpResult(NeoResponse.noContent());

    assertEquals("{}", viaPreHook.getJSONArray(FIELD_CONTENT).getJSONObject(0)
        .getString(FIELD_TEXT));
    assertEquals("{}", viaFunnel.getJSONArray(FIELD_CONTENT).getJSONObject(0)
        .getString(FIELD_TEXT));
  }

  // ── runPostHook ───────────────────────────────────────────────────────

  @Test
  public void testRunPostHookNullHandlerReturnsNull() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    JSONObject responseJson = new JSONObject();

    JSONObject result = McpHookExecutor.runPostHook(null, ctx, responseJson);

    assertNull(result);
  }

  @Test
  public void testRunPostHookHandlerReturnsNullReturnsNull() throws Exception {
    NeoHandler handler = mock(NeoHandler.class);
    NeoContext ctx = mock(NeoContext.class);
    JSONObject responseJson = new JSONObject();
    when(handler.afterHandle(ctx)).thenReturn(null);

    JSONObject result = McpHookExecutor.runPostHook(handler, ctx, responseJson);

    assertNull(result);
    verify(ctx).setPreviousResult(any(NeoResponse.class));
    verify(handler).afterHandle(ctx);
  }

  @Test
  public void testRunPostHookHandlerReplacesResponseReturnsMcpResult() throws Exception {
    NeoHandler handler = mock(NeoHandler.class);
    NeoContext ctx = mock(NeoContext.class);
    JSONObject responseJson = new JSONObject();
    JSONObject overrideBody = new JSONObject();
    overrideBody.put("replaced", true);
    when(handler.afterHandle(ctx)).thenReturn(NeoResponse.ok(overrideBody));

    JSONObject result = McpHookExecutor.runPostHook(handler, ctx, responseJson);

    assertNotNull(result);
    assertFalse(result.has(FIELD_IS_ERROR));
    assertTrue(result.getJSONArray(FIELD_CONTENT).getJSONObject(0)
        .getString(FIELD_TEXT).contains("replaced"));
  }

  // ── runReadHook resolution (ETP-5806 follow-up) ───────────────────────

  /**
   * Repro for the live regression: an {@code @NeoExtension}-only handler (no
   * {@code Java_Qualifier}, exactly {@code BpLocationHandler}'s binding) must still have its
   * {@code afterHandle} run over an MCP {@code etendo_get}/{@code etendo_list} read.
   *
   * <p>Before the fix, {@code runReadHook} resolved its POST-phase handler with
   * {@code resolveEntityHandler(sfEntity)} — {@code NeoHandlerLookup.byQualifier} alone, no
   * annotation fallback. With a blank qualifier that always resolved to {@code null}, so the real
   * dispatch call ({@code NeoExtensionDispatcher.dispatch}, left unmocked here) short-circuited on
   * {@code NO_CUSTOMIZATION} and {@code afterHandle} never ran — the response the caller built
   * came back unchanged. This test proves the opposite: with only
   * {@code NeoExtensionDispatcher.resolveOnly} stubbed to return a handler (standing in for what
   * the {@code @NeoExtension} annotation index would resolve) and {@code dispatch} itself real, the
   * handler's {@code afterHandle} is invoked and its replacement body is what the read returns. A
   * Mockito test that calls {@code handler.afterHandle(ctx)} directly — the shape
   * {@code BpLocationHandlerTest}'s existing flag-off suite uses — would stay green through this
   * exact regression, because it never exercises how {@code runReadHook} resolves the handler in
   * the first place.
   */
  @Test
  public void testRunReadHookInvokesAfterHandleOnAnAnnotationOnlyHandler() throws Exception {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getJavaQualifier()).thenReturn(null); // @NeoExtension-only, no Java_Qualifier
    Tab adTab = mock(Tab.class);
    NeoHandler handler = mock(NeoHandler.class);
    JSONObject originalBody = new JSONObject().put("id", "loc-1")
        .put("response", new JSONObject().put("data",
            new JSONArray().put(new JSONObject().put("id", "loc-1")
                .put("streetName", "Real St"))));
    JSONObject strippedBody = new JSONObject().put("id", "loc-1")
        .put("response", new JSONObject().put("data", new JSONArray().put(new JSONObject()
            .put("id", "loc-1"))));
    when(handler.afterHandle(any(NeoContext.class))).thenReturn(NeoResponse.ok(strippedBody));

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<NeoExtensionDispatcher> dispatcher =
            mockStatic(NeoExtensionDispatcher.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(null);
      dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any(NeoExtensionRequest.class)))
          .thenReturn(handler);

      McpHookExecutor.ReadHookOutcome outcome = McpHookExecutor.runReadHook(
          "bp-location", "bpLocation", "loc-1", adTab, sfEntity, originalBody);

      verify(handler).afterHandle(any(NeoContext.class));
      assertNull(outcome.mcpError());
      assertEquals(strippedBody, outcome.body());
      assertFalse(outcome.body().getJSONObject("response").getJSONArray("data")
          .getJSONObject(0).has("streetName"));
    }
  }

  /**
   * Symmetric case: when resolution finds nothing (no annotation, no qualifier — the ordinary
   * entity with no customization at all), the read's own body is returned unchanged, exactly as
   * before this fix.
   */
  @Test
  public void testRunReadHookWithNoResolvedHandlerKeepsTheOriginalBody() throws Exception {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getJavaQualifier()).thenReturn(null);
    Tab adTab = mock(Tab.class);
    JSONObject originalBody = new JSONObject().put("id", "x");

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<NeoExtensionDispatcher> dispatcher =
            mockStatic(NeoExtensionDispatcher.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(null);
      dispatcher.when(() -> NeoExtensionDispatcher.resolveOnly(any(NeoExtensionRequest.class)))
          .thenReturn(null);

      McpHookExecutor.ReadHookOutcome outcome = McpHookExecutor.runReadHook(
          "product", "product", "p-1", adTab, sfEntity, originalBody);

      assertNull(outcome.mcpError());
      assertEquals(originalBody, outcome.body());
    }
  }

  // ── buildActionHookContext (ETP-4285) ─────────────────────────────────

  @Test
  public void testBuildActionHookContextSetsActionEndpointTypeAndFieldName() throws Exception {
    SFEntity sfEntity = mock(SFEntity.class);
    Tab adTab = mock(Tab.class);
    JSONObject params = new JSONObject();
    params.put("docAction", "CO");

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(null);

      NeoContext ctx = McpHookExecutor.buildActionHookContext("sales-order", "header",
          "REC-1", "documentAction", params, adTab, sfEntity);

      // endpointType + fieldName are exactly what handlers branch on, e.g.
      // AbstractOrderHeaderHandler.isActionDocumentActionComplete.
      assertEquals(NeoEndpointType.ACTION, ctx.getEndpointType());
      assertEquals("documentAction", ctx.getFieldName());
      assertEquals("POST", ctx.getHttpMethod());
      assertEquals("REC-1", ctx.getRecordId());
      assertEquals("sales-order", ctx.getSpecName());
      assertEquals("header", ctx.getEntityName());
      assertEquals("CO", ctx.getRequestBody().getString("docAction"));
      assertEquals(adTab, ctx.getAdTab());
      assertEquals(sfEntity, ctx.getSfEntity());
    }
  }

  // ── runPostHook after the write committed (ETP-5782) ─────────────────

  /**
   * The write before the post-hook is committed, so a refusal raised by {@code afterHandle} — here
   * a keyed one, a 422 anywhere else — is the server-side 500 it always was, with a hint telling
   * the agent to read the record before re-sending, never a "correct and retry" refusal.
   */
  @Test
  public void testPostHookFailureAfterTheCommitIsNotARetryableRefusal() throws Exception {
    NeoContext ctx = NeoContext.builder().httpMethod("POST").specName("sales-order")
        .entityName("header").build();
    com.etendoerp.go.schemaforge.NeoExtensionRequest request =
        com.etendoerp.go.schemaforge.NeoExtensionRequest.builder()
            .qualifier("q").specName("sales-order").entityName("header")
            .surface(com.etendoerp.go.schemaforge.NeoExtensionSurface.of(ctx))
            .channel(com.etendoerp.go.schemaforge.NeoExtensionChannel.MCP)
            .context(ctx).build();
    org.openbravo.dal.service.OBDal dal = mock(org.openbravo.dal.service.OBDal.class);

    try (MockedStatic<com.etendoerp.go.schemaforge.NeoExtensionDispatcher> dispatcher =
        mockStatic(com.etendoerp.go.schemaforge.NeoExtensionDispatcher.class);
        MockedStatic<org.openbravo.dal.service.OBDal> obdal =
            mockStatic(org.openbravo.dal.service.OBDal.class)) {
      dispatcher.when(() -> com.etendoerp.go.schemaforge.NeoExtensionDispatcher.dispatch(any()))
          .thenThrow(new org.openbravo.base.exception.OBException("@20502@"));
      obdal.when(org.openbravo.dal.service.OBDal::getInstance).thenReturn(dal);

      JSONObject result = McpHookExecutor.runPostHook(request, new JSONObject().put("id", "A"));

      assertTrue(result.getBoolean(FIELD_IS_ERROR));
      JSONObject envelope = new JSONObject(
          result.getJSONArray(FIELD_CONTENT).getJSONObject(0).getString(FIELD_TEXT));
      assertEquals(500, envelope.getInt(FIELD_STATUS));
      assertEquals("server_error", envelope.getString("error"));
      assertEquals(com.etendoerp.go.schemaforge.NeoErrorResponses.AFTER_COMMIT_HINT,
          envelope.getString("hint"));
      verify(dal).rollbackAndClose();
    }
  }
  // ── ETP-5680: placeholder forwarding ──

  /** The placeholders the mandatory-defaults pass recorded reach the pre-hook's context. */
  @Test
  public void testFallbackPlaceholdersAreForwardedToTheHookContext() {
    NeoContext from = NeoContext.builder()
        .specName("product").entityName("product").httpMethod("POST").build();
    from.addFallbackDefault("taxCategory");
    from.addFallbackDefault("uOM");
    NeoContext to = NeoContext.builder()
        .specName("product").entityName("product").httpMethod("POST").build();

    McpHookExecutor.forwardFallbackDefaults(from, to);

    assertEquals(java.util.Set.of("taxCategory", "uOM"), to.getFallbackDefaults());
    assertFalse(to.getFallbackDefaults().contains("productType"));
  }

  /** With nothing recorded (a value the caller sent is never recorded) nothing is forwarded. */
  @Test
  public void testNoPlaceholdersMeansNothingForwarded() {
    NeoContext from = NeoContext.builder()
        .specName("product").entityName("product").httpMethod("POST").build();
    NeoContext to = NeoContext.builder()
        .specName("product").entityName("product").httpMethod("POST").build();

    McpHookExecutor.forwardFallbackDefaults(from, to);

    assertTrue(to.getFallbackDefaults().isEmpty());
  }

  // ── resolveServerCreateFields (ETP-5710) ──────────────────────────────

  @Test
  public void testResolveServerCreateFieldsCallsCustomizationWithLiveBody() throws Exception {
    NeoHandler customization = mock(NeoHandler.class);
    SFEntity sfEntity = mock(SFEntity.class);
    JSONObject body = new JSONObject().put("businessPartner", "bp-1");

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<NeoHandlerLookup> lookup = mockStatic(NeoHandlerLookup.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(null);
      lookup.when(() -> NeoHandlerLookup.forEntity(sfEntity)).thenReturn(customization);

      McpHookExecutor.resolveServerCreateFields("sales-invoice", "header", null, sfEntity, body);

      verify(customization).resolveServerCreateFields(argThat(ctx ->
          ctx.getRequestBody() == body && "POST".equals(ctx.getHttpMethod())
              && ctx.getRecordId() == null && ctx.getEndpointType() == NeoEndpointType.CRUD));
    }
  }

  @Test
  public void testResolveServerCreateFieldsWithoutCustomizationOrOnFailureLeavesBody()
      throws Exception {
    SFEntity sfEntity = mock(SFEntity.class);
    JSONObject body = new JSONObject().put("a", "b");

    try (MockedStatic<NeoHandlerLookup> lookup = mockStatic(NeoHandlerLookup.class)) {
      lookup.when(() -> NeoHandlerLookup.forEntity(sfEntity)).thenReturn(null);
      McpHookExecutor.resolveServerCreateFields("s", "e", null, sfEntity, body);

      lookup.when(() -> NeoHandlerLookup.forEntity(sfEntity))
          .thenThrow(new IllegalStateException("boom"));
      McpHookExecutor.resolveServerCreateFields("s", "e", null, sfEntity, body);
    }
    assertEquals(1, body.length());
  }

  @Test
  public void testNeoHandlerDefaultResolveServerCreateFieldsIsNoOp() throws Exception {
    NeoHandler plain = new NeoHandler() {
      @Override
      public NeoResponse handle(NeoContext context) {
        return null;
      }
    };
    JSONObject body = new JSONObject().put("a", "b");
    plain.resolveServerCreateFields(NeoContext.builder().requestBody(body).build());
    assertEquals(1, body.length());
  }
}
