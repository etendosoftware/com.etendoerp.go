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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.module.sii.data.AEATSIIConfig;
import org.openbravo.module.sii.utils.SIIUtils;

/**
 * Static helper methods extracted from {@link AbstractInvoiceHeaderHandler} to keep that
 * class within SonarQube's method-count limit (S1448, max 35 methods).
 *
 * <p>All methods here are package-private and stateless — they contain no instance state
 * and interact only through their parameters and the shared constants on
 * {@link AbstractInvoiceHeaderHandler}.
 */
final class InvoiceCalloutHelper {

  private static final Logger log = LogManager.getLogger(InvoiceCalloutHelper.class);

  private InvoiceCalloutHelper() {
    // utility class — not instantiable
  }

  // ---------------------------------------------------------------------------
  // Context utilities
  // ---------------------------------------------------------------------------

  /**
   * Resolves the invoice record ID from the context.
   * For CRUD requests the record ID is on the URL path; for POST (create) it must be
   * extracted from the CRUD response that precedes this hook.
   */
  static String resolveInvoiceIdFromContext(NeoContext context) {
    if (context.getRecordId() != null) {
      return context.getRecordId();
    }
    // POST: extract newly created record ID from the CRUD response
    return NeoHandlerUtils.extractCreatedIdFromPreviousResult(context);
  }

  /**
   * Returns {@code true} when the current request is a "Complete" action on an invoice
   * (doc action {@code CO}), either via a CRUD PATCH/PUT or a named action endpoint.
   */
  static boolean isInvoiceCompleteAction(NeoContext context) {
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      String method = context.getHttpMethod();
      if (!"PATCH".equals(method) && !"PUT".equals(method)) {
        return false;
      }
      JSONObject body = context.getRequestBody();
      return body != null
          && "CO".equals(body.optString(AbstractInvoiceHeaderHandler.FIELD_DOCUMENT_ACTION_INV, ""));
    }
    if (NeoEndpointType.ACTION.equals(context.getEndpointType())
        && AbstractInvoiceHeaderHandler.FIELD_DOCUMENT_ACTION_INV.equals(context.getFieldName())) {
      JSONObject body = context.getRequestBody();
      if (body == null) {
        return false;
      }
      JSONObject fieldValues = body.optJSONObject("fieldValues");
      String docAction = fieldValues != null
          ? fieldValues.optString(AbstractInvoiceHeaderHandler.FIELD_DOCUMENT_ACTION_INV, "")
          : body.optString("docAction",
              body.optString(AbstractInvoiceHeaderHandler.FIELD_DOCUMENT_ACTION_INV, ""));
      return "CO".equals(docAction);
    }
    return false;
  }

  // ---------------------------------------------------------------------------
  // Callout helpers (ETP-4783)
  // ---------------------------------------------------------------------------

  /**
   * Resolves the invoice record ID for a callout request.
   *
   * <p>Callout URLs carry no record-id path segment — {@code NeoServletSupport.parseSubEndpointPath}
   * matches the callout route as a literal {@code {specName}/{entityName}/callout} 3-segment path
   * with the record-id segment hardcoded {@code null}, and {@link NeoCalloutEndpoint#handleCallout}
   * never calls {@code .recordId(...)} on the {@link NeoContext} builder. So
   * {@link NeoContext#getRecordId()} is always {@code null} for a real callout, and the currently
   * loaded record's id must instead be read from the callout's echoed {@code formState.id}.
   *
   * @param context   the current NeoContext
   * @param formState the callout's {@code formState} object; may be {@code null}
   * @return the resolved invoice record id, or {@code null} if neither source has one
   */
  static String resolveCalloutRecordId(NeoContext context, JSONObject formState) {
    String recordId = context.getRecordId();
    if (StringUtils.isNotBlank(recordId)) {
      return recordId;
    }
    if (formState == null) {
      return null;
    }
    return StringUtils.trimToNull(formState.optString("id", null));
  }

  /**
   * Appends a typed message to the {@code messages} array in the response body.
   * Silently swallows any serialisation error.
   */
  static void appendMessage(JSONObject body, String type, String text) {
    try {
      JSONArray messages = body.optJSONArray("messages");
      if (messages == null) {
        messages = new JSONArray();
        body.put("messages", messages);
      }
      JSONObject msg = new JSONObject();
      msg.put("type", type);
      msg.put("text", text);
      messages.put(msg);
    } catch (Exception e) {
      log.warn("[ETP-4029] appendMessage failed: {}", e.getMessage());
    }
  }

  /**
   * When the user toggles {@code aeatsiiIsauthorization}, injects the SII authorization number
   * (or clears it) into the callout {@code updates} map so the frontend reflects the DB value.
   * No-op when the trigger field is not {@code aeatsiiIsauthorization}.
   * All failures are caught and logged as warnings (non-fatal).
   *
   * @param triggerField the name of the field that fired the callout
   * @param requestBody  the callout request body ({@code field}, {@code value}, {@code formState})
   * @param updates      the callout response's {@code updates} map; may be {@code null}
   */
  static void applySiiAuthorizationCallout(String triggerField, JSONObject requestBody,
      JSONObject updates) {
    if (!AbstractInvoiceHeaderHandler.FIELD_AEATSII_IS_AUTHORIZATION.equals(triggerField)) {
      return;
    }
    try {
      Object rawValue = requestBody != null ? requestBody.opt(AbstractInvoiceHeaderHandler.FIELD_VALUE) : null;
      String value = rawValue != null ? rawValue.toString() : "";
      boolean isAuthorization = "Y".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value);

      if (updates == null) {
        // Cannot inject field updates without the updates section — skip silently.
        log.debug("[ETP-4783] applySiiAuthorizationCallout: no updates map, skipping injection");
        return;
      }

      if (isAuthorization) {
        Organization org = OBContext.getOBContext().getCurrentOrganization();
        if (org == null) {
          log.warn("[ETP-4783] applySiiAuthorizationCallout: no current organization in OBContext");
          return;
        }
        AEATSIIConfig config = SIIUtils.getSiiConfigFromOrg(org);
        if (config == null || StringUtils.isBlank(config.getAuthorizationno())) {
          // The classic SiiAuthorizationCallout (executed earlier via NeoCalloutEndpoint.executeCallout)
          // already appended this ERROR message to the response. Appending it here too would cause the
          // error to appear twice in the UI. Skip — no field update to inject either. (ETP-4783)
          return;
        }
        // Updates entries must be { "value": "..." } objects — see applyVerifactuInvTypeFromDocType.
        JSONObject authUpdate = new JSONObject();
        authUpdate.put(AbstractInvoiceHeaderHandler.FIELD_VALUE, config.getAuthorizationno());
        updates.put(AbstractInvoiceHeaderHandler.FIELD_AEATSII_AUTHORIZATION_NO, authUpdate);
      } else {
        JSONObject authClear = new JSONObject();
        authClear.put(AbstractInvoiceHeaderHandler.FIELD_VALUE, "");
        updates.put(AbstractInvoiceHeaderHandler.FIELD_AEATSII_AUTHORIZATION_NO, authClear);
      }
    } catch (Exception e) {
      log.warn("[ETP-4783] applySiiAuthorizationCallout failed (non-fatal): {}", e.getMessage());
    }
  }

  /**
   * After a non-DocType callout, corrects a stale {@code etvfacVerifacDesc} that a cascade may
   * have injected based on the wrong DocType. Re-reads the value from the DocType the user
   * actually selected ({@code formState.transactionDocument}).
   * No-op when the trigger IS {@code transactionDocument} (handled by
   * {@link #applyVerifactuInvTypeFromDocType}) or when no Verifactu field appears in updates.
   *
   * <p>ETP-5745 CP-20: this used to ALSO realign {@code etvfacInvType} from the DocType's own
   * configured code — removed, same reasoning as {@link #applyVerifactuInvTypeFromDocType}: that
   * field encodes "this is a correction AND why" in one value (R1-R5), so copying ANY
   * DocType-configured code into it — even one a human set up front, on the DocType, once — is
   * still picking the user's reason for THIS invoice on their behalf.
   *
   * @param triggerField the field that fired the callout
   * @param formState    the form state snapshot from the callout request
   * @param updates      the callout response's {@code updates} map; may be {@code null}
   */
  static void realignVerifactuDescWithFormStateDocType(String triggerField,
      JSONObject formState, JSONObject updates) {
    if (AbstractInvoiceHeaderHandler.FIELD_TRANSACTION_DOCUMENT.equals(triggerField)) {
      return; // already handled by applyVerifactuInvTypeFromDocType
    }
    if (updates == null || !updates.has("etvfacVerifacDesc")) {
      return; // cascade did not touch Verifactu description — nothing to realign
    }
    if (formState == null) {
      return;
    }
    String docTypeId = formState.optString(AbstractInvoiceHeaderHandler.FIELD_TRANSACTION_DOCUMENT, "").trim();
    if (docTypeId.isEmpty()) {
      return;
    }
    try {
      String sql = "SELECT em_etvfac_verifac_desc FROM c_doctype WHERE c_doctype_id = ?";
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, docTypeId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            String desc = rs.getString(1);
            if (desc != null && !desc.trim().isEmpty()) {
              JSONObject descUpdate = new JSONObject();
              descUpdate.put(AbstractInvoiceHeaderHandler.FIELD_VALUE, desc.trim());
              updates.put("etvfacVerifacDesc", descUpdate);
            }
          }
        }
      }
      log.debug("[ETP-4783] realignVerifactuDescWithFormStateDocType: corrected Verifactu desc for docType={} (trigger={})",
          docTypeId, triggerField);
    } catch (Exception e) {
      log.warn("[ETP-4783] realignVerifactuDescWithFormStateDocType failed (non-fatal): {}", e.getMessage());
    }
  }

  /** The generic AD_Column default for {@code etvfacInvType} (ETP-4390: "Factura"). */
  private static final String VERIFACTU_INV_TYPE_DEFAULT = "F1";

  /**
   * When the user changes the document type ({@code transactionDocument} trigger), sets
   * {@code etvfacInvType} to EMPTY when the newly-selected document type is rectificative, or
   * back to the generic default ({@code "F1"}) when it is not.
   *
   * <p>ETP-5745 CP-20: this method used to copy the NEW DocType's own configured
   * {@code em_etvfac_inv_type} onto the invoice whenever the document type became rectificative
   * — reverted, together with the javadoc rationale that survives it: VERI*FACTU folds "this is
   * a correction" AND "for this reason" into the SAME {@code R1}-{@code R5} value, so copying
   * ANY code — even one a human configured on the DocType itself, once, for every invoice of
   * that type — is still picking a reason on behalf of THIS specific invoice, exactly the
   * fiscal judgement call {@code aeatsiiMotivoRectif}/{@code tbaiReverseinvoicecode} are never
   * allowed to guess either (see {@code SalesRectificativeFiscalDefaults}' class javadoc). The
   * user must pick an {@code R1}-{@code R5} code manually, and
   * {@link RectificationFiscalCodeGuard} blocks completion while it is still {@code F*}/blank.
   *
   * <p>ETP-5745 CP-22: a rectificative document type now ACTIVELY CLEARS
   * {@code etvfacInvType} to {@code ""} — CP-20 had left it untouched instead, which meant SII's
   * Clave visibly flips to {@code "R"} and TicketBAI's Código field visibly appears the instant
   * the user picks a rectificative doc type, but VERI*FACTU's own "Tipo de Factura" silently sat
   * at {@code "F1"} with no visual cue that it, too, needs a manual choice before completion —
   * the user only found out at the 422. Clearing it gives the SAME "something needs your
   * attention" signal the other two systems already have (the Select shows its empty-state
   * placeholder — see {@code useSifFieldPatcher.js}'s matching exclusion of a rectificative
   * invoice from its own F1-backfill effect, CP-22).
   *
   * <p>The CLEARING-to-{@code "F1"} side (switching AWAY from a rectificative doc type) is
   * unchanged: cleanup of a now-irrelevant {@code R1}-{@code R5} value, not a guess (same
   * reasoning as {@code tbaiReverseinvoicecode}'s own clear-on-revert).
   * No-op when the trigger is not {@code transactionDocument}, or when
   * {@link RectificativeSupport#isColumnPresent()} is {@code false} (no way to tell direction).
   *
   * @param triggerField the field that fired the callout
   * @param requestBody  the callout request body ({@code field}, {@code value}, {@code formState})
   * @param updates      the callout response's {@code updates} map; may be {@code null}
   */
  static void applyVerifactuInvTypeFromDocType(String triggerField, JSONObject requestBody,
      JSONObject updates) {
    if (!AbstractInvoiceHeaderHandler.FIELD_TRANSACTION_DOCUMENT.equals(triggerField)) {
      return;
    }
    if (updates == null) {
      return;
    }
    if (!RectificativeSupport.isColumnPresent()) {
      return;
    }
    try {
      Object rawValue = requestBody != null ? requestBody.opt(AbstractInvoiceHeaderHandler.FIELD_VALUE) : null;
      String docTypeId = rawValue != null ? rawValue.toString().trim() : "";
      if (docTypeId.isEmpty()) {
        return;
      }
      String sql = "SELECT em_etsg_isrectificative FROM c_doctype WHERE c_doctype_id = ?";
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, docTypeId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            boolean isRectificative = "Y".equalsIgnoreCase(rs.getString(1));
            String effectiveInvType = isRectificative ? "" : VERIFACTU_INV_TYPE_DEFAULT;
            // Updates entries must be { "value": "..." } objects, not raw strings —
            // the frontend's applyCalloutFieldUpdates reads entry.value; a raw string
            // would give entry.value === undefined and silently skip the update.
            JSONObject fieldUpdate = new JSONObject();
            fieldUpdate.put(AbstractInvoiceHeaderHandler.FIELD_VALUE, effectiveInvType);
            updates.put(AbstractInvoiceHeaderHandler.FIELD_ETVFAC_INV_TYPE, fieldUpdate);
            log.debug("[ETP-4783] applyVerifactuInvTypeFromDocType: set etvfacInvType to '{}' for docType={}",
                effectiveInvType, docTypeId);
          }
        }
      }
    } catch (Exception e) {
      log.warn("[ETP-4783] applyVerifactuInvTypeFromDocType failed (non-fatal): {}", e.getMessage());
    }
  }

  /**
   * Injects {@code isRectificative} plus TicketBAI's and SII's own STRUCTURAL rectificative
   * fields into the callout response when the selected document type is marked as rectificative
   * ({@code em_etsg_isrectificative = 'Y'}) — never a "motivo"/code, only the fields that say
   * "this is a correction" (ETP-5745 CP-15).
   * Fires only when {@code triggerField} is {@code "transactionDocument"}.
   * No-op when {@link RectificativeSupport#isColumnPresent()} is {@code false}.
   *
   * <p>{@code applyRectificativeFieldsFromDocType} is invoked from the shared
   * {@code AbstractInvoiceHeaderHandler#handleInvoiceAfterCallout}, used identically by both
   * {@code SalesInvoiceHeaderHandler} and {@code PurchaseInvoiceHeaderHandler} — this method has no
   * built-in signal for invoice direction. In practice ALL THREE TicketBAI fields
   * ({@code tbaiIsreverseinvoice}, {@code tbaiReverseinvoicetype}, {@code tbaiReverseinvoicecode})
   * are already sales-only at the model level, confirmed 2026-09 (ETP-5229 investigation): their
   * {@code ETGO_SF_FIELD.ISINCLUDED} flag is {@code 'N'} on the {@code purchase-invoice} header
   * entity (spec {@code 6E4047EE1CD34DEC95D575599A2930A6}) and {@code 'Y'} on {@code sales-invoice}
   * (spec {@code E0AD89F210BD4687AF397206F0735FB5}) — so the generated purchase-invoice frontend
   * never renders these fields and any callout {@code updates} entry for them is UI-inert there.
   * The classic AD_Field registration mirrors this: {@code EM_Tbai_Isreverseinvoice}/
   * {@code EM_Tbai_Reverseinvoicetype}/{@code EM_Tbai_Reverseinvoicecode} are registered ONLY on the
   * Sales Invoice window's Header tab, not on Purchase Invoice at all — the same structural pattern
   * SII already uses for its own rectificative doc-type reference list (purchase doc types never
   * offer a rectificative "Tipo Factura" option). So the two pre-existing fields needed no Java-side
   * guard — they were already inert on purchase invoices via that model-level gate, not by accident.
   * {@code tbaiReverseinvoicecode} (this fix) still gets an EXPLICIT {@code issotrx} guard below —
   * belt-and-braces so the backend's intent doesn't silently depend on frontend field config, and
   * so this callout never emits a stray {@code updates} entry for a field structurally absent on the
   * purchase side. TicketBAI reverse-invoice codes (AD_REF_LIST 89C6433F71584D23B95140DE86EDC726,
   * e.g. {@code "R4"} = "Factura rectificativa: Resto.") are an issued-document (AR) concept — a
   * received (AP) purchase invoice is never TicketBAI-reported by the receiving party.
   *
   * <p><b>ETP-5745 CP-14:</b> {@code tbaiReverseinvoicecode} is ONLY ever CLEARED here (when the
   * document type stops being rectificative) — never auto-picked. See the inline comment at its
   * block below for why.</p>
   *
   * <p><b>ETP-5745 CP-18:</b> {@code aeatsiiMotivoRectif} gets the same treatment — cleared on
   * revert, never set. {@code aeatsiiClaveTipo}, unlike the two "motivo"/code fields, IS reverted
   * to its generic default ({@code "F1"}) on revert (manual testing showed leaving it at
   * {@code "R"} stranded the form in an inconsistent state within the same unsaved session) — see
   * the inline comments at the {@code aeatsiiClaveTipo}/{@code aeatsiiMotivoRectif} block below.</p>
   *
   * @param triggerField the callout trigger field name
   * @param requestBody  the callout request body
   * @param updates      the callout response's {@code updates} map; may be {@code null}
   */
  static void applyRectificativeFieldsFromDocType(String triggerField,
      JSONObject requestBody, JSONObject updates) {
    if (!AbstractInvoiceHeaderHandler.FIELD_TRANSACTION_DOCUMENT.equals(triggerField)) {
      return;
    }
    if (updates == null) {
      return;
    }
    if (!RectificativeSupport.isColumnPresent()) {
      return;
    }
    try {
      Object rawValue = requestBody != null ? requestBody.opt(AbstractInvoiceHeaderHandler.FIELD_VALUE) : null;
      String docTypeId = rawValue != null ? rawValue.toString().trim() : "";
      if (docTypeId.isEmpty()) {
        return;
      }
      String sql = "SELECT em_etsg_isrectificative, issotrx FROM c_doctype WHERE c_doctype_id = ?";
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      boolean isRectificative = false;
      boolean isSalesTransaction = false;
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, docTypeId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            isRectificative = "Y".equalsIgnoreCase(rs.getString(1));
            isSalesTransaction = "Y".equalsIgnoreCase(rs.getString(2));
          }
        }
      }
      // ETP-5745 CP-15: echo the structural flag itself back to the client. The SIF tab's
      // TicketBAI panel (and, below, SII's own structural auto-set) both gate purely on
      // `data.isRectificative` — which otherwise only ever arrives via a GET response
      // (AbstractInvoiceHeaderHandler#enrichIsRectificative), i.e. only after a header save
      // triggers the window's refetch. Putting it in THIS callout's `updates` — the same
      // round trip that already carries tbaiIsreverseinvoice — makes it available the instant
      // the user picks the document type, no save required.
      JSONObject isRectificativeUpdate = new JSONObject();
      isRectificativeUpdate.put(AbstractInvoiceHeaderHandler.FIELD_VALUE, isRectificative);
      updates.put("isRectificative", isRectificativeUpdate);

      JSONObject isReverseUpdate = new JSONObject();
      isReverseUpdate.put(AbstractInvoiceHeaderHandler.FIELD_VALUE, isRectificative ? "Y" : "N");
      updates.put("tbaiIsreverseinvoice", isReverseUpdate);

      JSONObject reverseTypeUpdate = new JSONObject();
      // "I" = "Por diferencias" — the TicketBAI default reverse type (AD_REF_LIST VALUE for
      // reference 6E28A33291454412B2129FDC072B6FD9). Cleared when not rectificative.
      reverseTypeUpdate.put(AbstractInvoiceHeaderHandler.FIELD_VALUE, isRectificative ? "I" : "");
      updates.put("tbaiReverseinvoicetype", reverseTypeUpdate);

      // ETP-5229: tbaiReverseinvoicecode is SALES-ONLY (see javadoc) — never touched for a
      // purchase (AP) doc type, so it is left exactly as-is on purchase invoices.
      //
      // ETP-5745 CP-14: no longer auto-picks "R4" ("Factura rectificativa: Resto.", AD_REF_LIST
      // 89C6433F71584D23B95140DE86EDC726) when the document type becomes rectificative. This is
      // the exact kind of fiscal classification choice (why the invoice is being corrected) that
      // must stay a manual, informed decision — the same reasoning SII's aeatsiiMotivoRectif
      // already followed (see SalesRectificativeFiscalDefaults' class javadoc). Only the CLEARING
      // side is kept: switching the document type AWAY from rectificative still blanks a
      // now-irrelevant code, which is cleanup, not a guess. RectificationFiscalCodeGuard blocks
      // completion while this field is still blank on a rectificative invoice.
      if (isSalesTransaction && !isRectificative) {
        JSONObject reverseCodeUpdate = new JSONObject();
        reverseCodeUpdate.put(AbstractInvoiceHeaderHandler.FIELD_VALUE, "");
        updates.put("tbaiReverseinvoicecode", reverseCodeUpdate);
      }

      // ETP-5745 CP-15/CP-18/CP-24: SII's own structural fields, via the SAME callout TicketBAI
      // already uses — so the "Tipo factura" dropdown shows "R" the instant the user picks a
      // rectificative document type, instead of only after a header save + refetch (the gap
      // reported against RectificationFiscalCodeGuard's structural correction, which only runs
      // at completion). Sales-only — see useSifFieldPatcher.js's ETP-5745 CP-10 precedent:
      // purchase-side corrective support isn't implemented, so purchase invoices stay at 'F1'.
      //
      // ETP-5745 CP-24: additionally gated on the organization actually being enrolled in SII
      // (SalesRectificativeFiscalDefaults#siiConfigActive) — unlike TicketBAI/VERI*FACTU just
      // above, these fields used to be touched UNCONDITIONALLY. That was the real bug behind
      // "Una factura rectificativa debe tener tipo de factura rectificativa y motivo de
      // rectificación para enviar al SII" firing for an org using ONLY VERI*FACTU (SII never
      // configured at all): the DB check constraint (EM_AEATSII_C_INV_RECT_CHK) fires on
      // ClaveTipo='R' alone, with zero awareness of whether the org even uses SII — so stamping
      // "R" here for such an org guaranteed the save would fail on an empty Motivo nobody was
      // ever going to be asked for. When SII is not active for this org, aeatsiiClaveTipo/
      // aeatsiiTipoRectif/aeatsiiMotivoRectif are left completely untouched — including on
      // revert — exactly as if this block did not run at all.
      //
      // ETP-5745 CP-18: when SII IS active, aeatsiiClaveTipo is ALSO reverted to the generic
      // AD_Column default ("F1") when the document type stops being rectificative — confirmed by
      // manual testing that leaving it at "R" (the earlier CP-15 behavior, deliberately
      // asymmetric) stranded the invoice with an inconsistent "Tipo factura" within the same
      // unsaved session. aeatsiiTipoRectif ("I" / cleared) mirrors tbaiReverseinvoicetype's
      // clear-on-revert behavior. aeatsiiMotivoRectif is NEVER set to a value (still nobody's
      // guess to make — see RectificationFiscalCodeGuard and SalesRectificativeFiscalDefaults'
      // class javadocs), but a value the user typed BY HAND while the document was rectificative
      // must not stay stuck once it no longer applies — exactly the same "clearing is cleanup,
      // not a guess" reasoning tbaiReverseinvoicecode already follows above.
      if (isSalesTransaction && isCurrentOrgEnrolledInSii()) {
        JSONObject claveTipoUpdate = new JSONObject();
        claveTipoUpdate.put(AbstractInvoiceHeaderHandler.FIELD_VALUE, isRectificative ? "R" : "F1");
        updates.put("aeatsiiClaveTipo", claveTipoUpdate);

        JSONObject tipoRectifUpdate = new JSONObject();
        tipoRectifUpdate.put(AbstractInvoiceHeaderHandler.FIELD_VALUE, isRectificative ? "I" : "");
        updates.put("aeatsiiTipoRectif", tipoRectifUpdate);

        if (!isRectificative) {
          JSONObject motivoRectifUpdate = new JSONObject();
          motivoRectifUpdate.put(AbstractInvoiceHeaderHandler.FIELD_VALUE, "");
          updates.put("aeatsiiMotivoRectif", motivoRectifUpdate);
        }
      }

      log.debug("[ETP-4783/ETP-5229] applyRectificativeFieldsFromDocType: docType={} isRectificative={} "
              + "isSalesTransaction={}",
          docTypeId, isRectificative, isSalesTransaction);
    } catch (Exception e) {
      log.warn("[ETP-4783] applyRectificativeFieldsFromDocType failed (non-fatal): {}", e.getMessage());
    }
  }

  /**
   * Whether the CURRENT session's organization (the callout has no invoice id yet on a brand
   * new, unsaved record, so there is no {@code Invoice.organization} to read — this mirrors
   * {@code applySiiAuthorizationCallout}'s own org resolution a few methods above) is actually
   * enrolled in the SII, via {@link SalesRectificativeFiscalDefaults#siiConfigActive}.
   *
   * <p>Never throws: a failure to resolve the context organization answers {@code false}.
   */
  private static boolean isCurrentOrgEnrolledInSii() {
    try {
      Organization org = OBContext.getOBContext().getCurrentOrganization();
      return org != null && SalesRectificativeFiscalDefaults.siiConfigActive(org.getId());
    } catch (Exception e) {
      log.debug("[ETP-5745] Could not resolve the current organization's SII enrollment: {}",
          e.getMessage());
      return false;
    }
  }
}
