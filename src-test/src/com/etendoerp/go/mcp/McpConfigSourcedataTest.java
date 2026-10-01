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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
  private static Path sourcedataDir() {
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

  /** sales-invoice/header and purchase-invoice/header. */
  private static final List<String> INVOICE_HEADERS =
      List.of("7240CAF07810439B85DE61E70BE8DC0B", "0D654005B02741A29A0491FD5AFEA6E8");
  /** payment-in/finPayment and payment-out/header. */
  private static final List<String> PAYMENT_HEADERS =
      List.of("26AAEE85345F4D549907007E8821360A", "65BF1DFD362B4F1CB6FEC60DB7030CF5");

  private static JSONObject payloadOf(String entityId) throws IOException, JSONException {
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
