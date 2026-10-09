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

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;

/**
 * The write fence of a processed invoice (ETP-5692): on the CRUD update path, an invoice that is
 * no longer a draft accepts only the changes the invoice window itself can make.
 *
 * <p><b>Why.</b> Once completed, the SPA locks the whole invoice except a short allowlist
 * ({@code window.draftMode.keepSaveWhenCompletedFields} for the header, {@code
 * editableLineFieldsWhenCompleted} for the lines, plus the notes panel). REST and MCP writes only
 * met the core triggers, which protect a fixed column list: on a completed invoice
 * {@code description} or {@code paymentMethod} were accepted, and on a <em>posted</em> one the
 * header cost center was accepted too — {@code C_INVOICE_TRG} locks {@code C_Project_ID},
 * {@code DateAcct} and the other dimensions while posted, but not {@code C_Costcenter_ID} — so the
 * header disagreed with the ledger. The API must not allow more than the UI does.</p>
 *
 * <p><b>The rules,</b> evaluated against the stored record, on {@code PUT}/{@code PATCH} only:
 * <ol>
 *   <li>A draft ({@code Processed = 'N'}) is not fenced at all.</li>
 *   <li>A key whose value differs from the stored one must be in the allowlist; anything else —
 *       including a key that is not a property of the entity (a virtual field) — is refused,
 *       naming every offending key and the allowed ones. A key sent with its stored value is not
 *       a change and passes.</li>
 *   <li>While the invoice is posted ({@code Posted = 'Y'}), the allowlisted fields that feed the
 *       ledger ({@link #LEDGER_FIELDS}) are refused too: unpost first.</li>
 *   <li>On a processed invoice whose status is not {@code CO} (voided, closed…), the accounting
 *       dimensions ({@link #DIMENSION_FIELDS}) are refused.</li>
 * </ol>
 * Rules 3–4 mirror the {@code readOnlyLogic} the SPA applies to project / cost center
 * ({@code @Posted@='Y' | (@Processed@='Y' & @DocStatus@!'CO')}) and accounting date
 * ({@code @Posted@='Y'}). One more SPA lock is mirrored: a purchase invoice's
 * {@code orderReference} is refused once it has been sent to the SII
 * ({@code @EM_Aeatsii_Issent@='Y' & @IsSOTrx@='N'}).</p>
 *
 * <p><b>Placement.</b> This is the invoice customization, not shared NEO code: it is called only
 * from {@link AbstractInvoiceHeaderHandler}'s subclasses and {@link InvoiceLineHandler}, so it runs
 * on every channel that resolves those customizations — REST single, MCP {@code etendo_update}.
 * REST and MCP {@code etendo_batch} only create records, and a line created on a processed invoice
 * is already refused by {@code C_INVLINE_CHK_RESTRICTIONS_TRG}.</p>
 *
 * <p><b>The allowlist is declared here, once.</b> It is the backend copy of the window
 * configuration ({@code decisions.json → window.draftMode} and {@code notesField}), which is not
 * pushed to NEO; change both together. Writes done by the invoice's own handlers and processes
 * through DAL (SII/TBAI/VeriFactu state, payments, the exchange-rate mirror onto
 * {@code eTGOCurrencyRate}, the origin-invoice link) do not go through the CRUD update and are not
 * affected.</p>
 *
 * <p>Fails open when the record cannot be read: the update itself then runs into the same core
 * triggers it always did.</p>
 */
public final class CompletedInvoiceWriteFence {

  private static final Logger log = LogManager.getLogger(CompletedInvoiceWriteFence.class);

  /** Purchase-invoice supplier number: allowlisted, but locked once the invoice is sent to the SII. */
  private static final String FIELD_ORDER_REFERENCE = "orderReference";

  /**
   * Header fields both invoice windows can still change once completed: the
   * {@code keepSaveWhenCompletedFields} of sales-invoice ({@code accountingDate}, {@code project},
   * {@code costcenter}) plus {@code description}, the window's {@code notesField}, which the notes
   * panel saves on a completed document by design (ETP-5205).
   */
  public static final Set<String> HEADER_EDITABLE_WHEN_COMPLETED = orderedSet("accountingDate",
      "project", "costcenter", "description");

  /**
   * Purchase-invoice header: the above plus {@code orderReference} (the supplier's invoice
   * number), which its {@code keepSaveWhenCompletedFields} also lists (ETP-4839).
   */
  public static final Set<String> PURCHASE_HEADER_EDITABLE_WHEN_COMPLETED =
      union(HEADER_EDITABLE_WHEN_COMPLETED, orderedSet(FIELD_ORDER_REFERENCE));

  /** Line fields both invoice windows can still change once completed ({@code editableLineFieldsWhenCompleted}). */
  public static final Set<String> LINE_EDITABLE_WHEN_COMPLETED = orderedSet("project", "costcenter");

  /** Allowlisted fields the ledger was booked with: locked while the invoice is posted. */
  static final Set<String> LEDGER_FIELDS = orderedSet("accountingDate", "project", "costcenter");

  /** Allowlisted accounting dimensions: editable on a processed invoice only while it is {@code CO}. */
  static final Set<String> DIMENSION_FIELDS = orderedSet("project", "costcenter");

  /** Keys that carry no business change: identity, the concurrency token, audit and the parent link. */
  private static final Set<String> META_KEYS = Set.of("id", "updated", "updatedBy", "creationDate",
      "createdBy", "parentId", "recordTime");

  static final String CODE_FIELDS_LOCKED = "completed_invoice_fields_locked";
  static final String CODE_POSTED_LOCKED = "posted_invoice_fields_locked";
  static final String CODE_STATUS_LOCKED = "invoice_status_fields_locked";
  static final String CODE_SII_SENT_LOCKED = "sii_sent_invoice_fields_locked";

  /*
   * Stable identities sent in messageKeys. Plain strings, deliberately NOT AD_MESSAGE records
   * (ETP-5692 decision): the text is built here in English, like DocumentPostingService's own
   * answers; a client that wants another language maps the key (the SPA through backendErrors.js).
   */
  static final String MSG_FIELDS_LOCKED = "ETGO_CompletedInvoiceFieldsLocked";
  static final String MSG_POSTED_LOCKED = "ETGO_InvoiceFieldsLockedPosted";
  static final String MSG_STATUS_LOCKED = "ETGO_InvoiceFieldsLockedStatus";
  static final String MSG_SII_SENT_LOCKED = "ETGO_InvoiceFieldsLockedSiiSent";


  static final String PARAM_FIELDS = "fields";
  static final String PARAM_ALLOWED_FIELDS = "allowedFields";
  static final String PARAM_DOC_STATUS = "docStatus";

  private static final String STATUS_COMPLETED = "CO";
  private static final String YES = "Y";
  private static final String IDENTIFIER_SUFFIX = "$_identifier";
  private static final int SC_UNPROCESSABLE = 422;

  private CompletedInvoiceWriteFence() {
  }

  /**
   * Fences a header update.
   *
   * @param context              the request
   * @param editableWhenCompleted the header allowlist of the calling window
   * @return a 422 naming the refused fields, or {@code null} to let the update run
   */
  public static NeoResponse checkHeader(NeoContext context, Set<String> editableWhenCompleted) {
    if (!isUpdate(context)) {
      return null;
    }
    try {
      Invoice invoice = read(Invoice.class, context.getRecordId());
      return invoice == null ? null
          : check(invoice, invoice, context.getRequestBody(), editableWhenCompleted);
    } catch (RuntimeException | JSONException e) {
      log.warn("Could not evaluate the completed-invoice fence for invoice {}: {}",
          context.getRecordId(), e.getMessage());
      return null;
    }
  }

  /**
   * Fences an invoice line update, judged by the state of its invoice.
   *
   * @param context the request
   * @return a 422 naming the refused fields, or {@code null} to let the update run
   */
  public static NeoResponse checkLine(NeoContext context) {
    if (!isUpdate(context)) {
      return null;
    }
    try {
      InvoiceLine line = read(InvoiceLine.class, context.getRecordId());
      if (line == null || line.getInvoice() == null) {
        return null;
      }
      return check(line, line.getInvoice(), context.getRequestBody(),
          LINE_EDITABLE_WHEN_COMPLETED);
    } catch (RuntimeException | JSONException e) {
      log.warn("Could not evaluate the completed-invoice fence for line {}: {}",
          context.getRecordId(), e.getMessage());
      return null;
    }
  }

  private static boolean isUpdate(NeoContext context) {
    if (context == null || !NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return false;
    }
    String method = context.getHttpMethod();
    return ("PATCH".equals(method) || "PUT".equals(method))
        && StringUtils.isNotBlank(context.getRecordId()) && context.getRequestBody() != null;
  }

  private static <T extends BaseOBObject> T read(Class<T> type, String id) {
    OBContext.setAdminMode(true);
    try {
      return OBDal.getInstance().get(type, id);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The rules of the class javadoc, against {@code record} (the header or the line) and the state
   * of {@code invoice}.
   *
   * <p>On a voided or closed invoice that is NOT posted, only the dimensions
   * ({@link #DIMENSION_FIELDS}: project, costcenter) are locked; {@code accountingDate} stays
   * writable on purpose, because the SPA leaves it editable there too — its {@code readOnlyLogic}
   * is {@code @Posted@='Y'} only. The posted lock ({@link #LEDGER_FIELDS}) is the one that covers
   * it.</p>
   */
  static NeoResponse check(BaseOBObject record, Invoice invoice, JSONObject body,
      Set<String> editableWhenCompleted) throws JSONException {
    if (!Boolean.TRUE.equals(invoice.isProcessed())) {
      return null;
    }
    List<String> changed = changedFields(record, body);
    if (changed.isEmpty()) {
      return null;
    }
    List<String> refused = new ArrayList<>();
    for (String key : changed) {
      if (!editableWhenCompleted.contains(key)) {
        refused.add(key);
      }
    }
    if (!refused.isEmpty()) {
      return reject(CODE_FIELDS_LOCKED, MSG_FIELDS_LOCKED, refused, editableWhenCompleted, null,
          "This invoice is completed, so @fields@ can no longer be changed. Fields that can still "
              + "be changed: @allowedFields@.",
          null);
    }
    if (changed.contains(FIELD_ORDER_REFERENCE) && Boolean.TRUE.equals(invoice.isAeatsiiIssent())
        && !Boolean.TRUE.equals(invoice.isSalesTransaction())) {
      return reject(CODE_SII_SENT_LOCKED, MSG_SII_SENT_LOCKED, List.of(FIELD_ORDER_REFERENCE), null,
          null, "This purchase invoice has already been sent to the SII, so @fields@ (the "
              + "supplier's invoice number) can no longer be changed.",
          null);
    }
    if (YES.equals(invoice.getPosted())) {
      List<String> locked = intersect(changed, LEDGER_FIELDS);
      if (!locked.isEmpty()) {
        return reject(CODE_POSTED_LOCKED, MSG_POSTED_LOCKED, locked, null, null,
            "This invoice is posted, so @fields@ cannot be changed. Unpost it first to correct "
                + "its accounting.",
            "Run the invoice header action 'unpost' first, then retry this update; post the "
                + "invoice again afterwards.");
      }
      return null;
    }
    if (!STATUS_COMPLETED.equals(invoice.getDocumentStatus())) {
      List<String> locked = intersect(changed, DIMENSION_FIELDS);
      if (!locked.isEmpty()) {
        return reject(CODE_STATUS_LOCKED, MSG_STATUS_LOCKED, locked, null,
            invoice.getDocumentStatus(),
            "@fields@ cannot be changed on a voided or closed invoice (status @docStatus@).",
            null);
      }
    }
    return null;
  }

  /**
   * The keys of {@code body} whose value differs from {@code record}'s. A key that is not a
   * property of the entity is always a change (it cannot be compared, and is never allowed);
   * identity, audit, the concurrency token, server-owned keys and {@code $_identifier} companions
   * are not business changes and are skipped.
   */
  static List<String> changedFields(BaseOBObject record, JSONObject body) throws JSONException {
    Entity entity = record.getEntity();
    JSONObject values = unwrap(body, entity);
    List<String> changed = new ArrayList<>();
    for (Iterator<?> it = values.keys(); it.hasNext();) {
      String key = String.valueOf(it.next());
      if (isMetaKey(key)) {
        continue;
      }
      if (!entity.hasProperty(key)) {
        changed.add(key);
        continue;
      }
      if (!sameValue(record.get(key), values.get(key))) {
        changed.add(key);
      }
    }
    return changed;
  }

  private static JSONObject unwrap(JSONObject body, Entity entity) {
    JSONObject data = body.optJSONObject("data");
    return data != null && !entity.hasProperty("data") ? data : body;
  }

  private static boolean isMetaKey(String key) {
    return META_KEYS.contains(key) || key.startsWith("_") || key.endsWith(IDENTIFIER_SUFFIX)
        || NeoServerOwnedFields.isServerOwnedKey(key);
  }

  /**
   * Whether the value a client sent equals the stored one, in the shapes NEO writes accept: an id
   * (or {@code {id}}) for a reference, {@code yyyy-MM-dd…} for a date, any numeric form for a
   * number, {@code true}/{@code "Y"} for a boolean. Empty and {@code null} are the same.
   */
  static boolean sameValue(Object stored, Object sent) {
    boolean sentEmpty = sent == null || JSONObject.NULL.equals(sent)
        || (sent instanceof String && ((String) sent).isEmpty());
    if (stored == null || (stored instanceof String && ((String) stored).isEmpty())) {
      return sentEmpty;
    }
    if (sentEmpty) {
      return false;
    }
    if (stored instanceof BaseOBObject) {
      Object id = sent instanceof JSONObject ? ((JSONObject) sent).opt("id") : sent;
      return String.valueOf(((BaseOBObject) stored).getId()).equals(String.valueOf(id));
    }
    if (stored instanceof Date) {
      String text = String.valueOf(sent);
      return text.length() >= 10
          && new SimpleDateFormat("yyyy-MM-dd").format((Date) stored).equals(text.substring(0, 10));
    }
    if (stored instanceof Number) {
      try {
        return new BigDecimal(stored.toString()).compareTo(new BigDecimal(String.valueOf(sent))) == 0;
      } catch (NumberFormatException e) {
        return false;
      }
    }
    if (stored instanceof Boolean) {
      String text = String.valueOf(sent);
      boolean sentTrue = "true".equalsIgnoreCase(text) || YES.equalsIgnoreCase(text);
      return ((Boolean) stored) == sentTrue;
    }
    return String.valueOf(stored).equals(String.valueOf(sent));
  }

  /**
   * The 422 every rule answers: a plain-English sentence under {@code error.message} (no
   * AD_MESSAGE, ETP-5692 decision), plus the identity — {@code code}, the offending {@code fields}, the
   * {@code allowedFields}, {@code messageKeys}/{@code messageParams} — which the MCP lifts to the
   * top level of the agent's error, so the agent can correct the call without parsing prose.
   *
   * <p>Never {@code read_only_field}: the SPA retries a save without a field named that way,
   * which would turn this refusal into a silent partial save.</p>
   */
  static NeoResponse reject(String code, String messageKey, List<String> fields,
      Collection<String> allowed, String docStatus, String template, String hint)
      throws JSONException {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put(PARAM_FIELDS, fields);
    if (allowed != null) {
      params.put(PARAM_ALLOWED_FIELDS, new ArrayList<>(allowed));
    }
    if (docStatus != null) {
      params.put(PARAM_DOC_STATUS, docStatus);
    }
    String message = template
        .replace("@fields@", String.join(", ", fields))
        .replace("@allowedFields@", allowed == null ? "" : String.join(", ", allowed))
        .replace("@docStatus@", docStatus == null ? "" : docStatus);

    JSONObject error = new JSONObject();
    error.put("status", SC_UNPROCESSABLE);
    error.put("code", code);
    error.put("message", message);
    error.put(PARAM_FIELDS, new JSONArray(fields));
    if (allowed != null) {
      error.put(PARAM_ALLOWED_FIELDS, new JSONArray(new ArrayList<>(allowed)));
    }
    if (hint != null) {
      error.put("hint", hint);
    }
    error.put("messageKeys", new JSONArray(List.of(messageKey)));
    JSONObject messageParams = new JSONObject();
    for (Map.Entry<String, Object> entry : params.entrySet()) {
      Object value = entry.getValue();
      messageParams.put(entry.getKey(),
          value instanceof Collection<?> ? new JSONArray((Collection<?>) value) : value);
    }
    error.put("messageParams", messageParams);
    JSONObject body = new JSONObject();
    body.put("error", error);
    return NeoResponse.error(SC_UNPROCESSABLE, body);
  }

  private static List<String> intersect(List<String> changed, Set<String> set) {
    List<String> out = new ArrayList<>();
    for (String key : changed) {
      if (set.contains(key)) {
        out.add(key);
      }
    }
    return out;
  }

  private static Set<String> orderedSet(String... values) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(values)));
  }

  private static Set<String> union(Set<String> a, Set<String> b) {
    Set<String> out = new LinkedHashSet<>(a);
    out.addAll(b);
    return Collections.unmodifiableSet(out);
  }
}
