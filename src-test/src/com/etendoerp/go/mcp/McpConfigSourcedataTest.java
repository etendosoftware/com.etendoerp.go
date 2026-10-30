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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.GoodsReceiptHeaderHandler;
import com.etendoerp.go.schemaforge.GoodsShipmentHeaderHandler;
import com.etendoerp.go.schemaforge.PurchaseInvoiceHeaderHandler;
import com.etendoerp.go.schemaforge.PurchaseOrderHeaderHandler;
import com.etendoerp.go.schemaforge.ReturnMaterialReceiptHeaderHandler;
import com.etendoerp.go.schemaforge.ReturnToVendorShipmentHeaderHandler;
import com.etendoerp.go.schemaforge.SalesInvoiceHeaderHandler;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Validates every hand-authored {@code MCP_CONFIG} payload that ships in the module's sourcedata.
 *
 * <p>These payloads are the half of the parent gate that lives in data rather than code, and they
 * fail quietly: a mistyped section name, a property that does not exist, a {@code mode} nobody
 * implements — the resolver logs a warning, withholds the entity, and the MCP surface silently
 * loses an entity nobody was watching. Parsing them here turns that into a build failure, which is
 * the only place a data typo is cheap to fix.</p>
 *
 * <p>What this test can and cannot see: it validates <em>shape</em> — the payload parses, its
 * sections are registered, and each section's own validator accepts the body. It cannot check that
 * {@code parent.field} names a real DAL property, because that needs a running model; that half is
 * {@code McpParentScope}'s job at resolve time, and it is why an unresolvable field withholds the
 * entity instead of being ignored.</p>
 *
 * @covers com.etendoerp.go.mcp.McpActionsSection
 * @covers com.etendoerp.go.mcp.McpVerbsSection
 * @covers com.etendoerp.go.mcp.McpParentSection
 * @covers com.etendoerp.go.mcp.McpEntityConfig
 * @covers com.etendoerp.go.schemaforge.PurchaseOrderHeaderHandler
 * @covers com.etendoerp.go.schemaforge.SalesOrderHeaderHandler
 * @covers com.etendoerp.go.schemaforge.GoodsReceiptHeaderHandler
 * @covers com.etendoerp.go.schemaforge.ReturnToVendorShipmentHeaderHandler
 * @covers com.etendoerp.go.schemaforge.ReturnMaterialReceiptHeaderHandler
 * @covers com.etendoerp.go.schemaforge.GoodsShipmentHeaderHandler
 * @covers com.etendoerp.go.schemaforge.PurchaseInvoiceHeaderHandler
 * @covers com.etendoerp.go.schemaforge.SalesInvoiceHeaderHandler
 */
@DisplayName("MCP_CONFIG sourcedata")
class McpConfigSourcedataTest {

  private static final String SOURCEDATA = "modules/com.etendoerp.go/src-db/database/sourcedata";

  /** {@code <!--id-->  <MCP_CONFIG><![CDATA[ … ]]></MCP_CONFIG>}, one row at a time. */
  private static final Pattern ROW = Pattern.compile(
      "<!--([0-9A-Fa-f]{32})-->\\s*<MCP_CONFIG><!\\[CDATA\\[(.*?)]]></MCP_CONFIG>", Pattern.DOTALL);

  /**
   * Walk up from the working directory until the sourcedata folder appears, so the test runs the
   * same from the Etendo root, from the module, and from an IDE with either as its working
   * directory. Mirrors the lookup in {@code McpWriteVerbCoercionCallSiteTest}.
   */
  static Path sourcedataDir() {
    Path fromRoot = Paths.get(SOURCEDATA);
    if (Files.isDirectory(fromRoot)) {
      return fromRoot;
    }
    Path dir = Paths.get("").toAbsolutePath();
    while (dir != null) {
      Path candidate = dir.resolve(SOURCEDATA);
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      Path local = dir.resolve("src-db/database/sourcedata");
      if (Files.isDirectory(local)) {
        return local;
      }
      dir = dir.getParent();
    }
    return fromRoot;
  }

  private static List<String[]> authoredPayloads() throws IOException {
    List<String[]> payloads = new ArrayList<>();
    Path dir = sourcedataDir();
    if (!Files.isDirectory(dir)) {
      fail("sourcedata directory not found from " + Paths.get("").toAbsolutePath()
          + " — the test cannot silently pass without having read anything");
    }
    for (String file : new String[] { "ETGO_SF_SPEC.xml", "ETGO_SF_ENTITY.xml",
        "ETGO_SF_FIELD.xml" }) {
      Path path = dir.resolve(file);
      if (!Files.isRegularFile(path)) {
        continue;
      }
      Matcher matcher = ROW.matcher(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
      while (matcher.find()) {
        payloads.add(new String[] { file + "#" + matcher.group(1), matcher.group(2) });
      }
    }
    return payloads;
  }

  @Test
  @DisplayName("every authored payload parses and passes its sections' validators")
  void allPayloadsValidate() throws IOException, JSONException {
    McpConfigSections.resetForTests();
    List<String[]> payloads = authoredPayloads();
    List<String> failures = new ArrayList<>();
    for (String[] row : payloads) {
      String where = row[0];
      JSONObject body;
      try {
        body = new JSONObject(row[1]);
      } catch (JSONException e) {
        failures.add(where + ": not valid JSON — " + e.getMessage());
        continue;
      }
      if (!body.has(McpParentSection.NAME)) {
        // Not an error: a future section may be the only thing a row declares. But an empty
        // payload is dead weight in the data, so say so.
        if (body.length() == 0) {
          failures.add(where + ": empty payload — remove the row instead of shipping '{}'");
        }
        continue;
      }
      List<String> problems =
          McpParentSection.declaration().validate(body.getJSONObject(McpParentSection.NAME));
      for (String problem : problems) {
        failures.add(where + ": " + problem);
      }
    }
    assertTrue(failures.isEmpty(), "authored MCP_CONFIG payloads are invalid:\n  "
        + String.join("\n  ", failures));
  }

  @Test
  @DisplayName("every authored verbs and actions section passes its validator")
  void verbsAndActionsSectionsValidate() throws IOException, JSONException {
    McpConfigSections.resetForTests();
    List<String> failures = new ArrayList<>();
    for (String[] row : authoredPayloads()) {
      JSONObject body = new JSONObject(row[1]);
      if (body.has(McpVerbsSection.NAME)) {
        McpVerbsSection.declaration().validate(body.getJSONObject(McpVerbsSection.NAME))
            .forEach(p -> failures.add(row[0] + ": verbs: " + p));
      }
      if (body.has(McpActionsSection.NAME)) {
        McpActionsSection.validate(body.getJSONObject(McpActionsSection.NAME))
            .forEach(p -> failures.add(row[0] + ": actions: " + p));
      }
    }
    assertTrue(failures.isEmpty(), "authored MCP_CONFIG sections are invalid:\n  "
        + String.join("\n  ", failures));
  }

  /**
   * ETP-5715 — the windows whose SPA hides the Attachments tab ({@code window.attachments:false} in
   * {@code schema_forge/artifacts/<window>/decisions.json}) carry {@code MCP_CONFIG.attachments
   * .enabled:false} on their root entity, so the MCP offers no attachments either. The flag lives in
   * two places and nothing derives one from the other yet, so this list is the drift guard: a window
   * that adds {@code attachments:false} must be added here and in the sourcedata, and the reverse.
   */
  private static final Set<String> SPECS_WITHOUT_ATTACHMENTS = new TreeSet<>(List.of(
      "matched-purchase-invoices", "monitor-verifactu", "payment-in", "payment-out", "sii-config",
      "sii-monitor", "tbai-config", "tbai-facturas-enviadas", "verifactu-config"));

  @Test
  @DisplayName("exactly the windows without an Attachments tab in the app declare attachments.enabled:false")
  void attachmentLessWindowsAreDeclared() throws Exception {
    Path dir = sourcedataDir();
    String specXml = new String(Files.readAllBytes(dir.resolve("ETGO_SF_SPEC.xml")),
        StandardCharsets.UTF_8);
    java.util.Map<String, String> specNames = new java.util.HashMap<>();
    Matcher specs = Pattern.compile(
        "<!--([0-9A-F]{32})--><ETGO_SF_SPEC>.*?<NAME><!\\[CDATA\\[(.*?)]]></NAME>", Pattern.DOTALL)
        .matcher(specXml);
    while (specs.find()) {
      specNames.put(specs.group(1), specs.group(2));
    }
    String entityXml = new String(Files.readAllBytes(dir.resolve("ETGO_SF_ENTITY.xml")),
        StandardCharsets.UTF_8);
    Matcher rows = Pattern.compile("<!--[0-9A-F]{32}--><ETGO_SF_ENTITY>(.*?)</ETGO_SF_ENTITY>",
        Pattern.DOTALL).matcher(entityXml);
    Set<String> declared = new TreeSet<>();
    McpConfigSections.resetForTests();
    while (rows.find()) {
      Matcher config = ROW.matcher(rows.group(1));
      if (!config.find()) {
        continue;
      }
      JSONObject body = new JSONObject(config.group(2));
      if (!body.has("attachments")) {
        continue;
      }
      JSONObject section = body.getJSONObject("attachments");
      assertEquals(List.of(), com.etendoerp.go.schemaforge.NeoAttachmentsConfig.validate(section));
      assertFalse(section.getBoolean("enabled"), "only the opt-out is declared in data");
      // Internal pointers (decisions.json) belong in comments, never in data an agent can receive.
      assertFalse(section.getString("reason").contains("decisions.json"));
      Matcher spec = Pattern.compile("<ETGO_SF_SPEC_ID><!\\[CDATA\\[([0-9A-F]{32})]]>")
          .matcher(rows.group(1));
      assertTrue(spec.find());
      declared.add(specNames.get(spec.group(1)));
    }
    assertEquals(SPECS_WITHOUT_ATTACHMENTS, declared);
  }

  /**
   * ETP-5715 — the document entities whose PDF is NOT rendered by the application but uploaded by the
   * user as the main attachment (the two-area / drop-zone layout in the SPA: {@code InvoicePreview}'s
   * purchase branch and {@code GoodsReceiptPreview}: no render, no sourceBlob, no recordUpdated, no
   * print-* in use). They declare {@code MCP_CONFIG.pdf.source:"supplier"}; every other window is
   * "rendered" by default. A window whose preview shows an uploaded document must be added here AND
   * to the sourcedata, or {@code etendo_get_document_pdf} would call its upload stale.
   */
  private static final Set<String> SUPPLIER_PDF_ENTITIES = new TreeSet<>(List.of(
      "goods-receipt/goodsReceipt", "purchase-invoice/header"));

  @Test
  @DisplayName("exactly the upload-layout document entities declare pdf.source:supplier, validly")
  void supplierPdfEntitiesAreDeclared() throws Exception {
    Path dir = sourcedataDir();
    String specXml = new String(Files.readAllBytes(dir.resolve("ETGO_SF_SPEC.xml")),
        StandardCharsets.UTF_8);
    java.util.Map<String, String> specNames = new java.util.HashMap<>();
    Matcher specs = Pattern.compile(
        "<!--([0-9A-F]{32})--><ETGO_SF_SPEC>.*?<NAME><!\\[CDATA\\[(.*?)]]></NAME>", Pattern.DOTALL)
        .matcher(specXml);
    while (specs.find()) {
      specNames.put(specs.group(1), specs.group(2));
    }
    String entityXml = new String(Files.readAllBytes(dir.resolve("ETGO_SF_ENTITY.xml")),
        StandardCharsets.UTF_8);
    Matcher rows = Pattern.compile("<!--[0-9A-F]{32}--><ETGO_SF_ENTITY>(.*?)</ETGO_SF_ENTITY>",
        Pattern.DOTALL).matcher(entityXml);
    Set<String> declared = new TreeSet<>();
    McpConfigSections.resetForTests();
    while (rows.find()) {
      Matcher config = ROW.matcher(rows.group(1));
      if (!config.find()) {
        continue;
      }
      JSONObject body = new JSONObject(config.group(2));
      if (!body.has("pdf")) {
        continue;
      }
      JSONObject section = body.getJSONObject("pdf");
      assertEquals(List.of(), com.etendoerp.go.schemaforge.NeoPdfSourceConfig.validate(section));
      assertEquals("supplier", section.getString("source"), "only the exception is declared in data");
      Matcher spec = Pattern.compile("<ETGO_SF_SPEC_ID><!\\[CDATA\\[([0-9A-F]{32})]]>")
          .matcher(rows.group(1));
      Matcher name = Pattern.compile("<NAME><!\\[CDATA\\[(.*?)]]></NAME>").matcher(rows.group(1));
      assertTrue(spec.find() && name.find());
      declared.add(specNames.get(spec.group(1)) + "/" + name.group(1));
    }
    assertEquals(SUPPLIER_PDF_ENTITIES, declared);
  }

  private static final String SALES_INVOICE_HEADER = "7240CAF07810439B85DE61E70BE8DC0B";
  private static final String PURCHASE_INVOICE_HEADER = "0D654005B02741A29A0491FD5AFEA6E8";
  /** sales-invoice/header and purchase-invoice/header. */
  private static final List<String> INVOICE_HEADERS =
      List.of(SALES_INVOICE_HEADER, PURCHASE_INVOICE_HEADER);
  /** payment-in/finPayment and payment-out/header. */
  private static final List<String> PAYMENT_HEADERS =
      List.of("26AAEE85345F4D549907007E8821360A", "65BF1DFD362B4F1CB6FEC60DB7030CF5");

  static JSONObject payloadOf(String entityId) throws IOException, JSONException {
    for (String[] row : authoredPayloads()) {
      if (row[0].equals("ETGO_SF_ENTITY.xml#" + entityId)) {
        return new JSONObject(row[1]);
      }
    }
    fail("no MCP_CONFIG authored for ETGO_SF_ENTITY " + entityId);
    return null;
  }

  /**
   * ETP-5558, product decision: a bank-initiated (PIS) payment ends in an authorization only a
   * person can give, so no PIS action — and not the PSD2 button either — is offered to or run by
   * an agent. The handler keeps serving them to the SPA; this row is the only thing that keeps them
   * off the MCP, so its content is asserted, not just its shape.
   */
  @Test
  @DisplayName("the invoice headers hide every PIS action and send the APRM button to registerPayment")
  void invoiceHeadersHidePis() throws IOException, JSONException {
    for (String id : INVOICE_HEADERS) {
      JSONObject actions = payloadOf(id).getJSONObject(McpActionsSection.NAME);
      List<String> hidden = new ArrayList<>();
      for (int i = 0; i < actions.getJSONArray(McpActionsSection.KEY_HIDDEN).length(); i++) {
        hidden.add(actions.getJSONArray(McpActionsSection.KEY_HIDDEN).getString(i));
      }
      for (String pis : List.of("pisSupplierAccounts", "pisTemplates", "pisPaymentStatus",
          "cancelPisPayment", "retryPisPayment", "psd2GenerateBankPayment")) {
        assertTrue(hidden.contains(pis), id + " must hide " + pis);
      }
      assertTrue("registerPayment".equals(actions.getJSONObject(McpActionsSection.KEY_REDIRECT)
          .optString("aPRMAddpayment")), id + " must redirect aPRMAddpayment to registerPayment");
      String redirectReason = actions.optString(McpActionsSection.KEY_REDIRECT_REASON, "");
      assertTrue(redirectReason.contains("Add Payment"), id + ": the redirect has its own reason");
      assertFalse(redirectReason.contains("SCA"), id + ": not the PIS reason");
    }
  }

  /**
   * ETP-5692: the AD {@code posted} button is a blind toggle — "Process completed successfully" on
   * a draft while doing nothing. Agents get the declared {@code post} / {@code unpost} actions
   * instead, so the button is hidden, with its own {@code reasons} entry naming both — the PIS
   * {@code reason} of the section stays for the PIS actions.
   */
  @Test
  @DisplayName("the invoice headers hide the posted toggle in favour of post / unpost")
  void invoiceHeadersHideThePostedToggle() throws IOException, JSONException {
    for (String id : INVOICE_HEADERS) {
      JSONObject actions = payloadOf(id).getJSONObject(McpActionsSection.NAME);
      assertTrue(setOf(actions.getJSONArray(McpActionsSection.KEY_HIDDEN)).contains("posted"),
          id + " must hide the posted button");
      String posted = actions.getJSONObject(McpActionsSection.KEY_REASONS).getString("posted");
      assertTrue(posted.contains("post action") && posted.contains("unpost action"),
          id + ": posted's own reason must point at post and unpost: " + posted);
      assertFalse(posted.contains("SCA"), id + ": not the PIS reason");
      String reason = actions.getString(McpActionsSection.KEY_REASON);
      assertTrue(reason.contains("SCA"), id + ": the PIS reason is kept");
      assertFalse(reason.contains("posted"), id + ": the PIS reason says nothing about posted");
    }
  }

  /**
   * ETP-5558: a draft payment header shows only Delete and Confirm in the UI — Save is disabled and
   * no field is editable — so MCP hides update on both payment headers. Delete is hidden too: the
   * generic delete of a draft fails on the payment-detail foreign key (measured live), while the
   * invoice's deletePayment removes it cleanly, and the UI's Eliminar never uses the generic route.
   */
  @Test
  @DisplayName("the payment headers hide create, update and delete through MCP")
  void paymentHeadersHideEveryWrite() throws IOException, JSONException {
    for (String id : PAYMENT_HEADERS) {
      JSONObject verbs = payloadOf(id).getJSONObject(McpVerbsSection.NAME);
      assertFalse(verbs.optBoolean(McpVerbsSection.KEY_CREATE, true), id + " create");
      assertFalse(verbs.optBoolean(McpVerbsSection.KEY_UPDATE, true), id + " update");
      assertFalse(verbs.optBoolean(McpVerbsSection.KEY_DELETE, true), id + " delete");
      String instead = verbs.getString(McpVerbsSection.KEY_INSTEAD);
      assertTrue(instead.contains("paymentId"), id + ": how a draft is edited: " + instead);
      assertTrue(instead.contains("deletePayment"), id + ": how a draft is deleted: " + instead);
    }
  }

  // ── ETP-5558: the rows of 7c363c467 (and b86eade1d), asserted by content ──

  /** sales-invoice and purchase-invoice paymentDetails, then their paymentPlan. */
  private static final List<String> INVOICE_PAYMENT_CHILDREN = List.of(
      "0D5FAC6A352140EABD483FF77539FAEA", "1F7ACD054BF14A148B7A11CD32A8704D",
      "28A6378134714F1E8D3C0869C1C77438", "1343B4002EE14A9896594655770261ED");
  private static final String FA_TRANSACTION = "AF50E181A0094E439C7B46B25A9E38FC";
  private static final String FA_RECONCILIATIONS = "F904BC5EBBCA4C55BE9F62871E2AD171";
  private static final String FA_ACCOUNT = "BE3EAEED69644E56B0ECDCFE4A7E9BC1";

  private static Set<String> setOf(JSONArray array) throws JSONException {
    Set<String> out = new TreeSet<>();
    for (int i = 0; i < array.length(); i++) {
      out.add(array.getString(i));
    }
    return out;
  }

  /** The row resolved exactly as the MCP reads it: through McpEntityConfig, not just its JSON. */
  private static void assertResolvesCleanly(String entityId) throws IOException, JSONException {
    McpConfigSections.resetForTests();
    McpConfigCache.invalidateAll();
    try {
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("spec-of-" + entityId);
      SFEntity entity = mock(SFEntity.class);
      when(entity.getId()).thenReturn(entityId);
      when(entity.getETGOSFSpec()).thenReturn(spec);
      when(entity.get(McpEntityConfig.PROPERTY_MCP_CONFIG))
          .thenReturn(payloadOf(entityId).toString());
      McpEntityConfig.Resolved resolved = McpEntityConfig.forEntity(entity);
      assertTrue(resolved.isUsable(), entityId + ": " + resolved.describeProblems());
    } finally {
      McpConfigCache.invalidateAll();
    }
  }

  private static void assertEveryWriteHidden(JSONObject verbs, String id) {
    assertFalse(verbs.optBoolean(McpVerbsSection.KEY_CREATE, true), id + " create");
    assertFalse(verbs.optBoolean(McpVerbsSection.KEY_UPDATE, true), id + " update");
    assertFalse(verbs.optBoolean(McpVerbsSection.KEY_DELETE, true), id + " delete");
  }

  @Test
  @DisplayName("the payment headers hide the buttons the payment window does not offer and keep"
      + " Confirmar at value P")
  void paymentHeadersExposeOnlyTheUiActions() throws IOException, JSONException {
    Set<String> expectedHidden = new TreeSet<>(List.of("psd2GenerateBankPayment",
        "aPRMAddScheduledpayments", "aprmExecutepayment", "aPRMReversePayment",
        "aPRMReconcilePayment", "aeatsiiSend", "etblkpBulkposting", "posted",
        // The PIS retry/status are a bank integration, limited for agents. eTPRRemovePayment is
        // not here: the UI's Eliminar works at every status but RPVOID/pisLocked.
        "retryPisPayment", "pisPaymentStatus"));
    for (String id : PAYMENT_HEADERS) {
      JSONObject payload = payloadOf(id);
      JSONObject actions = payload.getJSONObject(McpActionsSection.NAME);
      Set<String> hidden = setOf(actions.getJSONArray(McpActionsSection.KEY_HIDDEN));
      assertEquals(expectedHidden, hidden, id);
      for (String ui : List.of("aPRMProcessPayment", "etprReactivatePayment",
          "eTPRRemovePayment")) {
        assertFalse(hidden.contains(ui), id + ": the UI's " + ui + " must stay");
      }
      JSONArray process = actions.getJSONObject(McpActionsSection.KEY_VALUES)
          .getJSONArray("aPRMProcessPayment");
      assertEquals(1, process.length(), id);
      assertEquals("P", process.getString(0), id);
      assertEquals(1, actions.getJSONObject(McpActionsSection.KEY_VALUES).length(), id);
      assertEveryWriteHidden(payload.getJSONObject(McpVerbsSection.NAME), id);
      String reason = actions.getString(McpActionsSection.KEY_REASON);
      String invoiceSpec = id.equals(PAYMENT_HEADERS.get(0)) ? "sales-invoice" : "purchase-invoice";
      assertTrue(reason.contains("etendo_action(spec:'" + invoiceSpec + "'")
          && reason.contains("action:'registerPayment'"), id + ": how one is created: " + reason);
      assertTrue(reason.contains("RPVOID") && reason.contains("pisLocked"),
          id + ": when Eliminar is refused: " + reason);
      assertFalse(reason.contains("deletePayment"), id + ": Eliminar is the way: " + reason);
      assertResolvesCleanly(id);
    }
  }

  @Test
  @DisplayName("the invoices' paymentDetails and paymentPlan are read-only, pointing at"
      + " registerPayment")
  void invoicePaymentChildrenAreReadOnly() throws IOException, JSONException {
    for (String id : INVOICE_PAYMENT_CHILDREN) {
      JSONObject payload = payloadOf(id);
      JSONObject verbs = payload.getJSONObject(McpVerbsSection.NAME);
      assertEveryWriteHidden(verbs, id);
      assertTrue(verbs.getString(McpVerbsSection.KEY_INSTEAD).contains("registerPayment"), id);
      assertFalse(payload.has(McpActionsSection.NAME), id);
      assertResolvesCleanly(id);
    }
  }

  @Test
  @DisplayName("financial-account transaction is read-only, pointing at the account's movement"
      + " actions; its movement buttons are hidden")
  void financialAccountTransactionIsReadOnly() throws IOException, JSONException {
    JSONObject payload = payloadOf(FA_TRANSACTION);
    JSONObject verbs = payload.getJSONObject(McpVerbsSection.NAME);
    assertEveryWriteHidden(verbs, FA_TRANSACTION);
    // ETP-5558: the 405 used to name the financial-account-transactions spec, which the MCP refuses
    // (422, report spec) — a dead end. It now names the account's declared movement actions.
    String instead = verbs.getString(McpVerbsSection.KEY_INSTEAD);
    assertTrue(instead.contains("etendo_action(spec:'financial-account', entity:'account'"), instead);
    for (String action : List.of("createMovement", "updateMovement", "processMovement",
        "reactivateMovement", "deleteMovement", "transferFunds")) {
      assertTrue(instead.contains("'" + action + "'"), action + " in " + instead);
    }
    for (String section : List.of(McpVerbsSection.NAME, McpActionsSection.NAME)) {
      String reason = payload.getJSONObject(section).getString("reason");
      assertFalse(reason.contains("financial-account-transactions"),
          section + " must not send the agent to a spec it cannot call: " + reason);
    }
    // b86eade1d: post/unpost stay; the movement flow owns reactivate/remove.
    assertEquals(new TreeSet<>(List.of("etprReactivateTransaction", "etprRemoveTransaction",
        "posted", "etblkpBulkposting")), setOf(payload.getJSONObject(McpActionsSection.NAME)
        .getJSONArray(McpActionsSection.KEY_HIDDEN)));
    assertResolvesCleanly(FA_TRANSACTION);
  }

  @Test
  @DisplayName("financial-account reconciliations are read-only, pointing at bank-reconciliation")
  void financialAccountReconciliationsAreReadOnly() throws IOException, JSONException {
    JSONObject verbs = payloadOf(FA_RECONCILIATIONS).getJSONObject(McpVerbsSection.NAME);
    assertEveryWriteHidden(verbs, FA_RECONCILIATIONS);
    assertTrue(verbs.getString(McpVerbsSection.KEY_INSTEAD).contains(
        "etendo_action(spec:'bank-reconciliation'"), verbs.getString(McpVerbsSection.KEY_INSTEAD));
    assertResolvesCleanly(FA_RECONCILIATIONS);
  }

  @Test
  @DisplayName("financial-account account hides every Core button and keeps its writes")
  void financialAccountHidesCoreButtonsOnly() throws IOException, JSONException {
    JSONObject payload = payloadOf(FA_ACCOUNT);
    assertEquals(new TreeSet<>(List.of("aPRMImportBankFile", "aPRMMatchTransactions",
        "aPRMMatchTransactionsForce", "aPRMReconcile", "aprmAddMultiplePayments",
        "pSD2GetBankstatement", "pSD2GetConsent", "psd2ReconnectFa", "psd2GetConnections",
        "psd2RefreshConnections")), setOf(payload.getJSONObject(McpActionsSection.NAME)
        .getJSONArray(McpActionsSection.KEY_HIDDEN)));
    assertFalse(payload.has(McpVerbsSection.NAME),
        "create, update and delete stay: the SPA uses them");
    String reason = payload.getJSONObject(McpActionsSection.NAME)
        .optString(McpActionsSection.KEY_REASON, null);
    assertNotNull(reason);
    assertTrue(reason.contains("createMovement") && reason.contains("transferFunds"),
        "the hidden Core buttons point at the movement and transfer actions: " + reason);
    assertTrue(reason.contains("disconnectBank") && reason.contains("deleteBankConnection"),
        "the bank connection is disconnected with the declared actions: " + reason);
    // ETP-5558: Classic's Funds Transfer stays listed, withdrawn, pointing at the agent's action.
    assertEquals("transferFunds", payload.getJSONObject(McpActionsSection.NAME)
        .getJSONObject(McpActionsSection.KEY_REDIRECT).getString("aprmFundsTrans"));
    assertResolvesCleanly(FA_ACCOUNT);
  }

  private static final String PERIOD_DOCUMENTS = "E04EDCE14C7E4E6C865F5C18847819CB";

  @Test
  @DisplayName("the per-document-type open/close the calendar no longer offers is hidden, pointing"
      + " at the period's own")
  void perDocumentOpenCloseIsHidden() throws IOException, JSONException {
    JSONObject actions = payloadOf(PERIOD_DOCUMENTS).getJSONObject(McpActionsSection.NAME);
    assertEquals(new TreeSet<>(List.of("openClose", "processNow")),
        setOf(actions.getJSONArray(McpActionsSection.KEY_HIDDEN)));
    assertTrue(actions.getString(McpActionsSection.KEY_REASON)
        .contains("entity:'periodControl'"), actions.toString());
    assertResolvesCleanly(PERIOD_DOCUMENTS);
  }

  private static final String GOODS_RECEIPT_HEADER = "6102D47FD12A4162858D948885F80544";
  private static final String PURCHASE_ORDER_HEADER = "F1775FF66155444992AC0B1E38CFA3BB";

  private static JSONObject redirectOf(String entityId) throws IOException, JSONException {
    return payloadOf(entityId).getJSONObject(McpActionsSection.NAME)
        .getJSONObject(McpActionsSection.KEY_REDIRECT);
  }

  private static final String PURCHASE_ORDER_HEADER_ID = "F1775FF66155444992AC0B1E38CFA3BB";
  private static final String SALES_ORDER_HEADER_ID = "3B006BDF3E704510B38D814A703AD8A1";
  private static final String SALES_QUOTATION_HEADER_ID = "41A35A9AC111442F86CE1A6296997DCD";

  /**
   * ETP-5714: the classic buttons of an order that have no MCP equivalent are hidden WITH a reason,
   * so the catalogue stops offering a button every call to which fails or does the wrong thing.
   */
  @Test
  @DisplayName("the order headers hide the classic pick / cancel-and-replace buttons, each with a reason")
  void orderHeadersHideClassicButtons() throws IOException, JSONException {
    JSONObject purchase = payloadOf(PURCHASE_ORDER_HEADER_ID).getJSONObject(McpActionsSection.NAME);
    JSONObject sales = payloadOf(SALES_ORDER_HEADER_ID).getJSONObject(McpActionsSection.NAME);

    JSONObject quotation = payloadOf(SALES_QUOTATION_HEADER_ID).getJSONObject(McpActionsSection.NAME);

    // ETP-5714 follow-up: no Etendo GO screen renders Create Lines From (Create_POLines) or Pick
    // from Receipt, so no order or quotation tab leaves them invokable
    assertEquals(Set.of("RM_Pickfromreceipt", "Cancelandreplace", "Confirmcancelandreplace",
        "Create_POLines"), setOf(purchase.getJSONArray(McpActionsSection.KEY_HIDDEN)));
    assertEquals(Set.of("Cancelandreplace", "Confirmcancelandreplace", "Create_POLines",
        "RM_Pickfromreceipt"), setOf(sales.getJSONArray(McpActionsSection.KEY_HIDDEN)));
    assertEquals(Set.of("Create_POLines", "RM_Pickfromreceipt", "Cancelandreplace",
        "Confirmcancelandreplace"), setOf(quotation.getJSONArray(McpActionsSection.KEY_HIDDEN)));
    for (JSONObject actions : List.of(purchase, sales, quotation)) {
      for (String hidden : setOf(actions.getJSONArray(McpActionsSection.KEY_HIDDEN))) {
        assertFalse(actions.getJSONObject(McpActionsSection.KEY_REASONS).optString(hidden, "")
            .isBlank(), "a reason for " + hidden);
      }
      assertFalse(actions.optString(McpActionsSection.KEY_REASON, "").isBlank());
    }
    assertResolvesCleanly(PURCHASE_ORDER_HEADER_ID);
    assertResolvesCleanly(SALES_ORDER_HEADER_ID);
    assertResolvesCleanly(SALES_QUOTATION_HEADER_ID);
  }

  /**
   * ETP-5714: the {@code documentAction} values per status follow core's
   * {@code ActionButtonUtility.docAction} (the real DocAction transitions), not the AD list: an
   * invoice offers CO on a draft and only RE once completed (the screen's Reactivate; no reversal, RC removed
   * in the QA follow-up), an order CO/VO/PR on a draft. Once completed a purchase order offers
   * CL/RE, while a sales order offers only RE: the app never offers Close on a sales order and its
   * pre-hook refuses it (ETP-5704). Advisory data, so its content is asserted here.
   */
  @Test
  @DisplayName("order and invoice headers declare documentAction values per status from core's transitions")
  void documentActionValuesByStatus() throws IOException, JSONException {
    Map<String, Set<String>> completedValues = Map.of(
        PURCHASE_ORDER_HEADER_ID, Set.of("CL", "RE"),
        SALES_ORDER_HEADER_ID, Set.of("RE"));
    for (String id : List.of(PURCHASE_ORDER_HEADER_ID, SALES_ORDER_HEADER_ID)) {
      JSONObject values = valuesByStatusOf(id);
      assertEquals(Set.of("CO", "VO", "PR"), setOf(values.getJSONArray("DR")), id);
      assertEquals(completedValues.get(id), setOf(values.getJSONArray("CO")), id);
      // review W2: final statuses are listed explicitly (empty), never left to be guessed
      assertEquals(0, values.getJSONArray("VO").length(), id);
      assertEquals(0, values.getJSONArray("CL").length(), id);
      assertResolvesCleanly(id);
    }
    // ETP-5704: the sales order's advisory list must never offer Close again.
    assertFalse(setOf(valuesByStatusOf(SALES_ORDER_HEADER_ID).getJSONArray("CO")).contains("CL"),
        "a completed sales order is not offered CL");
    for (String id : INVOICE_HEADERS) {
      JSONObject values = valuesByStatusOf(id);
      assertEquals(Set.of("CO"), setOf(values.getJSONArray("DR")), id);
      assertEquals(Set.of("RE"), setOf(values.getJSONArray("CO")), id);
      for (String status : List.of("DR", "IP", "CO")) {
        assertFalse(setOf(values.getJSONArray(status)).contains("VO"),
            id + ": VO is not offered from " + status);
      }
      JSONObject actions = payloadOf(id).getJSONObject(McpActionsSection.NAME);
      // enforced, not advisory: the DocAction allow-list is exactly what the screen sends
      assertEquals(Set.of("CO", "RE"), setOf(actions.getJSONObject(McpActionsSection.KEY_VALUES)
          .getJSONArray("DocAction")), id);
      assertEquals(0, values.getJSONArray("VO").length(), id);
      assertResolvesCleanly(id);
    }
  }

  private static JSONObject valuesByStatusOf(String entityId) throws IOException, JSONException {
    JSONObject byStatus = payloadOf(entityId).getJSONObject(McpActionsSection.NAME)
        .getJSONObject(McpActionsSection.KEY_VALUES_BY_STATUS).getJSONObject("documentAction");
    assertEquals("documentStatus", byStatus.getString("property"), entityId);
    return byStatus.getJSONObject("values");
  }

  /** The spec ids whose agent prompt explains availability and documentAction (ETP-5714). */
  private static final List<String> AVAILABILITY_PROMPT_SPECS = List.of(
      "09BD7884E7014713A0AD1D2A3395875E", "475953DD812E497BA2131716ABECFC26",
      "02A76D8B4EC842529A537DC56AF0F996", "F4EFD42F68D2437196B06278BBDB33B9",
      "6E4047EE1CD34DEC95D575599A2930A6", "E0AD89F210BD4687AF397206F0735FB5");

  @Test
  @DisplayName("the invoice spec prompts and documentAction prompts no longer advertise RC (void by reversal)")
  void invoicePromptsDoNotAdvertiseReversal() throws IOException {
    for (String file : List.of("ETGO_SF_SPEC.xml", "ETGO_SF_FIELD.xml")) {
      String xml = new String(Files.readAllBytes(sourcedataDir().resolve(file)),
          StandardCharsets.UTF_8);
      assertFalse(xml.contains("void by reversal"), file);
      assertFalse(xml.contains("reverse completed ones with RC"), file);
      assertFalse(xml.contains("RC reverses it"), file);
      assertFalse(xml.contains("VO applies"), file);
      assertFalse(xml.contains("cannot be voided"), file);
      assertFalse(xml.contains("there is no void or reversal"), file);
    }
  }

  @Test
  @DisplayName("the document window prompts explain documentAction, availability and the costing retry")
  void documentPromptsExplainAvailability() throws IOException {
    String xml = new String(Files.readAllBytes(sourcedataDir().resolve("ETGO_SF_SPEC.xml")),
        StandardCharsets.UTF_8);
    for (String specId : AVAILABILITY_PROMPT_SPECS) {
      Matcher m = Pattern.compile("<!--" + specId + "-->\\s*<AGENT_PROMPT><!\\[CDATA\\[(.*?)]]>",
          Pattern.DOTALL).matcher(xml);
      assertTrue(m.find(), "prompt of " + specId);
      String prompt = m.group(1);
      assertTrue(prompt.contains("next default action") && prompt.contains("\"--\""), specId);
      assertTrue(prompt.contains("availableNow") && prompt.contains("unavailableReason"), specId);
      assertTrue(prompt.contains("costNotCalculated") && prompt.contains("retryAfterSeconds"),
          specId);
      assertTrue(prompt.contains("costCalculationStalled") && prompt.contains("do not retry"),
          specId);
    }
  }

  /**
   * ETP-5714: the AD message of a post refused because costing is stalled ships in the module's
   * sourcedata, with the text the service falls back to and an Etendo-format UUID.
   */
  @Test
  @DisplayName("the stalled-costing AD message ships in the module sourcedata")
  void stalledCostingMessageShips() throws IOException {
    String xml = new String(Files.readAllBytes(sourcedataDir().resolve("AD_MESSAGE.xml")),
        StandardCharsets.UTF_8);
    Matcher m = Pattern.compile("<!--([0-9A-F]{32})-->\\s*<VALUE><!\\[CDATA\\[ETGO_CostCalculationStalled]]>"
        + "</VALUE>\\s*<!--\\1-->\\s*<MSGTEXT><!\\[CDATA\\[(.*?)]]>", Pattern.DOTALL).matcher(xml);
    assertTrue(m.find(), "ETGO_CostCalculationStalled");
    assertTrue(m.group(2).contains("stalled") && m.group(2).contains("administrator"), m.group(2));
  }

  /** The four goods-document headers: receipt, shipment, return to vendor, customer return. */
  static List<String> goodsHeaders() {
    return List.of(GOODS_RECEIPT_HEADER, GOODS_SHIPMENT_HEADER, RETURN_TO_VENDOR_HEADER,
        RETURN_MATERIAL_HEADER);
  }

  /**
   * ETP-5714: the AD {@code Posted} button is a blind post/unpost toggle the SPA never uses (it
   * calls the {@code post} / {@code unpost} actions served by {@code DocumentPostingService}); an
   * agent that pressed it got core's raw cost error with no retry guidance. The four goods headers
   * hide it with a reason naming both actions, as the invoices do, and keep their other entries.
   */
  @Test
  @DisplayName("the goods headers hide the posted toggle in favour of post / unpost")
  void goodsHeadersHideThePostedToggle() throws IOException, JSONException {
    for (String id : goodsHeaders()) {
      JSONObject actions = payloadOf(id).getJSONObject(McpActionsSection.NAME);
      assertTrue(setOf(actions.getJSONArray(McpActionsSection.KEY_HIDDEN)).contains("posted"),
          id + " must hide the posted button");
      String posted = actions.getJSONObject(McpActionsSection.KEY_REASONS).getString("posted");
      assertTrue(posted.contains("post action") && posted.contains("unpost action"),
          id + ": posted's own reason must name post and unpost: " + posted);
      assertTrue(actions.has(McpActionsSection.KEY_REDIRECT), id + ": its redirects are kept");
      assertResolvesCleanly(id);
    }
  }

  /**
   * ETP-5714: {@code etblkpBulkposting} (the Bulk Posting button) has no usable process on
   * M_InOut, yet shipments and both returns listed it as invokable and answered 405 on use. It is
   * hidden with a reason that names post / unpost; the receipt does not include the field, so it
   * is not listed there.
   */
  @Test
  @DisplayName("every M_InOut and C_Invoice header hides the Bulk Posting button with a reason")
  void goodsHeadersHideBulkPosting() throws IOException, JSONException {
    // The catalogue lists every button column of the table, whatever ISINCLUDED says (the receipt and
    // the invoices list it as discarded), so each header on those two tables must hide it.
    for (String id : List.of(GOODS_RECEIPT_HEADER, GOODS_SHIPMENT_HEADER, RETURN_TO_VENDOR_HEADER,
        RETURN_MATERIAL_HEADER, SALES_INVOICE_HEADER, PURCHASE_INVOICE_HEADER)) {
      JSONObject actions = payloadOf(id).getJSONObject(McpActionsSection.NAME);
      assertTrue(setOf(actions.getJSONArray(McpActionsSection.KEY_HIDDEN))
          .contains("etblkpBulkposting"), id + " must hide the Bulk Posting button");
      String reason = actions.getJSONObject(McpActionsSection.KEY_REASONS)
          .getString("etblkpBulkposting");
      assertTrue(reason.contains("post action") && reason.contains("unpost action"), reason);
      assertTrue(setOf(actions.getJSONArray(McpActionsSection.KEY_HIDDEN)).contains("posted"),
          id + ": the posted toggle stays hidden");
      assertResolvesCleanly(id);
    }
  }

  /**
   * The redirect target is not validated at runtime, so a typo would silently hand agents a useless
   * hint. Assert each target against the contracts the entity's handler really declares.
   */
  private static void assertTargetsAreDeclared(String entityId, Set<String> declared)
      throws IOException, JSONException {
    JSONObject redirect = redirectOf(entityId);
    java.util.Iterator<?> keys = redirect.keys();
    while (keys.hasNext()) {
      String button = (String) keys.next();
      assertTrue(declared.contains(redirect.getString(button)), entityId + ": " + button
          + " redirects to '" + redirect.getString(button) + "', which the handler does not declare "
          + declared);
    }
  }

  @Test
  @DisplayName("goods-receipt redirects the classic invoice-from-receipt button to"
      + " createPurchaseInvoice and Create Lines From to importOrderLines, which its handler"
      + " declares")
  void goodsReceiptRedirectsInvoiceFromShipment() throws IOException, JSONException {
    JSONObject redirect = redirectOf(GOODS_RECEIPT_HEADER);
    assertEquals(2, redirect.length());
    assertEquals("createPurchaseInvoice", redirect.getString("invoicefromshipment"));
    assertEquals("importOrderLines", redirect.getString("CreateFrom"));
    JSONObject actions = payloadOf(GOODS_RECEIPT_HEADER).getJSONObject(McpActionsSection.NAME);
    assertFalse(actions.optString(McpActionsSection.KEY_REDIRECT_REASON, "").isBlank());
    assertTrue(actions.getJSONObject(McpActionsSection.KEY_REASONS).getString("CreateFrom")
        .contains("importOrderLines"), "Create Lines From has its own reason, naming the action");
    assertTargetsAreDeclared(GOODS_RECEIPT_HEADER,
        new GoodsReceiptHeaderHandler().actionContracts().keySet());
    assertResolvesCleanly(GOODS_RECEIPT_HEADER);
  }

  private static final String RETURN_TO_VENDOR_HEADER = "40D5EE72A0F24464BABD21886059F3D8";
  private static final String RETURN_MATERIAL_HEADER = "963A22BC87B144E18470530BA256FA78";

  /**
   * ETP-5712: both return headers carry the classic M_InOut buttons that would invoice from the
   * return — {@code GenerateTo} (AD_Process 154, "Generate Invoice from Shipment", hidden in the
   * tab) and {@code Invoicefromshipment} (no process). Neither is an agent path: each is redirected
   * to the header's own {@code createReturnInvoice}, with its own reason, keyed by AD column name
   * (the one spelling the listing and the call-time refusal both accept).
   */
  private static void assertClassicInvoiceButtonsRedirectToCreateReturnInvoice(JSONObject actions,
      JSONObject redirect) throws JSONException {
    assertEquals("createReturnInvoice", redirect.getString("GenerateTo"));
    assertEquals("createReturnInvoice", redirect.getString("Invoicefromshipment"));
    JSONObject reasons = actions.getJSONObject(McpActionsSection.KEY_REASONS);
    for (String button : List.of("GenerateTo", "Invoicefromshipment")) {
      String reason = reasons.optString(button, "");
      assertFalse(reason.isBlank(), "a reason for " + button);
      assertTrue(reason.contains("createReturnInvoice"), button + ": " + reason);
      assertTrue(reason.contains("factura rectificativa"), button + ": " + reason);
    }
  }

  @Test
  @DisplayName("return-to-vendor-shipment redirects the classic Pick/Edit Lines button, which has no"
      + " process, to importReceiptLines, which its handler declares")
  void returnToVendorRedirectsPickEditToImportReceiptLines() throws IOException, JSONException {
    JSONObject redirect = redirectOf(RETURN_TO_VENDOR_HEADER);
    assertEquals(3, redirect.length());
    assertEquals("importReceiptLines", redirect.getString("RM_Shipment_Pickedit"));
    JSONObject actions = payloadOf(RETURN_TO_VENDOR_HEADER).getJSONObject(McpActionsSection.NAME);
    assertFalse(actions.optString(McpActionsSection.KEY_REDIRECT_REASON, "").isBlank());
    assertClassicInvoiceButtonsRedirectToCreateReturnInvoice(actions, redirect);
    assertTargetsAreDeclared(RETURN_TO_VENDOR_HEADER,
        new ReturnToVendorShipmentHeaderHandler().actionContracts().keySet());
    assertResolvesCleanly(RETURN_TO_VENDOR_HEADER);
  }

  @Test
  @DisplayName("return-material-receipt redirects the classic Pick/Edit Lines button, which has no"
      + " process, to importShipmentLines, which its handler declares")
  void returnMaterialReceiptRedirectsPickEditToImportShipmentLines()
      throws IOException, JSONException {
    JSONObject redirect = redirectOf(RETURN_MATERIAL_HEADER);
    assertEquals(3, redirect.length());
    assertEquals("importShipmentLines", redirect.getString("RM_Receipt_PickEdit"));
    JSONObject actions = payloadOf(RETURN_MATERIAL_HEADER).getJSONObject(McpActionsSection.NAME);
    assertFalse(actions.optString(McpActionsSection.KEY_REDIRECT_REASON, "").isBlank());
    assertClassicInvoiceButtonsRedirectToCreateReturnInvoice(actions, redirect);
    assertTargetsAreDeclared(RETURN_MATERIAL_HEADER,
        new ReturnMaterialReceiptHeaderHandler().actionContracts().keySet());
    assertResolvesCleanly(RETURN_MATERIAL_HEADER);
  }

  @Test
  @DisplayName("purchase-order redirects its classic receive/invoice buttons, each with a reason,"
      + " to actions its handler declares")
  void purchaseOrderRedirectsClassicButtons() throws IOException, JSONException {
    JSONObject redirect = redirectOf(PURCHASE_ORDER_HEADER);
    assertEquals(2, redirect.length());
    assertEquals("createGoodsReceipt", redirect.getString("RM_ReceiveMaterials"));
    assertEquals("createPurchaseInvoice", redirect.getString("RM_CreateInvoice"));
    JSONObject actions = payloadOf(PURCHASE_ORDER_HEADER).getJSONObject(McpActionsSection.NAME);
    JSONObject reasons = actions.getJSONObject(McpActionsSection.KEY_REASONS);
    for (String key : List.of("RM_ReceiveMaterials", "RM_CreateInvoice")) {
      assertFalse(reasons.optString(key, "").isBlank(), "a reason for " + key);
    }
    assertFalse(actions.optString(McpActionsSection.KEY_REDIRECT_REASON, "").isBlank());
    Set<String> declared = new PurchaseOrderHeaderHandler().actionContracts().keySet();
    assertTrue(declared.containsAll(Set.of("createGoodsReceipt", "createPurchaseInvoice")), declared
        .toString());
    assertTargetsAreDeclared(PURCHASE_ORDER_HEADER, declared);
    assertResolvesCleanly(PURCHASE_ORDER_HEADER);
  }

  /**
   * The button columns of the classic purchase-order header, by the DB column name — the one
   * spelling both the listing matcher (name or column) and the call-time refusal (action, column or
   * DAL property) accept. The module carries no source of truth for them: ETGO_SF_FIELD has no row
   * for these buttons and AD_COLUMN of the core is not shipped here, so the literals are asserted.
   * {@code rMCreateInvoice} (the DAL property spelling) matched neither in the listing and silently
   * left the redirect unapplied; this set would have failed it.
   */
  private static final Set<String> PURCHASE_ORDER_BUTTON_COLUMNS =
      new TreeSet<>(List.of("RM_ReceiveMaterials", "RM_CreateInvoice"));

  /** JAVA_QUALIFIER of every ETGO_SF_FIELD row that belongs to the entity (its schema names). */
  private static Set<String> fieldQualifiersOf(String entityId) throws IOException {
    String xml = new String(Files.readAllBytes(sourcedataDir().resolve("ETGO_SF_FIELD.xml")),
        StandardCharsets.UTF_8);
    Matcher owner = Pattern.compile(
        "<!--([0-9A-Fa-f]{32})-->\\s*<ETGO_SF_ENTITY_ID><!\\[CDATA\\[" + entityId + "]]>").matcher(xml);
    Set<String> rows = new TreeSet<>();
    while (owner.find()) {
      rows.add(owner.group(1));
    }
    Set<String> qualifiers = new TreeSet<>();
    Matcher qualifier = Pattern.compile(
        "<!--([0-9A-Fa-f]{32})-->\\s*<JAVA_QUALIFIER><!\\[CDATA\\[(.*?)]]>").matcher(xml);
    while (qualifier.find()) {
      if (rows.contains(qualifier.group(1))) {
        qualifiers.add(qualifier.group(2));
      }
    }
    return qualifiers;
  }

  private static Set<String> keysOf(JSONObject object) {
    Set<String> keys = new TreeSet<>();
    java.util.Iterator<?> it = object.keys();
    while (it.hasNext()) {
      keys.add((String) it.next());
    }
    return keys;
  }

  @Test
  @DisplayName("the purchase-order redirect, hidden buttons and reasons are keyed by DB column names")
  void purchaseOrderKeysAreTheButtonColumns() throws IOException, JSONException {
    JSONObject actions = payloadOf(PURCHASE_ORDER_HEADER).getJSONObject(McpActionsSection.NAME);
    assertEquals(PURCHASE_ORDER_BUTTON_COLUMNS,
        keysOf(actions.getJSONObject(McpActionsSection.KEY_REDIRECT)));
    // ETP-5714: the section also hides three classic buttons, each with its own reason, so the
    // reasons are the redirected buttons' plus the hidden ones'.
    Set<String> hidden = new TreeSet<>(setOf(actions.getJSONArray(McpActionsSection.KEY_HIDDEN)));
    Set<String> reasoned = new TreeSet<>(PURCHASE_ORDER_BUTTON_COLUMNS);
    reasoned.addAll(hidden);
    assertEquals(reasoned, keysOf(actions.getJSONObject(McpActionsSection.KEY_REASONS)));
    assertEquals(Set.of("RM_Pickfromreceipt", "Cancelandreplace", "Confirmcancelandreplace",
        "Create_POLines"), hidden);
  }

  @Test
  @DisplayName("the goods-receipt redirect keys are the schema name of a real field of that entity,"
      + " or the AD column of its Create Lines From button")
  void goodsReceiptKeyIsARealButtonField() throws IOException, JSONException {
    Set<String> qualifiers = fieldQualifiersOf(GOODS_RECEIPT_HEADER);
    for (String key : keysOf(redirectOf(GOODS_RECEIPT_HEADER))) {
      assertTrue(qualifiers.contains(key) || INOUT_BUTTON_COLUMNS.contains(key),
          key + " is not a button field of the goods receipt " + qualifiers);
    }
  }

  /**
   * The DB column of the M_InOut Create Lines From button (field {@code createLinesFrom},
   * curated discarded). The redirect is keyed by the column, the one spelling the listing and the
   * call both accept; the module ships no AD_COLUMN to read it from, so the literal is asserted.
   */
  private static final Set<String> INOUT_BUTTON_COLUMNS = Set.of("CreateFrom");

  @Test
  @DisplayName("the redirect texts of every redirecting row do not end with a period (the framework"
      + " appends its own)")
  void redirectTextsHaveNoTrailingPeriod() throws IOException, JSONException {
    for (String id : List.of(GOODS_RECEIPT_HEADER, PURCHASE_ORDER_HEADER, GOODS_SHIPMENT_HEADER,
        PURCHASE_INVOICE_HEADER, SALES_INVOICE_HEADER)) {
      JSONObject actions = payloadOf(id).getJSONObject(McpActionsSection.NAME);
      List<String> texts = new ArrayList<>();
      texts.add(actions.getString(McpActionsSection.KEY_REDIRECT_REASON));
      JSONObject reasons = actions.optJSONObject(McpActionsSection.KEY_REASONS);
      if (reasons != null) {
        for (String key : keysOf(reasons)) {
          texts.add(reasons.getString(key));
        }
      }
      for (String text : texts) {
        assertFalse(text.trim().endsWith("."), id + ": '" + text + "'");
      }
    }
  }

  /** ETGO_SF_ENTITY of goods-shipment/goodsShipment. */
  private static final String GOODS_SHIPMENT_HEADER = "6882D245E7964685A14DCF0ACD66FB13";

  /**
   * The classic button bypasses every guard of {@code createDraftInvoice} (status check, nothing
   * pending, shipment scoping, partial lines), so the shipment redirects it too. The key is the AD
   * column name, the only spelling both the listing and the call matcher accept.
   * Reads the exported sourcedata: it needs {@code export.database} to have run for this row.
   */
  @Test
  @DisplayName("goods-shipment redirects the classic Invoicefromshipment button to createDraftInvoice"
      + " and Create Lines From to importOrderLines, which its handler declares")
  void goodsShipmentRedirectsInvoiceFromShipment() throws IOException, JSONException {
    JSONObject redirect = redirectOf(GOODS_SHIPMENT_HEADER);
    assertEquals(2, redirect.length());
    assertEquals("createDraftInvoice", redirect.getString("Invoicefromshipment"));
    assertEquals("importOrderLines", redirect.getString("CreateFrom"));
    JSONObject actions = payloadOf(GOODS_SHIPMENT_HEADER).getJSONObject(McpActionsSection.NAME);
    assertTrue(actions.getJSONObject(McpActionsSection.KEY_REASONS).getString("CreateFrom")
        .contains("importOrderLines"), "Create Lines From has its own reason, naming the action");
    assertFalse(actions.optString(McpActionsSection.KEY_REDIRECT_REASON, "").isBlank());
    assertFalse(actions.optString(McpActionsSection.KEY_REASON, "").isBlank());
    assertTargetsAreDeclared(GOODS_SHIPMENT_HEADER,
        new GoodsShipmentHeaderHandler().actionContracts().keySet());
    assertResolvesCleanly(GOODS_SHIPMENT_HEADER);
  }

  @Test
  @DisplayName("invoicefromshipment is redirected only on the two goods documents (to their own"
      + " invoice action) and on the two return headers (to createReturnInvoice, ETP-5712)")
  void invoiceFromShipmentRedirectIsOnlyOnTheGoodsAndReturnDocuments()
      throws IOException, JSONException {
    for (String[] row : authoredPayloads()) {
      JSONObject body = new JSONObject(row[1]);
      JSONObject actions = body.optJSONObject(McpActionsSection.NAME);
      JSONObject redirect = actions == null ? null
          : actions.optJSONObject(McpActionsSection.KEY_REDIRECT);
      if (redirect == null) {
        continue;
      }
      for (String key : keysOf(redirect)) {
        if (key.equalsIgnoreCase("invoicefromshipment")) {
          boolean goods = row[0].equals("ETGO_SF_ENTITY.xml#" + GOODS_RECEIPT_HEADER)
              || row[0].equals("ETGO_SF_ENTITY.xml#" + GOODS_SHIPMENT_HEADER);
          boolean returns = row[0].equals("ETGO_SF_ENTITY.xml#" + RETURN_TO_VENDOR_HEADER)
              || row[0].equals("ETGO_SF_ENTITY.xml#" + RETURN_MATERIAL_HEADER);
          assertTrue(goods || returns,
              "invoicefromshipment must not be redirected outside the goods and return documents: "
                  + row[0]);
          if (returns) {
            assertEquals("createReturnInvoice", redirect.getString(key), row[0]);
          }
        }
      }
    }
  }

  // ── ETP-5713: the classic "create lines from" buttons point at the import actions ──

  /**
   * The classic Create From buttons have no process an agent can run (the OBUIAPP pick-and-edit
   * ones are not invocable, the plain CreateFrom has none). Each is redirected, with its own
   * reason, to the import action that does the same through the MCP, and the target is one the
   * entity's handler really declares (a redirect target is not validated at runtime).
   */
  @Test
  @DisplayName("purchase-invoice redirects its three Create From buttons to the import actions it"
      + " declares, keeping the Add Payment redirect")
  void purchaseInvoiceRedirectsTheCreateFromButtons() throws IOException, JSONException {
    assertInvoiceCreateFromRedirects(PURCHASE_INVOICE_HEADER, "importReceiptLines",
        new PurchaseInvoiceHeaderHandler().actionContracts().keySet());
  }

  @Test
  @DisplayName("sales-invoice redirects its three Create From buttons to the import actions it"
      + " declares, keeping the Add Payment redirect")
  void salesInvoiceRedirectsTheCreateFromButtons() throws IOException, JSONException {
    assertInvoiceCreateFromRedirects(SALES_INVOICE_HEADER, "importShipmentLines",
        new SalesInvoiceHeaderHandler().actionContracts().keySet());
  }

  private static void assertInvoiceCreateFromRedirects(String id, String inoutAction,
      Set<String> declared) throws IOException, JSONException {
    JSONObject redirect = redirectOf(id);
    assertEquals(4, redirect.length(), redirect.toString());
    assertEquals("registerPayment", redirect.getString("aPRMAddpayment"));
    assertEquals("importOrderLines", redirect.getString("Createfromorders"));
    assertEquals(inoutAction, redirect.getString("Createfrominouts"));
    assertEquals("importOrderLines", redirect.getString("CreateFrom"));
    JSONObject reasons = payloadOf(id).getJSONObject(McpActionsSection.NAME)
        .getJSONObject(McpActionsSection.KEY_REASONS);
    for (String button : List.of("Createfromorders", "Createfrominouts", "CreateFrom")) {
      assertTrue(reasons.optString(button, "").contains(redirect.getString(button)),
          id + ": " + button + " needs its own reason naming " + redirect.getString(button));
    }
    assertTrue(reasons.has("posted"), "the posted reason is kept");
    assertTargetsAreDeclared(id, declared);
    assertResolvesCleanly(id);
  }

  @Test
  @DisplayName("the authored rows are actually present — the regex has not stopped matching")
  void payloadsAreFound() throws IOException {
    // Without this, a change to the export format (or a bad regex) would turn the test above into
    // a test of the empty list, which passes forever and guards nothing.
    assertFalse(authoredPayloads().isEmpty(),
        "no MCP_CONFIG rows matched in sourcedata; either they were all removed or ROW no longer "
            + "matches the exported format");
  }

  @Test
  @DisplayName("no payload relaxes a write verb, whatever the sourcedata says")
  void noWriteRelaxationShipped() throws IOException, JSONException {
    // The validator already refuses optionalFor:["create"], so this is belt and braces against a
    // row that was authored before that rule existed, or hand-edited past it. A child written
    // without its parent is an orphan; shipping data that permits it is worse than a code bug,
    // because it looks like a deliberate configuration decision.
    McpConfigSections.resetForTests();
    for (String[] row : authoredPayloads()) {
      JSONObject body = new JSONObject(row[1]);
      if (!body.has(McpParentSection.NAME)) {
        continue;
      }
      for (String verb : McpParentSection
          .optionalVerbs(body.getJSONObject(McpParentSection.NAME))) {
        assertFalse(McpParentSection.VERB_CREATE.equals(verb)
            || McpParentSection.VERB_UPDATE.equals(verb)
            || McpParentSection.VERB_DELETE.equals(verb),
            row[0] + " relaxes the write verb '" + verb + "'");
      }
    }
  }
}
