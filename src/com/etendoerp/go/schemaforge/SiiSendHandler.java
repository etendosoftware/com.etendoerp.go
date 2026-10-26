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

package com.etendoerp.go.schemaforge;

import java.util.Map;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.util.NeoLanguage;

/**
 * NeoHandler delegate for the legacy SII button on Sales Invoice.
 *
 * <p>Classic Etendo wires {@code Em_Aeatsii_Send} to the client-side process
 * {@code OB.AEATSII.send}, which in turn calls the server-side OBUIAPP action
 * handler {@code org.openbravo.module.sii.process.MultiEnvioFactura}. NEO cannot
 * execute that client-side hook directly, so this handler invokes the underlying
 * server-side action handler with the same payload shape used by the classic UI.
 *
 * <p>ETP-5272: {@code MultiEnvioFactura} always sends communication type {@code A0}
 * ("alta" / new registration). That is wrong for an invoice pending a registry-error
 * correction ({@link Invoice#isAeatsiiErrorRegistral()} {@code = true}) — AEAT expects the
 * correction/modification envelope, communication type {@code A1}. Whenever that flag is
 * set, this handler routes to the classic "Modificar" action,
 * {@code org.openbravo.module.sii.process.MultiInvoiceSIIModification}, regardless of the
 * invoice's AEAT error code. Like {@code MultiEnvioFactura}, it extends
 * {@code BaseActionHandler}, so it is invoked through the same
 * {@link NeoProcessService#executeObuiappClass} bridge.
 */
@Named("sii-send")
public class SiiSendHandler extends AbstractLegacyInvoiceActionHandler {

  static final String ACTION_NAME = "EM_Aeatsii_Send";
  static final String ACTION_NAME_LEGACY = "Em_aeatsii_send";
  static final String ACTION_NAME_QUALIFIER = "aeatsiiSend";
  private static final String PROCESS_ID = "2ECF46DAAEEB486EAF79D3594D50DE5F";
  private static final String PROCESS_CLASS = "org.openbravo.module.sii.process.MultiEnvioFactura";
  private static final String MODIFICATION_PROCESS_ID = "F5CCFE8DCAC04FBD9B4A217C6383032B";
  private static final String MODIFICATION_PROCESS_CLASS =
      "org.openbravo.module.sii.process.MultiInvoiceSIIModification";

  @Override
  protected NeoResponse executeAction(String recordId) throws Exception {
    Invoice invoice = OBDal.getInstance().get(Invoice.class, recordId);
    // ETP-5745 bilingual follow-up: captured BEFORE the classic bridge call, not after — see
    // remapDriftedFieldLabels's javadoc for why.
    String requestLanguage = NeoLanguage.currentCode();

    if (invoice != null && Boolean.TRUE.equals(invoice.isAeatsiiErrorRegistral())) {
      return executeRegistralModification(recordId, invoice, requestLanguage);
    }

    NeoResponse response = NeoProcessService.executeObuiappClass(PROCESS_CLASS, PROCESS_ID,
        buildInvoiceSendParams(recordId, invoice));
    return remapDriftedFieldLabels(normalizeErrorShape(response), requestLanguage);
  }

  /**
   * Routes a registry-error resend to {@code MultiInvoiceSIIModification}, the classic module's
   * "Modificar" action (communication type {@code A1}), whenever
   * {@link Invoice#isAeatsiiErrorRegistral()} is {@code true} — regardless of the invoice's
   * AEAT error code. Like {@code MultiEnvioFactura}, this is a real {@code BaseActionHandler},
   * so it is invoked through the same {@link NeoProcessService#executeObuiappClass} bridge.
   *
   * @param requestLanguage the language {@link #executeAction} captured BEFORE this call
   */
  private static NeoResponse executeRegistralModification(String recordId, Invoice invoice,
      String requestLanguage) throws Exception {
    NeoResponse response = NeoProcessService.executeObuiappClass(MODIFICATION_PROCESS_CLASS,
        MODIFICATION_PROCESS_ID, buildInvoiceSendParams(recordId, invoice));
    return remapDriftedFieldLabels(normalizeErrorShape(response), requestLanguage);
  }

  /**
   * Builds the payload shape both {@code MultiEnvioFactura} and
   * {@code MultiInvoiceSIIModification} expect: a single-invoice {@code ids} array plus the
   * invoice's organization id, alongside the generic {@code recordId}/{@code inpRecordId} keys
   * {@link NeoProcessService#executeObuiappClass} forwards for record-context resolution.
   */
  private static JSONObject buildInvoiceSendParams(String recordId, Invoice invoice)
      throws JSONException {
    JSONObject params = new JSONObject();
    params.put("recordId", recordId);
    params.put("inpRecordId", recordId);
    params.put("orgid", resolveOrganizationId(invoice));
    JSONArray ids = new JSONArray();
    ids.put(recordId);
    params.put("ids", ids);
    return params;
  }

  /**
   * Ensures every error response carries a top-level {@code message} field.
   *
   * <p>{@code MultiEnvioFactura} (the classic SII sending process) reports a failed
   * single-invoice send (e.g. a missing required field caught by
   * {@code SIIUtils.invoicePreviousValidations}) through
   * {@link NeoProcessService#translateObuiappResult}, which already produces a body with a
   * top-level {@code message}. But any exception the classic process throws instead (a bug in
   * its own validation/classification code, a SOAP/network failure, etc.) is caught generically
   * by {@link NeoProcessService#executeObuiappClass} and returned via
   * {@link NeoResponse#error(int, String)}, whose body nests the text under {@code error.message}.
   *
   * <p>The frontend ({@code SifSendingModal.jsx}) only reads {@code response.message} /
   * {@code message} off the on-demand send response — it does not know about the
   * {@code error.message} shape used by generic CRUD/process error handling elsewhere. Without
   * this normalization, any such exception would silently degrade to a generic "HTTP 500" in the
   * UI instead of showing the real cause, which is exactly the "sent but nothing reached AEAT"
   * symptom this handler exists to prevent.
   *
   * <p>As of the fix generalizing this normalization into
   * {@link NeoResponse#ensureTopLevelMessage(NeoResponse)}, {@code NeoProcessService}'s
   * {@code executeObuiappClass} already applies it before returning, so this call is now
   * idempotent (the body already has a top-level {@code message} by the time it gets here).
   * Kept as a thin delegate — rather than removed — so this handler's intent stays
   * self-documenting and unaffected by future changes to where the shared service applies
   * its own normalization.
   *
   * <p>Package-private and static so it can be unit tested directly against a synthetic
   * {@link NeoResponse}, without needing a live AEAT connection or DB access.
   */
  static NeoResponse normalizeErrorShape(NeoResponse response) {
    return NeoResponse.ensureTopLevelMessage(response);
  }

  /**
   * Classic fixed sentence → its {@code NeoHints} catalog key, for the two of the three fixed
   * sentences of {@code SIIUtils.invoicePreviousValidations} (org.openbravo.module.sii, classic
   * SII module — NOT touched by this fix: its text is correct for Classic's own UI, and other
   * classic callers may depend on the exact wording) whose named field has a current GO label
   * that differs from the classic one (ETP-5745). The third fixed-field sentence in that same
   * validation block, "Descripción SII", is NOT remapped: GO's label for
   * {@code aeatsiiDescripcionSii} ({@code tools/app-shell/src/locales/es_ES.json} →
   * {@code sifDataTabs.field.siiDescription}) is the identical string, so the classic Spanish
   * sentence is already correct as-is.
   *
   * <p><b>ETP-5745 bilingual correction.</b> The first version of this fix did a plain substring
   * swap of the field NAME inside the classic (always-Spanish) sentence, leaving the REST of the
   * sentence in Spanish even for an English-locale GO session. Detection of which classic
   * sentence arrived is UNCHANGED (classic always emits Spanish regardless of locale, so matching
   * against the Spanish text is still correct) — but once matched, the ENTIRE message is now
   * replaced with a GO-authored sentence in the request's resolved language, via the same
   * {@code NeoHints} catalog (see {@code messages/neo_hints_es.properties} /
   * {@code neo_hints_en.properties}) {@link FinancialAccountBankConnectionSupport} already uses
   * for hand-authored bilingual backend text, resolved with
   * {@link com.etendoerp.go.schemaforge.util.NeoLanguage#currentCode()} — the GO locale
   * {@code NeoAuthenticator} already applied to this request from its {@code Accept-Language}
   * header (independent of, and more reliable than, {@code AD_User.AD_Language}: the SPA's
   * language switcher is purely client-side and never writes that field back). This handler is
   * also MCP-reachable (a {@code NeoEndpointType.ACTION} handler, same as any {@code etendo_action}
   * call): there {@code NeoLanguage.currentCode()} resolves from the token-owning user's own
   * {@code OBContext}, not a per-request {@code Accept-Language} header — the same established
   * fallback {@code McpToolTitles} already uses for MCP tool-title localization, so this is
   * intentional existing behavior on that channel, not a gap.
   *
   * <p><b>ETP-5745 — language must be captured BEFORE {@code executeObuiappClass} (2nd
   * correction, live-verified regression).</b> The first bilingual version called
   * {@code NeoLanguage.currentCode()} HERE, i.e. AFTER {@code executeObuiappClass} had already run
   * the classic {@code MultiEnvioFactura}/{@code MultiInvoiceSIIModification} handler. A live
   * English-locale session still got the Spanish {@code NeoHints} text, which rules out the
   * "no language resolved, fell back to English" failure mode — {@link NeoHints#text} falls back
   * to English on a missing/unresolvable language, so to end up with SPANISH text,
   * {@code OBContext}'s language must have been ACTIVELY overwritten to {@code es_*} by
   * something inside that classic call, not merely lost. Investigated and RULED OUT:
   * {@code OBContext.setAdminMode()}/{@code restorePreviousMode()} (both {@code executeObuiappClass}'s
   * own wrapping and the classic handlers' many internal {@code setAdminMode(true)} pairs) —
   * read against core's own source, both are no-ops on the context's identity/fields whenever a
   * non-null, non-singleton context already exists, which is the case here (the per-request
   * context {@code EnvironmentRequestAuthenticator} built and set the language on). No explicit
   * {@code OBContext.setOBContext}/{@code .setLanguage} call was found in
   * {@code MultiEnvioFactura}/{@code MultiInvoiceSIIModification} either. The exact line inside
   * the classic bridge that performs the overwrite was NOT pinpointed (no live backend available
   * to add a diagnostic log and confirm) — likely a classic/legacy CDI or session-scoped
   * mechanism reasserting the authenticated user's own stored {@code AD_User.AD_Language}
   * somewhere in the {@code BaseActionHandler} invocation chain, triggered lazily. <b>Fix:</b>
   * {@link #executeAction} now calls {@code NeoLanguage.currentCode()} itself, BEFORE invoking
   * {@code executeObuiappClass}, and passes the captured value down — this still resolves the
   * language through the one canonical {@code NeoLanguage} function (not a raw
   * {@code Accept-Language} re-read, which would bypass it), only at a point in the SAME request
   * the value is known-good. No other caller of {@code NeoLanguage.currentCode()} in this module
   * crosses a classic-process boundary between authentication and the read, so there was no prior
   * instance of this exact "capture before a classic call" combination to copy — grepped for one
   * before writing this; it does not exist. If the exact clobbering mechanism is found later, this
   * capture-before approach should still be the right fix (it is correct regardless of root cause,
   * and is the minimal change), not something to revert.
   *
   * <p><b>Why not {@code AD_Message}/{@code AD_Message_Trl}:</b> this module's own {@code ETGO_*}
   * AD_Message catalog (e.g. {@code ETGO_InsufficientStockLine}) exists as STABLE IDENTITY TOKENS
   * the SPA re-translates client-side via {@code backendErrors.js} — their {@code AD_Message_Trl}
   * rows are mechanically copied from the English base, never hand-translated, so resolving one
   * server-side would render English regardless of locale. That route only works for callers that
   * read {@code messageKeys} and translate client-side; {@code SifSendingModal.jsx} (the only
   * consumer of this handler's response) reads {@code response.message} directly and has no such
   * step, so the replacement text must already be correct when it leaves this handler.
   *
   * <p>Keep the GO field-label citations in the {@code NeoHints} catalog comments byte-for-byte
   * equal to their source (the "Referencia del proveedor" case was wrong once already — see
   * {@code docs/generated-custom-windows/purchase-invoice.md} for the verified source and the two
   * guesses it supersedes); a tester/reviewer can grep that source on a rename and update both
   * sides together.
   */
  private static final Map<String, String> DRIFTED_CLASSIC_SENTENCES = Map.of(
      // GO label: sifDataTabs.field.invoiceType ("Tipo factura" / "Invoice type") — SifTab.jsx,
      // shared by sales and purchase, not window-specific.
      "La factura debe contener información en el campo Clave tipo factura.", "siiMissingInvoiceType",
      // GO label: purchase-invoice/index.jsx LABEL_OVERRIDES.POReference ("Nº documento" /
      // "Document No.") — verified live; see the class javadoc above.
      "La factura de compra debe contener información en el campo Referencia del proveedor.",
          "siiMissingDocumentNo"
  );

  /**
   * Replaces the response's top-level {@code message} with a full GO-authored sentence, in the
   * request's resolved language, for the fixed set of {@link #DRIFTED_CLASSIC_SENTENCES} classic
   * sentences only (ETP-5745). Any other message — a different validation, an AEAT SOAP error,
   * the generic exception-wrapper text — passes through completely untouched: this is a targeted
   * fix for a known, fixed set of sentences, not a general classic-message translator.
   *
   * @param response the response {@link #normalizeErrorShape} already ran on (so a top-level
   *     {@code message} is present whenever the body carried one at all)
   * @param requestLanguage the language to build the replacement in — MUST be captured by the
   *     caller BEFORE the classic {@code executeObuiappClass} call that produced {@code response}
   *     (see the class javadoc's "language must be captured BEFORE" section for why)
   * @return {@code response}, with its body's {@code message} replaced in place when it matched
   */
  static NeoResponse remapDriftedFieldLabels(NeoResponse response, String requestLanguage) {
    if (response == null || response.getBody() == null) {
      return response;
    }
    JSONObject body = response.getBody();
    String message = body.optString("message", null);
    if (message == null) {
      return response;
    }
    for (Map.Entry<String, String> entry : DRIFTED_CLASSIC_SENTENCES.entrySet()) {
      if (message.contains(entry.getKey())) {
        String replacement = NeoHints.text(entry.getValue(), requestLanguage);
        try {
          body.put("message", replacement);
        } catch (JSONException ignored) {
          // Best-effort: leave the original (classic-only) message rather than fail the request.
        }
        break;
      }
    }
    return response;
  }

  @Override
  protected boolean matchesActionName(String fieldName) {
    return ACTION_NAME.equals(fieldName)
        || ACTION_NAME_LEGACY.equals(fieldName)
        || ACTION_NAME_QUALIFIER.equals(fieldName);
  }

  @Override
  protected String buildExecutionErrorMessage(Exception e) {
    return "SII send failed: " + e.getMessage();
  }

  private static String resolveOrganizationId(Invoice invoice) {
    if (invoice == null || invoice.getOrganization() == null) {
      return null;
    }
    return invoice.getOrganization().getId();
  }
}
