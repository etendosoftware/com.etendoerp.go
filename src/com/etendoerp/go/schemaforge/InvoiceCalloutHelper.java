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
   * After a non-DocType callout, corrects any stale {@code etvfacVerifacDesc}/{@code etvfacInvType}
   * values that a cascade may have injected based on the wrong DocType.
   * Re-reads the values from the DocType the user actually selected ({@code formState.transactionDocument}).
   * No-op when the trigger IS {@code transactionDocument} (handled by
   * {@link #applyVerifactuInvTypeFromDocType}) or when no Verifactu field appears in updates.
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
      String sql = "SELECT em_etvfac_verifac_desc, em_etvfac_inv_type"
          + " FROM c_doctype WHERE c_doctype_id = ?";
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, docTypeId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            String desc    = rs.getString(1);
            String invType = rs.getString(2);
            if (desc != null && !desc.trim().isEmpty()) {
              JSONObject descUpdate = new JSONObject();
              descUpdate.put(AbstractInvoiceHeaderHandler.FIELD_VALUE, desc.trim());
              updates.put("etvfacVerifacDesc", descUpdate);
            }
            // Also realign etvfacInvType if the cascade put a wrong value there
            if (invType != null && !invType.trim().isEmpty()
                && updates.has(AbstractInvoiceHeaderHandler.FIELD_ETVFAC_INV_TYPE)) {
              JSONObject invUpdate = new JSONObject();
              invUpdate.put(AbstractInvoiceHeaderHandler.FIELD_VALUE, invType.trim());
              updates.put(AbstractInvoiceHeaderHandler.FIELD_ETVFAC_INV_TYPE, invUpdate);
            }
          }
        }
      }
      log.debug("[ETP-4783] realignVerifactuDescWithFormStateDocType: corrected Verifactu desc/inv for docType={} (trigger={})",
          docTypeId, triggerField);
    } catch (Exception e) {
      log.warn("[ETP-4783] realignVerifactuDescWithFormStateDocType failed (non-fatal): {}", e.getMessage());
    }
  }

  /**
   * When the user changes the document type ({@code transactionDocument} trigger), injects the
   * new DocType's {@code em_etvfac_inv_type} value into the callout update map.
   * No-op when the trigger is not {@code transactionDocument}.
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
    try {
      Object rawValue = requestBody != null ? requestBody.opt(AbstractInvoiceHeaderHandler.FIELD_VALUE) : null;
      String docTypeId = rawValue != null ? rawValue.toString().trim() : "";
      if (docTypeId.isEmpty()) {
        return;
      }
      String sql = "SELECT em_etvfac_inv_type FROM c_doctype WHERE c_doctype_id = ?";
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, docTypeId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            String invType = rs.getString(1);
            if (invType != null && !invType.trim().isEmpty()) {
              // Updates entries must be { "value": "..." } objects, not raw strings —
              // the frontend's applyCalloutFieldUpdates reads entry.value; a raw string
              // would give entry.value === undefined and silently skip the update.
              JSONObject fieldUpdate = new JSONObject();
              fieldUpdate.put(AbstractInvoiceHeaderHandler.FIELD_VALUE, invType.trim());
              updates.put(AbstractInvoiceHeaderHandler.FIELD_ETVFAC_INV_TYPE, fieldUpdate);
              log.debug("[ETP-4783] applyVerifactuInvTypeFromDocType: injected etvfacInvType={} for docType={}",
                  invType.trim(), docTypeId);
            }
          }
        }
      }
    } catch (Exception e) {
      log.warn("[ETP-4783] applyVerifactuInvTypeFromDocType failed (non-fatal): {}", e.getMessage());
    }
  }

  /**
   * Injects TicketBAI rectificative fields into the callout response when the selected document
   * type is marked as rectificative ({@code em_etsg_isrectificative = 'Y'}).
   * Fires only when {@code triggerField} is {@code "transactionDocument"}.
   * No-op when {@link RectificativeSupport#isColumnPresent()} is {@code false}.
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
      String sql = "SELECT em_etsg_isrectificative FROM c_doctype WHERE c_doctype_id = ?";
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      boolean isRectificative = false;
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, docTypeId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            isRectificative = "Y".equalsIgnoreCase(rs.getString(1));
          }
        }
      }
      JSONObject isReverseUpdate = new JSONObject();
      isReverseUpdate.put(AbstractInvoiceHeaderHandler.FIELD_VALUE, isRectificative ? "Y" : "N");
      updates.put("tbaiIsreverseinvoice", isReverseUpdate);

      JSONObject reverseTypeUpdate = new JSONObject();
      // "I" = "Por diferencias" — the TicketBAI default reverse type (AD_REF_LIST VALUE for
      // reference 6E28A33291454412B2129FDC072B6FD9). Cleared when not rectificative.
      reverseTypeUpdate.put(AbstractInvoiceHeaderHandler.FIELD_VALUE, isRectificative ? "I" : "");
      updates.put("tbaiReverseinvoicetype", reverseTypeUpdate);

      log.debug("[ETP-4783] applyRectificativeFieldsFromDocType: docType={} isRectificative={}",
          docTypeId, isRectificative);
    } catch (Exception e) {
      log.warn("[ETP-4783] applyRectificativeFieldsFromDocType failed (non-fatal): {}", e.getMessage());
    }
  }
}
