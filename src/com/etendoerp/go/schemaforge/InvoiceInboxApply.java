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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static com.etendoerp.go.schemaforge.ocr.InboxDocument.A_PRESENT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.A_UNKNOWN;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.D_FOUND;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.D_NONE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.D_UNKNOWN;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ADDRESS_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CANDIDATES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CATEGORY_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CURRENCY_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DESCRIPTION;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DISCOUNT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DOCUMENT_NO;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DUPLICATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_EXTRACTED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_HEADER;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INBOX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INDEX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICE_DATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_LINES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_LOCATION_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_MATCHED_BY;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NAME;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NEW;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NOTICES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_PRODUCT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_QUANTITY;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_RATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX_LABEL;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX_RATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TOTAL_AMOUNT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TOTAL_SOURCE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UNIT_PRICE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UOM_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VALIDATION;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VENDOR;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.MATCHED_MANUAL;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.N_DUPLICATE_CHECK_UNKNOWN;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.N_DUPLICATE_IN_INBOX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.N_VENDOR_ADDRESS_MISSING;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_LINKED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_NEW;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_NONE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.TOTAL_PRINTED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.T_PENDING_VENDOR;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.T_RESOLVED;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.ocr.InboxLineMatcher;
import com.etendoerp.go.schemaforge.ocr.InboxTaxModes;
import com.etendoerp.go.schemaforge.ocr.InvoiceInboxTotals;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Address;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Duplicates;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Party;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.TaxAnswer;
import com.etendoerp.go.schemaforge.ocr.OcrSanitizer;

/**
 * ETP-6050 — the {@code apply} action of the invoice inbox: {@code POST
 * /sws/neo/invoice-inbox/header/{id}/action/apply} with {@code {updated, operations[]}} edits the
 * document the review panel shows, atomically, and answers the review body of the saved row (the
 * very shape of {@link InvoiceInboxReview}, with the new {@code updated} token).
 *
 * <p>The operations ({@code setHeader}, {@code setLine}, {@code addLine}, {@code removeLine},
 * {@code selectVendor}, {@code newVendor}, {@code selectProduct}, {@code newProduct}) run in order
 * on a working copy of {@code CURRENT_DATA}; nothing is written before every one validated. Only
 * {@code CURRENT_DATA} is edited, {@code EXTRACTED_DATA} never. The browser only proposes: every
 * id (vendor, product, category, unit) is resolved again here, and every text is cleaned like an
 * extracted one ({@link OcrSanitizer#text}).</p>
 *
 * <p>After the operations the server recomputes, in one transaction and in this order: the taxes
 * that depend on what changed, the duplicates, the total (only when it follows the lines), the
 * verdict, the status ({@code READY} goes back to {@code PENDING_REVIEW}, an {@code ERROR} that the
 * edit completes moves to {@code PENDING_REVIEW}), {@code ADJUSTED}
 * ({@link InvoiceInboxAdjustedPolicy}) and the summary columns. A request whose operations change
 * nothing writes nothing and the {@code updated} token does not move.</p>
 *
 * <p>The line {@code index} is a stable id: never renumbered, a new line takes the highest index plus
 * one. Guards (access, tenant, organization, processing, editable, lock, token):
 * {@link InvoiceInboxEditGate}. Stateless; called by {@link InvoiceInboxHeaderHandler}.</p>
 */
final class InvoiceInboxApply {

  static final String ACTION = "apply";

  static final int MAX_OPERATIONS = 50;
  static final int MAX_LINES = 100;
  /** Largest request body, in characters of its JSON. */
  static final int MAX_BODY_CHARS = 64 * 1024;
  /** Largest stored {@code CURRENT_DATA}, in bytes. */
  static final int MAX_DOCUMENT_BYTES = 1024 * 1024;

  static final String CODE_INVALID_REQUEST = InvoiceInboxEditGate.CODE_INVALID_REQUEST;
  static final String CODE_MISSING_UPDATED = "missing_updated";
  static final String CODE_REQUEST_TOO_LARGE = "request_too_large";
  static final String CODE_DOCUMENT_TOO_LARGE = "document_too_large";
  static final String CODE_INVALID_VALUE = "invalid_value";
  static final String CODE_LINE_NOT_FOUND = "line_not_found";
  static final String CODE_TOO_MANY_LINES = "too_many_lines";
  static final String CODE_UNKNOWN_VENDOR = "unknown_vendor";
  static final String CODE_UNKNOWN_PRODUCT = "unknown_product";
  static final String CODE_UNKNOWN_CATEGORY = "unknown_category";
  static final String CODE_UNKNOWN_UOM = "unknown_uom";
  static final String CODE_UNKNOWN_TAX = "unknown_tax";

  static final String OP_SET_HEADER = "setHeader";
  static final String OP_SET_LINE = "setLine";
  static final String OP_ADD_LINE = "addLine";
  static final String OP_REMOVE_LINE = "removeLine";
  static final String OP_SELECT_VENDOR = "selectVendor";
  static final String OP_NEW_VENDOR = "newVendor";
  static final String OP_SELECT_PRODUCT = "selectProduct";
  static final String OP_NEW_PRODUCT = "newProduct";

  private static final String KEY_UPDATED = "updated";
  private static final String KEY_OPERATIONS = "operations";
  private static final String KEY_OP = "op";
  private static final String KEY_ID = "id";
  private static final String KEY_FIELDS = "fields";
  private static final String KEY_FIELD = "field";
  private static final String KEY_TAX_ID = "taxId";
  private static final String KEY_MAX = "max";
  private static final String PENDING_REVIEW = "PENDING_REVIEW";
  private static final String STATUS_READY = "READY";
  private static final int MAX_ID_LENGTH = 64;
  /** Decimals each editable number is rounded (HALF_UP) to and stored with. */
  static final int QUANTITY_SCALE = 4;
  static final int PRICE_SCALE = 6;
  static final int DISCOUNT_SCALE = 4;
  private static final int RATE_SCALE = 2;
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final int MAX_NUMBER_DIGITS = 30;
  private static final int MIN_YEAR = 1900;
  private static final int MAX_YEAR = 2100;
  private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

  /** The keys the {@code newVendor.fields} patch accepts: the extracted vendor data. */
  private static final Set<String> VENDOR_FIELDS = Set.of(K_NAME, "taxId", "address",
      "postalCode", "city", "countryName", "email", "phone");

  private static final Map<String, Set<String>> OP_KEYS = Map.of(
      OP_SET_HEADER, Set.of(KEY_OP, K_DOCUMENT_NO, K_INVOICE_DATE),
      OP_SET_LINE, Set.of(KEY_OP, K_INDEX, K_DESCRIPTION, K_QUANTITY, K_UNIT_PRICE, K_TAX_RATE,
          K_DISCOUNT, KEY_TAX_ID),
      OP_ADD_LINE, Set.of(KEY_OP),
      OP_REMOVE_LINE, Set.of(KEY_OP, K_INDEX),
      OP_SELECT_VENDOR, Set.of(KEY_OP, KEY_ID),
      OP_NEW_VENDOR, Set.of(KEY_OP, KEY_FIELDS),
      OP_SELECT_PRODUCT, Set.of(KEY_OP, K_INDEX, KEY_ID),
      OP_NEW_PRODUCT, Set.of(KEY_OP, K_INDEX, K_NAME, K_CATEGORY_ID, K_UOM_ID));

  private InvoiceInboxApply() {
  }

  /** A refusal raised anywhere in the request; carries the answer. No stack trace on purpose. */
  private static final class Refusal extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient NeoResponse response;

    Refusal(NeoResponse response) {
      super(null, null, false, false);
      this.response = response;
    }
  }

  /** The state of one request: the working copy and what the operations did to it. */
  private static final class Edit {
    private final JSONObject document;
    private final OcrLookups lookups;
    private final IncomingDocument row;
    private boolean dirty;
    private boolean marked;
    private boolean vendorChanged;
    private boolean docNoChanged;
    private boolean dateChanged;
    private final Set<Integer> taxLines = new LinkedHashSet<>();
    private final Set<Integer> inheritRate = new LinkedHashSet<>();

    Edit(JSONObject document, OcrLookups lookups, IncomingDocument row) {
      this.document = document;
      this.lookups = lookups;
      this.row = row;
    }

    JSONObject header() {
      return document.optJSONObject(K_HEADER);
    }

    JSONArray lines() {
      return document.optJSONArray(K_LINES);
    }

    /** Records an effective change; {@code marks} is the verdict of the adjusted policy. */
    void changed(boolean marks) {
      dirty = true;
      marked = marked || marks;
    }
  }

  /**
   * Applies the operations.
   *
   * @param recordId the id of the {@code ETGO_INCOMING_DOCUMENT} row
   * @param body     the request body, may be {@code null}
   * @return 200 with the review body of the saved row; the refusal otherwise (see 9.2.6 of the plan)
   */
  static NeoResponse apply(String recordId, JSONObject body) {
    try {
      List<JSONObject> operations = new ArrayList<>();
      String token = parse(body, operations);
      InvoiceInboxEditGate.Opened opened = InvoiceInboxEditGate.open(recordId, token);
      if (opened.refusal() != null) {
        return opened.refusal();
      }
      return edit(opened.row(), operations);
    } catch (Refusal refusal) {
      return refusal.response;
    } catch (JSONException e) {
      throw new IllegalStateException("Could not apply the operations", e);
    }
  }

  // ── Request shape ────────────────────────────────────────────────────────────────────────────

  /** Validates the shape of the body; fills the operations and returns the token. */
  private static String parse(JSONObject body, List<JSONObject> operations) {
    if (body == null) {
      throw invalidRequest();
    }
    if (body.toString().length() > MAX_BODY_CHARS) {
      throw new Refusal(InvoiceInboxEditGate.refuse(413, CODE_REQUEST_TOO_LARGE,
          InvoiceInboxMessages.APPLY_TOO_LARGE, null));
    }
    Iterator<?> keys = body.keys();
    while (keys.hasNext()) {
      String key = (String) keys.next();
      if (!KEY_UPDATED.equals(key) && !KEY_OPERATIONS.equals(key)) {
        throw invalidRequest();
      }
    }
    String token = tokenOf(body);
    JSONArray array = body.optJSONArray(KEY_OPERATIONS);
    if (array == null || array.length() < 1 || array.length() > MAX_OPERATIONS) {
      throw invalidRequest();
    }
    for (int i = 0; i < array.length(); i++) {
      JSONObject op = array.optJSONObject(i);
      validateShape(op);
      operations.add(op);
    }
    return token;
  }

  private static String tokenOf(JSONObject body) {
    Object updated = body.opt(KEY_UPDATED);
    if (updated == null || JSONObject.NULL.equals(updated)
        || (updated instanceof String && StringUtils.isBlank((String) updated))) {
      throw new Refusal(InvoiceInboxEditGate.refuse(400, CODE_MISSING_UPDATED,
          InvoiceInboxMessages.MISSING_UPDATED, null));
    }
    if (!(updated instanceof String)) {
      throw invalidRequest();
    }
    return ((String) updated).trim();
  }

  private static Refusal invalidRequest() {
    return new Refusal(InvoiceInboxEditGate.refuse(400, CODE_INVALID_REQUEST,
        InvoiceInboxMessages.INVALID_REQUEST, null, KEY_MAX, String.valueOf(MAX_OPERATIONS)));
  }

  /** Unknown operation, unknown key, or a structural key of the wrong type: 400. */
  private static void validateShape(JSONObject op) {
    if (op == null || !(op.opt(KEY_OP) instanceof String)) {
      throw invalidRequest();
    }
    Set<String> allowed = OP_KEYS.get(op.optString(KEY_OP));
    if (allowed == null) {
      throw invalidRequest();
    }
    Iterator<?> keys = op.keys();
    while (keys.hasNext()) {
      if (!allowed.contains((String) keys.next())) {
        throw invalidRequest();
      }
    }
    if (allowed.contains(K_INDEX) && !isIndex(op.opt(K_INDEX))) {
      throw invalidRequest();
    }
    if (allowed.contains(KEY_ID) && !isId(op.opt(KEY_ID))) {
      throw invalidRequest();
    }
    Object taxId = op.opt(KEY_TAX_ID);
    if (taxId != null && !JSONObject.NULL.equals(taxId) && !isId(taxId)) {
      throw invalidRequest();
    }
    if (OP_NEW_VENDOR.equals(op.optString(KEY_OP)) && op.has(KEY_FIELDS)) {
      validateVendorFields(op.opt(KEY_FIELDS));
    }
    if (OP_NEW_PRODUCT.equals(op.optString(KEY_OP))) {
      for (String key : new String[] { K_CATEGORY_ID, K_UOM_ID }) {
        Object value = op.opt(key);
        if (value != null && !JSONObject.NULL.equals(value) && !isId(value)) {
          throw invalidRequest();
        }
      }
    }
  }

  private static void validateVendorFields(Object fields) {
    if (!(fields instanceof JSONObject)) {
      throw invalidRequest();
    }
    Iterator<?> keys = ((JSONObject) fields).keys();
    while (keys.hasNext()) {
      if (!VENDOR_FIELDS.contains((String) keys.next())) {
        throw invalidRequest();
      }
    }
  }

  private static boolean isIndex(Object value) {
    return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() >= 0
        && ((Number) value).longValue() <= Integer.MAX_VALUE;
  }

  private static boolean isId(Object value) {
    return value instanceof String && !StringUtils.isBlank((String) value)
        && ((String) value).length() <= MAX_ID_LENGTH;
  }

  // ── The edit ─────────────────────────────────────────────────────────────────────────────────

  private static NeoResponse edit(IncomingDocument row, List<JSONObject> operations)
      throws JSONException {
    JSONObject document = workingCopy(row);
    Optional<OcrLookups> lookups = InvoiceInboxReview.lookupsFor(row.getTargetType());
    Edit edit = new Edit(document, lookups.orElse(null), row);
    // Admin mode, as the worker and every NEO request: the selector-based lookups (address, taxes,
    // duplicates) read the Schema Forge configuration, which a business role cannot. The DAL still
    // filters every query by the role's readable clients and organizations.
    OBContext.setAdminMode(true);
    try {
      for (JSONObject op : operations) {
        run(edit, op);
      }
      if (edit.dirty) {
        recompute(edit);
      }
    } finally {
      OBContext.restorePreviousMode();
    }
    if (!edit.dirty) {
      return InvoiceInboxReview.respond(row);
    }
    InvoiceInboxValidator.Result verdict = InvoiceInboxValidator.validate(document);
    document.put(K_VALIDATION, verdict.toJson());
    String stored = document.toString();
    if (stored.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
      return InvoiceInboxEditGate.refuse(413, CODE_DOCUMENT_TOO_LARGE,
          InvoiceInboxMessages.DOCUMENT_TOO_LARGE, null);
    }
    if (STATUS_READY.equals(row.getStatus())) {
      // Editing a document that was marked as reviewed sends it back to the user.
      row.setStatus(PENDING_REVIEW);
      row.setReviewed(false);
    }
    InvoiceInboxStatusPolicy.applyAfterEdit(row, verdict);
    if (edit.marked) {
      row.setAdjusted(true);
    }
    row.setCurrentData(stored);
    syncSummary(document, row);
    OBDal.getInstance().save(row);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(row);
    return InvoiceInboxReview.respond(row);
  }

  /**
   * Copies the header into the summary columns in admin mode, as the worker does. The summary links
   * entities by ids that were found in admin mode (the duplicate invoice) or validated through the
   * tenant-checked lookups (the vendor); {@code OBDal.get} by id adds no organization filter, so as
   * the user's role it could throw an {@code OBSecurityException} for an invoice of an organization
   * the role cannot read and roll the whole edit back. Admin mode only skips the entity-access
   * check: it widens nothing the user sees (the ids come from queries that already applied the
   * role's readable clients and organizations, and the review answer never exposes the linked
   * entities of these columns).
   */
  private static void syncSummary(JSONObject document, IncomingDocument row) {
    OBContext.setAdminMode(true);
    try {
      InvoiceInboxSummary.syncFrom(document, row);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** The stored document, or the empty skeleton, with its blocks present and its lines indexed. */
  private static JSONObject workingCopy(IncomingDocument row) throws JSONException {
    JSONObject document = null;
    if (!StringUtils.isBlank(row.getCurrentData())) {
      try {
        document = new JSONObject(row.getCurrentData());
      } catch (JSONException e) {
        document = null;
      }
    }
    JSONObject skeleton = InvoiceInboxReview.skeleton();
    if (document == null || document.optJSONObject(K_HEADER) == null) {
      document = skeleton;
    }
    JSONObject header = document.getJSONObject(K_HEADER);
    JSONObject emptyHeader = skeleton.getJSONObject(K_HEADER);
    for (String key : new String[] { K_VENDOR, K_DUPLICATE }) {
      if (header.optJSONObject(key) == null) {
        header.put(key, emptyHeader.get(key));
      }
    }
    if (document.optJSONArray(K_LINES) == null) {
      document.put(K_LINES, new JSONArray());
    }
    if (document.optJSONArray(K_NOTICES) == null) {
      document.put(K_NOTICES, new JSONArray());
    }
    indexLines(document.getJSONArray(K_LINES));
    return document;
  }

  /** Gives every line a unique numeric index (the position for a document that has none). */
  private static void indexLines(JSONArray lines) throws JSONException {
    Set<Integer> used = new LinkedHashSet<>();
    List<JSONObject> missing = new ArrayList<>();
    for (int i = 0; i < lines.length(); i++) {
      JSONObject line = lines.optJSONObject(i);
      if (line == null) {
        continue;
      }
      Object index = line.opt(K_INDEX);
      if (isIndex(index) && used.add(((Number) index).intValue())) {
        continue;
      }
      missing.add(line);
    }
    int next = used.isEmpty() ? 0 : used.stream().mapToInt(Integer::intValue).max().getAsInt() + 1;
    for (JSONObject line : missing) {
      line.put(K_INDEX, next++);
    }
  }

  private static void run(Edit edit, JSONObject op) throws JSONException {
    switch (op.optString(KEY_OP)) {
      case OP_SET_HEADER:
        setHeader(edit, op);
        break;
      case OP_SET_LINE:
        setLine(edit, op);
        break;
      case OP_ADD_LINE:
        addLine(edit);
        break;
      case OP_REMOVE_LINE:
        removeLine(edit, op);
        break;
      case OP_SELECT_VENDOR:
        selectVendor(edit, op);
        break;
      case OP_NEW_VENDOR:
        newVendor(edit, op);
        break;
      case OP_SELECT_PRODUCT:
        selectProduct(edit, op);
        break;
      default:
        newProduct(edit, op);
        break;
    }
  }

  // ── Header and lines ─────────────────────────────────────────────────────────────────────────

  private static void setHeader(Edit edit, JSONObject op) throws JSONException {
    JSONObject header = edit.header();
    if (op.has(K_DOCUMENT_NO)) {
      String cleaned = cleanText(op.get(K_DOCUMENT_NO), K_DOCUMENT_NO, null);
      if (!cleaned.equals(text(header, K_DOCUMENT_NO))) {
        header.put(K_DOCUMENT_NO, cleaned);
        edit.docNoChanged = true;
        edit.changed(InvoiceInboxAdjustedPolicy.valueEdit(true));
      }
    }
    if (op.has(K_INVOICE_DATE)) {
      String date = isoDate(op.get(K_INVOICE_DATE));
      if (!date.equals(text(header, K_INVOICE_DATE))) {
        header.put(K_INVOICE_DATE, date.isEmpty() ? JSONObject.NULL : date);
        edit.dateChanged = true;
        edit.changed(InvoiceInboxAdjustedPolicy.valueEdit(true));
      }
    }
  }

  /** {@code yyyy-MM-dd} in 1900-2100, or empty for {@code null}/blank (clears the date). */
  private static String isoDate(Object value) {
    if (JSONObject.NULL.equals(value)) {
      return "";
    }
    if (!(value instanceof String)) {
      throw invalidValue(K_INVOICE_DATE, null);
    }
    String date = ((String) value).trim();
    if (date.isEmpty()) {
      return "";
    }
    try {
      if (!ISO_DATE.matcher(date).matches()) {
        throw invalidValue(K_INVOICE_DATE, null);
      }
      int year = LocalDate.parse(date).getYear();
      if (year < MIN_YEAR || year > MAX_YEAR) {
        throw invalidValue(K_INVOICE_DATE, null);
      }
    } catch (DateTimeParseException e) {
      throw invalidValue(K_INVOICE_DATE, null);
    }
    return date;
  }

  private static void setLine(Edit edit, JSONObject op) throws JSONException {
    int index = op.getInt(K_INDEX);
    JSONObject line = lineOf(edit, index);
    if (op.has(K_DESCRIPTION)) {
      String cleaned = cleanText(op.get(K_DESCRIPTION), K_DESCRIPTION, index);
      if (!cleaned.equals(text(line, K_DESCRIPTION))) {
        line.put(K_DESCRIPTION, cleaned);
        edit.changed(InvoiceInboxAdjustedPolicy.valueEdit(true));
      }
    }
    if (op.has(K_QUANTITY)) {
      setNumber(edit, line, K_QUANTITY, op.get(K_QUANTITY), index, MAX_AMOUNT.negate(),
          MAX_AMOUNT, QUANTITY_SCALE);
    }
    if (op.has(K_UNIT_PRICE)) {
      setNumber(edit, line, K_UNIT_PRICE, op.get(K_UNIT_PRICE), index, MAX_AMOUNT.negate(),
          MAX_AMOUNT, PRICE_SCALE);
    }
    // The tax chosen by id wins over a rate typed in the same operation: the rate is not applied at all.
    boolean taxIdWins = op.has(KEY_TAX_ID) && !JSONObject.NULL.equals(op.get(KEY_TAX_ID));
    if (op.has(K_TAX_RATE) && !taxIdWins && setNumber(edit, line, K_TAX_RATE, op.get(K_TAX_RATE), index,
        BigDecimal.ZERO, HUNDRED, RATE_SCALE)) {
      // A typed rate (legacy way of choosing a tax) drops a chosen tax: the tax is resolved again.
      forgetChosenTax(line);
      edit.taxLines.add(index);
    }
    if (op.has(KEY_TAX_ID)) {
      setTax(edit, line, index, op.get(KEY_TAX_ID));
    }
    if (op.has(K_DISCOUNT)) {
      setDiscount(edit, line, op.get(K_DISCOUNT), index);
    }
  }

  /**
   * Sets a number of a line ({@code null} stores a null). Equal at the stored scale means no
   * change. Returns whether the stored value changed.
   */
  private static boolean setNumber(Edit edit, JSONObject line, String field, Object value,
      int index, BigDecimal min, BigDecimal max, int scale) throws JSONException {
    BigDecimal parsed = JSONObject.NULL.equals(value) ? null
        : parseNumber(value, field, index, min, max, scale);
    BigDecimal stored = storedNumber(line, field, scale);
    if (parsed == null ? stored == null : (stored != null && parsed.compareTo(stored) == 0)) {
      return false;
    }
    line.put(field, parsed == null ? JSONObject.NULL : (Object) Double.valueOf(
        parsed.doubleValue()));
    edit.changed(InvoiceInboxAdjustedPolicy.valueEdit(true));
    return true;
  }

  /** The discount: {@code null} or zero means none (the key is removed); absent equals zero. */
  private static void setDiscount(Edit edit, JSONObject line, Object value, int index)
      throws JSONException {
    BigDecimal parsed = JSONObject.NULL.equals(value) ? BigDecimal.ZERO
        : parseNumber(value, K_DISCOUNT, index, BigDecimal.ZERO, HUNDRED, DISCOUNT_SCALE);
    BigDecimal stored = storedNumber(line, K_DISCOUNT, DISCOUNT_SCALE);
    if (parsed.compareTo(stored == null ? BigDecimal.ZERO : stored) == 0) {
      return;
    }
    if (parsed.signum() == 0) {
      line.remove(K_DISCOUNT);
    } else {
      line.put(K_DISCOUNT, parsed.doubleValue());
    }
    edit.changed(InvoiceInboxAdjustedPolicy.valueEdit(true));
  }

  /** Whether the line's tax is one the user chose (as opposed to the one the server resolved). */
  private static boolean isChosenTax(JSONObject line) {
    JSONObject tax = line.optJSONObject(K_TAX);
    return tax != null && MATCHED_MANUAL.equals(tax.optString(K_MATCHED_BY));
  }

  private static void forgetChosenTax(JSONObject line) {
    JSONObject tax = line.optJSONObject(K_TAX);
    if (tax != null) {
      tax.remove(K_MATCHED_BY);
    }
  }

  /**
   * Chooses the tax of a line by id, as the purchase invoice line's tax selector does (ETP-6050).
   * The id must pass {@link OcrLookups#taxById} for the document date, else 422
   * {@code unknown_tax}. The CHOSEN TAX WINS: the line's {@code taxRate} becomes its rate (what the
   * totals read), its {@code tax} block becomes {@code {state: resolved, matchedBy: manual, id,
   * rate}} and the name is never stored (the review adds it at read time). {@code null} drops a
   * chosen tax: the server resolves one again, from the rate and the vendor's address. Choosing
   * the id already chosen changes nothing; choosing the id the server resolved only pins it.
   */
  private static void setTax(Edit edit, JSONObject line, int index, Object value)
      throws JSONException {
    JSONObject tax = line.optJSONObject(K_TAX);
    boolean chosen = isChosenTax(line);
    if (JSONObject.NULL.equals(value)) {
      if (chosen) {
        forgetChosenTax(line);
        edit.taxLines.add(index);
        edit.changed(InvoiceInboxAdjustedPolicy.valueEdit(true));
      }
      return;
    }
    String id = (String) value;
    boolean sameId = tax != null && id.equals(tax.optString(K_ID));
    if (chosen && sameId) {
      return;
    }
    if (sameId && T_RESOLVED.equals(tax.optString(K_STATE))) {
      tax.put(K_MATCHED_BY, MATCHED_MANUAL);
      edit.taxLines.remove(index);
      // The line follows the tax it now pins: its rate is the tax's (the totals read it first). Only
      // a rate that actually changes marks; the pin itself does not.
      BigDecimal taxRate = storedNumber(tax, K_RATE, RATE_SCALE);
      boolean rateChanged = taxRate != null
          && (storedNumber(line, K_TAX_RATE, RATE_SCALE) == null
              || taxRate.compareTo(storedNumber(line, K_TAX_RATE, RATE_SCALE)) != 0);
      if (rateChanged) {
        line.put(K_TAX_RATE, taxRate.doubleValue());
      }
      edit.changed(InvoiceInboxAdjustedPolicy.valueEdit(rateChanged));
      return;
    }
    String date = text(edit.header(), K_INVOICE_DATE);
    Optional<OcrLookups.Tax> found = edit.lookups == null ? Optional.empty()
        : edit.lookups.taxById(id, date.isEmpty() ? null : date);
    if (found.isEmpty()) {
      throw new Refusal(InvoiceInboxEditGate.refuse(422, CODE_UNKNOWN_TAX,
          InvoiceInboxMessages.UNKNOWN_TAX, InvoiceInboxEditGate.details(K_INDEX, index)));
    }
    BigDecimal rate = found.get().rate() == null ? null
        : found.get().rate().setScale(RATE_SCALE, RoundingMode.HALF_UP);
    line.put(K_TAX, new JSONObject().put(K_STATE, T_RESOLVED).put(K_MATCHED_BY, MATCHED_MANUAL)
        .put(K_ID, found.get().id()).put(K_RATE, rate == null ? JSONObject.NULL
            : (Object) rate.doubleValue()));
    // On purpose: a chosen tax's rate goes to taxRate as it is (scale 2), WITHOUT the 0-100 clamp a
    // typed rate gets: withholding taxes can carry a negative rate in Etendo.
    line.put(K_TAX_RATE, rate == null ? JSONObject.NULL : (Object) rate.doubleValue());
    edit.taxLines.remove(index);
    edit.inheritRate.remove(index);
    edit.changed(InvoiceInboxAdjustedPolicy.valueEdit(true));
  }

  private static void addLine(Edit edit) throws JSONException {
    JSONArray lines = edit.lines();
    if (lines.length() >= MAX_LINES) {
      throw new Refusal(InvoiceInboxEditGate.refuse(422, CODE_TOO_MANY_LINES,
          InvoiceInboxMessages.TOO_MANY_LINES, InvoiceInboxEditGate.details(KEY_MAX, MAX_LINES),
          KEY_MAX, String.valueOf(MAX_LINES)));
    }
    int index = nextIndex(lines);
    JSONObject previous = lines.length() == 0 ? null : lines.optJSONObject(lines.length() - 1);
    BigDecimal rate = previous == null ? null : storedNumber(previous, K_TAX_RATE, RATE_SCALE);
    JSONObject line = new JSONObject().put(K_INDEX, index).put(K_DESCRIPTION, "")
        .put(K_QUANTITY, 1).put(K_UNIT_PRICE, 0)
        .put(K_TAX_RATE, rate == null ? JSONObject.NULL : (Object) rate.doubleValue())
        .put(K_TAX_LABEL, "")
        .put(K_PRODUCT, new JSONObject().put(K_STATE, S_NONE).put(K_CANDIDATES, new JSONArray()))
        .put(K_TAX, new JSONObject().put(K_STATE, T_PENDING_VENDOR));
    lines.put(line);
    edit.taxLines.add(index);
    if (rate == null) {
      // No rate to follow: the line takes the default tax of the vendor's address, and its rate.
      edit.inheritRate.add(index);
    }
    edit.changed(true);
  }

  private static void removeLine(Edit edit, JSONObject op) throws JSONException {
    int index = op.getInt(K_INDEX);
    lineOf(edit, index);
    JSONArray kept = new JSONArray();
    JSONArray lines = edit.lines();
    for (int i = 0; i < lines.length(); i++) {
      JSONObject line = lines.optJSONObject(i);
      if (line == null || line.optInt(K_INDEX, -1) != index) {
        kept.put(lines.get(i));
      }
    }
    edit.document.put(K_LINES, kept);
    edit.changed(true);
  }

  private static int nextIndex(JSONArray lines) {
    int max = -1;
    for (int i = 0; i < lines.length(); i++) {
      JSONObject line = lines.optJSONObject(i);
      max = Math.max(max, line == null ? -1 : line.optInt(K_INDEX, -1));
    }
    return max + 1;
  }

  private static JSONObject lineOf(Edit edit, int index) {
    JSONArray lines = edit.lines();
    for (int i = 0; i < lines.length(); i++) {
      JSONObject line = lines.optJSONObject(i);
      if (line != null && line.optInt(K_INDEX, -1) == index) {
        return line;
      }
    }
    throw new Refusal(InvoiceInboxEditGate.refuse(422, CODE_LINE_NOT_FOUND,
        InvoiceInboxMessages.LINE_NOT_FOUND, InvoiceInboxEditGate.details(K_INDEX, index)));
  }

  // ── Vendor ───────────────────────────────────────────────────────────────────────────────────

  private static JSONObject vendorOf(Edit edit) {
    return edit.header().optJSONObject(K_VENDOR);
  }

  private static void selectVendor(Edit edit, JSONObject op) throws JSONException {
    String id = op.getString(KEY_ID);
    JSONObject vendor = vendorOf(edit);
    boolean alreadyLinked = S_LINKED.equals(vendor.optString(K_STATE)) && id.equals(
        vendor.optString(K_ID));
    if (alreadyLinked) {
      return;
    }
    Optional<Party> found = edit.lookups == null ? Optional.empty() : edit.lookups.vendorById(id);
    if (found.isEmpty()) {
      throw new Refusal(InvoiceInboxEditGate.refuse(422, CODE_UNKNOWN_VENDOR,
          InvoiceInboxMessages.UNKNOWN_VENDOR, null));
    }
    Address address = edit.lookups.vendorAddress(id);
    boolean present = address.state() == OcrLookups.AddressState.PRESENT
        && address.locationId() != null;
    vendor.put(K_STATE, S_LINKED).put(K_MATCHED_BY, MATCHED_MANUAL).put(K_ID, found.get().id())
        .put(K_NAME, found.get().name()).put(K_ADDRESS_STATE, address.state().name())
        .put(K_LOCATION_ID, present ? address.locationId() : JSONObject.NULL);
    vendor.remove("details");
    edit.vendorChanged = true;
    edit.changed(InvoiceInboxAdjustedPolicy.selection(false));
  }

  private static void newVendor(Edit edit, JSONObject op) throws JSONException {
    JSONObject vendor = vendorOf(edit);
    JSONObject extracted = vendor.optJSONObject(K_EXTRACTED);
    if (extracted == null) {
      extracted = new JSONObject();
    }
    boolean wasLinked = S_LINKED.equals(vendor.optString(K_STATE));
    boolean wasNew = S_NEW.equals(vendor.optString(K_STATE));
    JSONObject fields = op.optJSONObject(KEY_FIELDS);
    boolean anyDiffers = false;
    JSONObject merged = new JSONObject(extracted.toString());
    Iterator<?> keys = fields == null ? new JSONObject().keys() : fields.keys();
    while (keys.hasNext()) {
      String key = (String) keys.next();
      String cleaned = cleanText(fields.get(key), key, null);
      if (!cleaned.equals(text(extracted, key))) {
        merged.put(key, cleaned);
        anyDiffers = true;
      }
    }
    if (wasNew && !anyDiffers) {
      return;
    }
    vendor.remove(K_ID);
    vendor.remove(K_NAME);
    vendor.remove(K_MATCHED_BY);
    vendor.remove("details");
    vendor.put(K_STATE, S_NEW).put(K_EXTRACTED, merged).put(K_ADDRESS_STATE, A_UNKNOWN)
        .put(K_LOCATION_ID, JSONObject.NULL);
    if (vendor.optJSONArray(K_CANDIDATES) == null) {
      vendor.put(K_CANDIDATES, new JSONArray());
    }
    edit.vendorChanged = true;
    edit.changed(InvoiceInboxAdjustedPolicy.newVendor(wasLinked, anyDiffers));
  }

  // ── Product ──────────────────────────────────────────────────────────────────────────────────

  private static void selectProduct(Edit edit, JSONObject op) throws JSONException {
    int index = op.getInt(K_INDEX);
    JSONObject line = lineOf(edit, index);
    String id = op.getString(KEY_ID);
    JSONObject product = line.optJSONObject(K_PRODUCT);
    boolean alreadyLinked = product != null && S_LINKED.equals(product.optString(K_STATE))
        && id.equals(product.optString(K_ID));
    if (alreadyLinked) {
      return;
    }
    Optional<Party> found = edit.lookups == null ? Optional.empty()
        : edit.lookups.productById(id);
    if (found.isEmpty()) {
      throw new Refusal(InvoiceInboxEditGate.refuse(422, CODE_UNKNOWN_PRODUCT,
          InvoiceInboxMessages.UNKNOWN_PRODUCT, InvoiceInboxEditGate.details(K_INDEX, index)));
    }
    line.put(K_PRODUCT, new JSONObject().put(K_STATE, S_LINKED).put(K_MATCHED_BY, MATCHED_MANUAL)
        .put(K_ID, found.get().id()).put(K_NAME, found.get().name())
        .put(K_CANDIDATES, candidatesOf(product)));
    edit.changed(InvoiceInboxAdjustedPolicy.selection(false));
  }

  private static void newProduct(Edit edit, JSONObject op) throws JSONException {
    int index = op.getInt(K_INDEX);
    JSONObject line = lineOf(edit, index);
    JSONObject product = line.optJSONObject(K_PRODUCT);
    boolean wasLinked = product != null && S_LINKED.equals(product.optString(K_STATE));
    boolean wasNew = product != null && S_NEW.equals(product.optString(K_STATE));
    JSONObject stored = product == null ? null : product.optJSONObject(K_NEW);
    String name = op.has(K_NAME) ? cleanText(op.get(K_NAME), K_NAME, index)
        : (stored != null && stored.has(K_NAME) ? text(stored, K_NAME)
            : text(line, K_DESCRIPTION));
    JSONObject draft = new JSONObject().put(K_NAME, name);
    resolveChoice(edit, op, stored, draft, index, true);
    resolveChoice(edit, op, stored, draft, index, false);
    boolean nameDiffers = !name.equals(stored != null && stored.has(K_NAME)
        ? text(stored, K_NAME) : text(line, K_DESCRIPTION));
    boolean same = wasNew && stored != null && sameDraft(stored, draft);
    if (same) {
      return;
    }
    line.put(K_PRODUCT, new JSONObject().put(K_STATE, S_NEW).put(K_ID, JSONObject.NULL)
        .put(K_CANDIDATES, candidatesOf(product)).put(K_NEW, draft));
    edit.changed(InvoiceInboxAdjustedPolicy.newProduct(true, wasLinked, nameDiffers));
  }

  /**
   * Resolves the category (or the unit) of the draft: the one asked for (its id is checked), the
   * stored id when the operation does not mention it, none when {@code null}. Only the ID is stored:
   * the name is a display value that {@link InvoiceInboxReview} adds at read time in the language of
   * the request, so a stored copy can never be stale or in another language. A name stored by an
   * earlier version is ignored here and dropped on the next write.
   */
  private static void resolveChoice(Edit edit, JSONObject op, JSONObject stored, JSONObject draft,
      int index, boolean category) throws JSONException {
    String idKey = category ? K_CATEGORY_ID : K_UOM_ID;
    if (!op.has(idKey)) {
      if (stored != null && !StringUtils.isBlank(text(stored, idKey))) {
        draft.put(idKey, text(stored, idKey));
      }
      return;
    }
    Object value = op.get(idKey);
    if (JSONObject.NULL.equals(value)) {
      return;
    }
    Optional<Party> found = edit.lookups == null ? Optional.empty()
        : (category ? edit.lookups.productCategoryById((String) value)
            : edit.lookups.uomById((String) value));
    if (found.isEmpty()) {
      throw new Refusal(InvoiceInboxEditGate.refuse(422,
          category ? CODE_UNKNOWN_CATEGORY : CODE_UNKNOWN_UOM,
          category ? InvoiceInboxMessages.UNKNOWN_CATEGORY : InvoiceInboxMessages.UNKNOWN_UOM,
          InvoiceInboxEditGate.details(K_INDEX, index)));
    }
    draft.put(idKey, found.get().id());
  }

  private static boolean sameDraft(JSONObject stored, JSONObject draft) {
    return text(stored, K_NAME).equals(text(draft, K_NAME))
        && text(stored, K_CATEGORY_ID).equals(text(draft, K_CATEGORY_ID))
        && text(stored, K_UOM_ID).equals(text(draft, K_UOM_ID));
  }

  private static JSONArray candidatesOf(JSONObject product) throws JSONException {
    JSONArray candidates = product == null ? null : product.optJSONArray(K_CANDIDATES);
    return candidates == null ? new JSONArray() : new JSONArray(candidates.toString());
  }

  // ── Recompute ────────────────────────────────────────────────────────────────────────────────

  private static void recompute(Edit edit) throws JSONException {
    rebuildTaxes(edit);
    if (edit.lookups != null) {
      InboxTaxModes.stamp(edit.lines(), edit.lookups);
    }
    if (edit.docNoChanged || edit.vendorChanged) {
      recheckDuplicates(edit);
    }
    if (edit.vendorChanged) {
      JSONObject vendor = vendorOf(edit);
      setNotice(edit, N_VENDOR_ADDRESS_MISSING, S_LINKED.equals(vendor.optString(K_STATE))
          && OcrLookups.AddressState.MISSING.name().equals(vendor.optString(K_ADDRESS_STATE)));
    }
    refreshTotal(edit, ensureCurrency(edit));
  }

  /**
   * The document currency: derived from the vendor (its purchase price list, else the
   * organization's) and stored in the header when the document has none yet or the vendor changed,
   * kept otherwise. Returns the decimals of its amounts, two when it cannot be known. The exchange
   * rate and the currency picker of the invoice are not touched: this is the scale of the inbox's
   * own amounts.
   */
  private static int ensureCurrency(Edit edit) throws JSONException {
    if (edit.lookups == null) {
      return InvoiceInboxTotals.AMOUNT_SCALE;
    }
    JSONObject header = edit.header();
    String stored = text(header, K_CURRENCY_ID);
    if (stored.isEmpty() || edit.vendorChanged) {
      JSONObject vendor = vendorOf(edit);
      String vendorId = S_LINKED.equals(vendor.optString(K_STATE)) ? vendor.optString(K_ID) : null;
      Optional<OcrLookups.DocCurrency> derived = edit.lookups.documentCurrency(vendorId,
          edit.row.getOrganization() == null ? null : edit.row.getOrganization().getId());
      if (derived.isPresent()) {
        header.put(K_CURRENCY_ID, derived.get().id());
        return derived.get().scale();
      }
      header.remove(K_CURRENCY_ID);
      return InvoiceInboxTotals.AMOUNT_SCALE;
    }
    return edit.lookups.currencyById(stored).map(OcrLookups.DocCurrency::scale)
        .orElse(InvoiceInboxTotals.AMOUNT_SCALE);
  }

  /** The tax of the lines that depend on what changed, once per distinct rate. */
  private static void rebuildTaxes(Edit edit) throws JSONException {
    if (edit.lookups == null) {
      return;
    }
    boolean all = edit.vendorChanged || edit.dateChanged;
    String location = locationOf(vendorOf(edit));
    String date = text(edit.header(), K_INVOICE_DATE);
    Map<String, TaxAnswer> cache = new HashMap<>();
    JSONArray lines = edit.lines();
    for (int i = 0; i < lines.length(); i++) {
      JSONObject line = lines.optJSONObject(i);
      int index = line == null ? -1 : line.optInt(K_INDEX, -1);
      if (line == null || !(all || edit.taxLines.contains(index))) {
        continue;
      }
      if (isChosenTax(line) && stillValid(edit, line, date)) {
        continue;
      }
      BigDecimal rate = storedNumber(line, K_TAX_RATE, RATE_SCALE);
      JSONObject tax = InboxLineMatcher.taxState(edit.lookups, cache, rate, location,
          date.isEmpty() ? null : date);
      line.put(K_TAX, tax);
      if (edit.inheritRate.contains(index) && rate == null
          && T_RESOLVED.equals(tax.optString(K_STATE)) && !tax.isNull(K_RATE)) {
        line.put(K_TAX_RATE, tax.get(K_RATE));
      }
    }
  }

  /** A tax the user chose survives a vendor or date change while it still passes the tax rule. */
  private static boolean stillValid(Edit edit, JSONObject line, String date) {
    return edit.lookups.taxById(line.optJSONObject(K_TAX).optString(K_ID),
        date.isEmpty() ? null : date).isPresent();
  }

  /** The vendor's address when it is linked and has one, else {@code null} (taxes stay pending). */
  private static String locationOf(JSONObject vendor) {
    boolean usable = S_LINKED.equals(vendor.optString(K_STATE))
        && A_PRESENT.equals(vendor.optString(K_ADDRESS_STATE)) && !vendor.isNull(K_LOCATION_ID);
    return usable ? StringUtils.defaultIfBlank(vendor.optString(K_LOCATION_ID), null) : null;
  }

  /** The duplicate block and its notices, asked again because the number or the vendor changed. */
  private static void recheckDuplicates(Edit edit) throws JSONException {
    JSONObject header = edit.header();
    JSONObject vendor = vendorOf(edit);
    String documentNo = text(header, K_DOCUMENT_NO);
    JSONObject duplicate = new JSONObject().put(K_STATE, D_NONE).put(K_INVOICES, new JSONArray());
    boolean unknown = false;
    if (edit.lookups != null && S_LINKED.equals(vendor.optString(K_STATE))
        && !documentNo.isEmpty()) {
      Duplicates found = edit.lookups.duplicates(vendor.optString(K_ID), documentNo);
      if (!found.known()) {
        unknown = true;
        duplicate.put(K_STATE, D_UNKNOWN);
      } else {
        JSONArray invoices = new JSONArray();
        for (OcrLookups.Duplicate document : found.documents()) {
          invoices.put(new JSONObject().put(K_ID, document.id()).put(K_DOCUMENT_NO,
              document.documentNo() == null ? "" : document.documentNo()));
        }
        duplicate.put(K_STATE, invoices.length() > 0 ? D_FOUND : D_NONE).put(K_INVOICES, invoices);
      }
    }
    header.put(K_DUPLICATE, duplicate);
    JSONArray inbox = InvoiceInboxDuplicates.find(edit.row, header,
        java.util.Arrays.asList(OBContext.getOBContext().getReadableOrganizations()));
    duplicate.put(K_INBOX, inbox);
    setNotice(edit, N_DUPLICATE_CHECK_UNKNOWN, unknown);
    setNotice(edit, N_DUPLICATE_IN_INBOX, inbox.length() > 0);
  }

  /** The total follows the lines unless the document printed one (which never changes). */
  private static void refreshTotal(Edit edit, int scale) throws JSONException {
    JSONObject header = edit.header();
    if (TOTAL_PRINTED.equals(header.optString(K_TOTAL_SOURCE))) {
      return;
    }
    InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(edit.lines(), scale);
    header.put(K_TOTAL_AMOUNT, totals == null ? 0 : totals.total().doubleValue());
  }

  private static void setNotice(Edit edit, String code, boolean present) throws JSONException {
    JSONArray notices = edit.document.optJSONArray(K_NOTICES);
    JSONArray out = new JSONArray();
    boolean had = false;
    for (int i = 0; notices != null && i < notices.length(); i++) {
      if (code.equals(notices.optString(i))) {
        had = true;
        if (!present) {
          continue;
        }
      }
      out.put(notices.get(i));
    }
    if (present && !had) {
      out.put(code);
    }
    edit.document.put(K_NOTICES, out);
  }

  // ── Values ───────────────────────────────────────────────────────────────────────────────────

  private static Refusal invalidValue(String field, Integer index) {
    JSONObject details = InvoiceInboxEditGate.details(KEY_FIELD, field);
    try {
      if (index != null) {
        details.put(K_INDEX, index.intValue());
      }
    } catch (JSONException e) {
      throw new IllegalStateException("Could not build the error details", e);
    }
    return new Refusal(InvoiceInboxEditGate.refuse(422, CODE_INVALID_VALUE,
        InvoiceInboxMessages.INVALID_VALUE, details, KEY_FIELD, field));
  }

  /** A text of the request, cleaned like an extracted one; {@code null} reads as empty. */
  private static String cleanText(Object value, String field, Integer index) {
    if (JSONObject.NULL.equals(value)) {
      return "";
    }
    if (!(value instanceof String)) {
      throw invalidValue(field, index);
    }
    return OcrSanitizer.text((String) value);
  }

  /** A finite JSON number within bounds, rounded HALF_UP to the scale the document stores. */
  private static BigDecimal parseNumber(Object value, String field, Integer index,
      BigDecimal min, BigDecimal max, int scale) {
    if (!(value instanceof Number)) {
      throw invalidValue(field, index);
    }
    double asDouble = ((Number) value).doubleValue();
    if (Double.isNaN(asDouble) || Double.isInfinite(asDouble)) {
      throw invalidValue(field, index);
    }
    BigDecimal parsed;
    try {
      parsed = new BigDecimal(value.toString());
    } catch (NumberFormatException e) {
      throw invalidValue(field, index);
    }
    if (parsed.precision() > MAX_NUMBER_DIGITS || Math.abs(parsed.scale()) > MAX_NUMBER_DIGITS) {
      throw invalidValue(field, index);
    }
    BigDecimal scaled = parsed.setScale(scale, RoundingMode.HALF_UP);
    if (scaled.compareTo(min) < 0 || scaled.compareTo(max) > 0) {
      throw invalidValue(field, index);
    }
    return scaled;
  }

  /** A stored number at the scale the document keeps, {@code null} when absent or null. */
  private static BigDecimal storedNumber(JSONObject object, String key, int scale) {
    Object value = object == null ? null : object.opt(key);
    if (!(value instanceof Number)) {
      return null;
    }
    try {
      return new BigDecimal(value.toString()).setScale(scale, RoundingMode.HALF_UP);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String text(JSONObject object, String key) {
    Object value = object == null ? null : object.opt(key);
    return value instanceof String ? ((String) value).trim() : "";
  }
}
