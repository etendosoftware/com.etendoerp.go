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

import static com.etendoerp.go.schemaforge.InvoiceInboxApplyValues.cleanText;
import static com.etendoerp.go.schemaforge.InvoiceInboxApplyValues.isId;
import static com.etendoerp.go.schemaforge.InvoiceInboxApplyValues.isIndex;
import static com.etendoerp.go.schemaforge.InvoiceInboxApplyValues.isoDate;
import static com.etendoerp.go.schemaforge.InvoiceInboxApplyValues.parseNumber;
import static com.etendoerp.go.schemaforge.InvoiceInboxApplyValues.storedNumber;
import static com.etendoerp.go.schemaforge.InvoiceInboxApplyValues.text;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CANDIDATES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CATEGORY_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DESCRIPTION;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DISCOUNT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DOCUMENT_NO;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DUPLICATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_HEADER;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INDEX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICE_DATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_LINES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_MATCHED_BY;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NAME;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NOTICES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_PRODUCT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_QUANTITY;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_RATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX_LABEL;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX_RATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UNIT_PRICE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UOM_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VALIDATION;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VENDOR;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.MATCHED_MANUAL;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_NONE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.T_PENDING_VENDOR;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.T_RESOLVED;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;
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
 * <p>The line {@code index} is a stable id: never renumbered, a new line takes the highest index
 * plus one. Guards (access, tenant, organization, processing, editable, lock, token):
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
  private static final String KEY_TAX_ID = "taxId";
  private static final String KEY_MAX = "max";
  private static final String PENDING_REVIEW = "PENDING_REVIEW";
  private static final String STATUS_READY = "READY";
  /** Decimals each editable number is rounded (HALF_UP) to and stored with. */
  static final int QUANTITY_SCALE = 4;
  static final int PRICE_SCALE = 6;
  static final int DISCOUNT_SCALE = 4;
  static final int RATE_SCALE = 2;
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  /** The bounds and the decimals of an editable number. */
  private record Range(BigDecimal min, BigDecimal max, int scale) {
  }

  private static final Range QUANTITY_RANGE = new Range(MAX_AMOUNT.negate(), MAX_AMOUNT,
      QUANTITY_SCALE);
  private static final Range PRICE_RANGE = new Range(MAX_AMOUNT.negate(), MAX_AMOUNT, PRICE_SCALE);
  private static final Range RATE_RANGE = new Range(BigDecimal.ZERO, HUNDRED, RATE_SCALE);

  /** The keys the {@code newVendor.fields} patch accepts: the extracted vendor data. */
  private static final Set<String> VENDOR_FIELDS = Set.of(K_NAME, KEY_TAX_ID, "address",
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
  static final class Refusal extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient NeoResponse response;

    Refusal(NeoResponse response) {
      super(null, null, false, false);
      this.response = response;
    }
  }

  /** The state of one request: the working copy and what the operations did to it. */
  static final class Edit {
    final JSONObject document;
    final OcrLookups lookups;
    final IncomingDocument row;
    boolean dirty;
    boolean marked;
    boolean vendorChanged;
    boolean docNoChanged;
    boolean dateChanged;
    final Set<Integer> taxLines = new LinkedHashSet<>();
    final Set<Integer> inheritRate = new LinkedHashSet<>();

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

    JSONObject vendor() {
      return header().optJSONObject(K_VENDOR);
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
   * @return 200 with the review body of the saved row; the refusal otherwise (see 9.2.6 of the
   *         plan)
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
      if (!allowed.contains(keys.next())) {
        throw invalidRequest();
      }
    }
    if (allowed.contains(K_INDEX) && !isIndex(op.opt(K_INDEX))) {
      throw invalidRequest();
    }
    if (allowed.contains(KEY_ID) && !isId(op.opt(KEY_ID))) {
      throw invalidRequest();
    }
    validateOptionalIds(op);
    if (OP_NEW_VENDOR.equals(op.optString(KEY_OP)) && op.has(KEY_FIELDS)) {
      validateVendorFields(op.opt(KEY_FIELDS));
    }
  }

  /** The ids an operation may carry as {@code null} or as a well-formed id: tax, category, unit. */
  private static void validateOptionalIds(JSONObject op) {
    for (String key : new String[] { KEY_TAX_ID, K_CATEGORY_ID, K_UOM_ID }) {
      Object value = op.opt(key);
      if (value != null && !JSONObject.NULL.equals(value) && !isId(value)) {
        throw invalidRequest();
      }
    }
  }

  private static void validateVendorFields(Object fields) {
    if (!(fields instanceof JSONObject)) {
      throw invalidRequest();
    }
    Iterator<?> keys = ((JSONObject) fields).keys();
    while (keys.hasNext()) {
      if (!VENDOR_FIELDS.contains(keys.next())) {
        throw invalidRequest();
      }
    }
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
        InvoiceInboxApplyRecompute.recompute(edit);
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
      if (line != null && !claimsItsIndex(line, used)) {
        missing.add(line);
      }
    }
    int next = used.isEmpty() ? 0 : used.stream().mapToInt(Integer::intValue).max().getAsInt() + 1;
    for (JSONObject line : missing) {
      line.put(K_INDEX, next++);
    }
  }

  /** Whether the line has a numeric index no other line took: it keeps it. */
  private static boolean claimsItsIndex(JSONObject line, Set<Integer> used) {
    Object index = line.opt(K_INDEX);
    return isIndex(index) && used.add(((Number) index).intValue());
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
        InvoiceInboxApplyResolution.selectVendor(edit, op);
        break;
      case OP_NEW_VENDOR:
        InvoiceInboxApplyResolution.newVendor(edit, op);
        break;
      case OP_SELECT_PRODUCT:
        InvoiceInboxApplyResolution.selectProduct(edit, op);
        break;
      default:
        InvoiceInboxApplyResolution.newProduct(edit, op);
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
      setNumber(edit, line, K_QUANTITY, op.get(K_QUANTITY), index, QUANTITY_RANGE);
    }
    if (op.has(K_UNIT_PRICE)) {
      setNumber(edit, line, K_UNIT_PRICE, op.get(K_UNIT_PRICE), index, PRICE_RANGE);
    }
    // The tax chosen by id wins over a rate typed in the same operation: the rate is not applied at
    // all.
    boolean taxIdWins = op.has(KEY_TAX_ID) && !JSONObject.NULL.equals(op.get(KEY_TAX_ID));
    if (op.has(K_TAX_RATE) && !taxIdWins
        && setNumber(edit, line, K_TAX_RATE, op.get(K_TAX_RATE), index, RATE_RANGE)) {
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
      int index, Range range) throws JSONException {
    BigDecimal parsed = JSONObject.NULL.equals(value) ? null
        : parseNumber(value, field, index, range.min(), range.max(), range.scale());
    BigDecimal stored = storedNumber(line, field, range.scale());
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
  static boolean isChosenTax(JSONObject line) {
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
      pinResolvedTax(edit, line, tax, index);
      return;
    }
    chooseNewTax(edit, line, id, index);
  }

  /**
   * Pins the tax the server had resolved: the line follows it, its rate is the tax's (the totals
   * read it first). Only a rate that actually changes marks; the pin itself does not.
   */
  private static void pinResolvedTax(Edit edit, JSONObject line, JSONObject tax, int index)
      throws JSONException {
    tax.put(K_MATCHED_BY, MATCHED_MANUAL);
    edit.taxLines.remove(index);
    BigDecimal taxRate = storedNumber(tax, K_RATE, RATE_SCALE);
    BigDecimal lineRate = storedNumber(line, K_TAX_RATE, RATE_SCALE);
    boolean rateChanged = taxRate != null && (lineRate == null || taxRate.compareTo(lineRate) != 0);
    if (rateChanged) {
      line.put(K_TAX_RATE, taxRate.doubleValue());
    }
    edit.changed(InvoiceInboxAdjustedPolicy.valueEdit(rateChanged));
  }

  /** Chooses a tax the line did not have: it must pass the tax rule for the document date. */
  private static void chooseNewTax(Edit edit, JSONObject line, String id, int index)
      throws JSONException {
    String date = text(edit.header(), K_INVOICE_DATE);
    String dateOrToday = date.isEmpty() ? null : date;
    Optional<OcrLookups.Tax> found = edit.lookups == null ? Optional.empty()
        : edit.lookups.taxById(id, dateOrToday);
    if (found.isEmpty()) {
      throw new Refusal(InvoiceInboxEditGate.refuse(422, CODE_UNKNOWN_TAX,
          InvoiceInboxMessages.UNKNOWN_TAX, InvoiceInboxEditGate.details(K_INDEX, index)));
    }
    BigDecimal rate = found.get().rate() == null ? null
        : found.get().rate().setScale(RATE_SCALE, RoundingMode.HALF_UP);
    Object rateValue = rate == null ? JSONObject.NULL : (Object) rate.doubleValue();
    line.put(K_TAX, new JSONObject().put(K_STATE, T_RESOLVED).put(K_MATCHED_BY, MATCHED_MANUAL)
        .put(K_ID, found.get().id()).put(K_RATE, rateValue));
    // On purpose: a chosen tax's rate goes to taxRate as it is (scale 2), WITHOUT the 0-100 clamp a
    // typed rate gets: withholding taxes can carry a negative rate in Etendo.
    line.put(K_TAX_RATE, rateValue);
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

  static JSONObject lineOf(Edit edit, int index) {
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
}
