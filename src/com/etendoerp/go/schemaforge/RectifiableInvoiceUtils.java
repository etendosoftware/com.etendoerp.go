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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Reads the invoices a return document may rectify (ETP-5381).
 *
 * <p>Split out of {@link ReturnShipmentUtils}, which had grown past Sonar's method ceiling
 * (java:S1448). The cut is along a real seam, not an arbitrary one: everything here ANSWERS
 * "which confirmed invoices can this return rectify, and does it already have an invoice?" —
 * three read-only queries plus the paging that serves them to the picker. Creating the
 * {@code C_Invoice_Reverse} rows stays in {@link ReturnShipmentUtils}, with the rest of the
 * document-building code.
 *
 * <p>Shared by both return header handlers: the logic is identical on the sales and purchase
 * sides, and the flow is distinguished inside the SQL by {@code IsSOTrx}, not by the caller.
 */
final class RectifiableInvoiceUtils {

  private static final Logger log = LogManager.getLogger(RectifiableInvoiceUtils.class);

  private RectifiableInvoiceUtils() {}

  /**
   * Request-facing overload: reads the paging window off the request body and delegates.
   *
   * <p><b>Read from the REQUEST BODY, not the query string</b>, even though the list windows page
   * with {@code _startRow}/{@code _endRow} query parameters. An action endpoint reaches its handler
   * through {@code NeoHookDispatcher#buildHookContext}, which populates {@code recordId} and
   * {@code requestBody} but NOT {@code queryParams} — only {@code NeoRequestRouter} does that. So
   * {@code context.getQueryParams()} is null here, and a query-string implementation would have
   * silently served the first batch forever: no error, no log, just a picker that never paged.
   * The action is already a POST carrying a JSON body, so the body is where the window belongs.
   *
   * <p>Absent keys mean "first batch", which keeps any caller that sends {@code {}} working.
   *
   * @param context the action request, read for {@code startRow}, {@code pageSize} and
   *                {@code search}
   * @param inOutId the return document
   */
  static NeoResponse buildRectifiableInvoicesResponse(NeoContext context, String inOutId)
      throws Exception {
    JSONObject body = context != null ? context.getRequestBody() : null;
    int startRow = parseIntFromBody(body, "startRow", 0);
    int pageSize = parseIntFromBody(body, "pageSize", ReturnShipmentUtils.DEFAULT_RECTIFIABLE_PAGE_SIZE);
    String search = body != null ? body.optString("search", null) : null;
    return buildRectifiableInvoicesResponse(inOutId, startRow, pageSize, search);
  }

  /**
   * Reads a non-negative integer from the request body, falling back to {@code fallback} when it is
   * absent or not a number. A malformed value is treated as absent rather than as an error: the
   * caller is asking for a page of a picker, and failing the whole action over a bad offset would
   * hide the list instead of showing its first batch.
   *
   * <p>Reads through {@code optString} rather than {@code optInt} so a value sent as a JSON string
   * ({@code "80"}) is accepted too — the frontend builds this body from state that may hold either.
   */
  private static int parseIntFromBody(JSONObject body, String name, int fallback) {
    String raw = body != null ? body.optString(name, null) : null;
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return Math.max(0, Integer.parseInt(raw.trim()));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /**
   * Builds the {@code rectifiableInvoices} action payload: the candidate invoices, which one was
   * auto-detected (so the UI can preselect it), and whether this return document already has an
   * invoice (so the UI can disable the option instead of letting the user hit a 409).
   *
   * <p>Shared by both return header handlers — the logic is identical on the sales and purchase
   * sides, only the document type differs, and that is resolved elsewhere.
   *
   * <p><b>Paged and searched server-side (ETP-5381)</b>, mirroring how the invoice list window
   * pages through {@code useEntity}: the caller asks for a window of rows and the response says
   * whether more exist. A slow connection should not wait for the whole candidate set before the
   * picker can open.
   *
   * <p>Two invariants the paging must not break:
   * <ul>
   *   <li><b>The detected rows ride on the FIRST batch only.</b> They are preselected, so a
   *       suggested id whose row has not been fetched yet would render as a chip with no matching
   *       row. Merging them on every batch instead would duplicate them down the list.</li>
   *   <li><b>{@code suggestedInvoiceIds} ships on every batch.</b> It is small, it is the same
   *       answer each time, and the client must not have to remember which batch defined it.</li>
   * </ul>
   *
   * @param inOutId  the return document
   * @param startRow first row to return, 0-based
   * @param pageSize how many rows to return
   * @param search   optional case-insensitive fragment matched against document number and partner
   *                 name; blank means no filter. Applied in SQL, never client-side — the client
   *                 only holds the batches it fetched, so filtering there would silently search a
   *                 subset and report "no matches" for a row that exists further down.
   */
  static NeoResponse buildRectifiableInvoicesResponse(String inOutId, int startRow, int pageSize,
      String search) throws Exception {
    boolean firstBatch = startRow <= 0;
    // The chain-detected rows are flagged so the UI can float them to the top and preselect. The
    // chain only limits the SUGGESTION, never the CHOICE: a return created standalone has no chain
    // at all, and one covering two invoiced shipments needs both.
    List<JSONObject> detected = firstBatch
        ? fetchAutoDetectedInvoices(inOutId)
        : Collections.emptyList();
    Set<String> detectedIds = new HashSet<>();
    for (JSONObject inv : detected) {
      detectedIds.add(inv.optString("id"));
    }
    List<JSONObject> selectable = fetchSelectableInvoices(inOutId, startRow, pageSize, search);
    // A full batch means "there may be more"; a short one is the end of the set. Same signal
    // useEntity reads (`rows.length < BATCH_SIZE` → no more), so the client needs no total count
    // and we avoid a second COUNT(*) per scroll.
    boolean hasMore = selectable.size() >= pageSize;
    Set<String> selectableIds = new HashSet<>();
    for (JSONObject inv : selectable) {
      selectableIds.add(inv.optString("id"));
    }
    // A detected invoice outside the first page must still be offered: otherwise its id ships in
    // suggestedInvoiceIds, the frontend drops it for having no matching row, and the user sees an
    // empty preselection with no error and no way to reach it from the picker.
    JSONArray arr = new JSONArray();
    for (JSONObject inv : detected) {
      if (!selectableIds.contains(inv.optString("id"))) {
        inv.put("suggested", true);
        arr.put(inv);
      }
    }
    for (JSONObject inv : selectable) {
      inv.put("suggested", detectedIds.contains(inv.optString("id")));
      arr.put(inv);
    }
    JSONObject data = new JSONObject();
    data.put("invoices", arr);
    data.put("hasMore", hasMore);
    data.put("startRow", Math.max(0, startRow));
    data.put("hasReturnInvoice", hasNonVoidedReturnInvoice(inOutId));
    // EVERY chain-detected invoice is reported, not just the newest — what the client does with
    // them is the client's call, and this field is also what badges the rows as related.
    //
    // The client no longer preselects them all (ETP-5381): one detected invoice is preselected,
    // two or more are not. An order billed across two invoices and returned once makes the chain
    // find both, and the chain does not know which the user means to rectify. That decision lives
    // in `useRectifiableInvoices`, deliberately — the backend reports what it found, it does not
    // decide what gets rectified. Recomputed rather than carried on the first batch only, so the
    // client never depends on batch order.
    JSONArray suggestedIds = new JSONArray();
    for (JSONObject inv : firstBatch ? detected : fetchAutoDetectedInvoices(inOutId)) {
      suggestedIds.put(inv.optString("id"));
    }
    data.put("suggestedInvoiceIds", suggestedIds);
    return ReturnShipmentUtils.wrapOkData(data);
  }

  /**
   * ETP-5381 (guard P5): true when the return document already has a non-voided invoice.
   *
   * <p>Deliberately shares its predicate with {@link ReturnShipmentUtils#fetchReturnInvoices} — the same join, the
   * same {@code DocStatus != 'VO'} filter — so this guard and the {@code hasReturnInvoice} flag
   * the UI hides its button with can never disagree.
   */
  @SuppressWarnings("java:S2077")
  static boolean hasNonVoidedReturnInvoice(String inOutId) {
    String sql =
        "SELECT 1 " +
        "FROM M_InOutLine l " +
        "JOIN C_InvoiceLine il ON il.M_InOutLine_ID = l.M_InOutLine_ID " +
        "JOIN C_Invoice i ON i.C_Invoice_ID = il.C_Invoice_ID " +
        "WHERE l.M_InOut_ID = ? " +
        "  AND i.DocStatus != 'VO' " +
        "LIMIT 1";
    // getConnection() inside the try on purpose: acquiring the connection is exactly the step
    // that fails when the DB is down, and leaving it outside would let that escape as a raw
    // RuntimeException instead of the OBException this method's contract promises.
    try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, inOutId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    } catch (Exception e) {
      // Propagate: silently answering "no invoice" would let a duplicate through, which is the
      // exact failure this guard exists to prevent.
      log.error("Error checking existing return invoices for {}: {}", inOutId, e.getMessage(), e);
      throw new OBException("Could not verify existing invoices for this return document", e);
    }
  }

  /**
   * Lists the invoices auto-detected from the return document's own chain: walks
   * {@code M_InOutLine.Canceled_Inoutline_ID} back to the original shipment/receipt line and from
   * there to the invoices that billed it — the same navigation
   * {@code SalesInvoiceHeaderHandler.enrichSourceInvoice} uses, only starting from the return
   * document instead of the invoice. There is no header-level link between a return and its
   * original document, so this has to go line by line.
   *
   * <p>These are only a <b>suggestion</b>. The chain exists just for returns created from a
   * shipment that was itself invoiced; a hand-made return has none, and a return covering two
   * shipments billed on two invoices has more than one. Never use this as the selectable list —
   * see {@link #fetchSelectableInvoices}.
   *
   * <p>Only {@code CO} invoices qualify: a draft cannot be rectified, and a voided one has
   * nothing left to rectify.
   */
  @SuppressWarnings("java:S2077")
  static List<JSONObject> fetchAutoDetectedInvoices(String inOutId) {
    String sql =
        "SELECT DISTINCT i.C_Invoice_ID, i.DocumentNo, i.DateInvoiced, i.GrandTotal, " +
        "  cur.ISO_Code, bp.Name " +
        "FROM M_InOutLine rl " +
        "JOIN M_InOutLine ol ON ol.M_InOutLine_ID = rl.Canceled_Inoutline_ID " +
        "JOIN C_InvoiceLine il ON il.M_InOutLine_ID = ol.M_InOutLine_ID " +
        "JOIN C_Invoice i ON i.C_Invoice_ID = il.C_Invoice_ID " +
        "LEFT JOIN C_Currency cur ON cur.C_Currency_ID = i.C_Currency_ID " +
        "LEFT JOIN C_BPartner bp ON bp.C_BPartner_ID = i.C_BPartner_ID " +
        "WHERE rl.M_InOut_ID = ? " +
        "  AND rl.Canceled_Inoutline_ID IS NOT NULL " +
        "  AND i.DocStatus = 'CO' " +
        "ORDER BY i.DateInvoiced DESC";
    return runInvoiceQuery(sql, Collections.singletonList(inOutId), "auto-detected",
        "Could not load the invoices detected for this return");
  }

  /**
   * Lists one batch of the invoices the user may pick to rectify: the confirmed invoices of the
   * same flow (sales or purchase) AND the same business partner as the return document, ordered by
   * document number then invoice date, newest first, windowed by {@code startRow}/{@code pageSize}
   * and optionally narrowed by {@code search}.
   *
   * <p>Deliberately NOT restricted to the return document's own chain. A return can be created
   * standalone — no order, no source invoice — and then the chain yields nothing even though
   * rectifying is perfectly legitimate; and a return covering two shipments billed on two separate
   * invoices has to be able to name both. The chain still drives the suggestion, but it must not
   * limit the choice.
   *
   * <p><b>Business partner IS filtered</b> — only invoices of the return document's own partner.
   * An earlier revision of this method deliberately did NOT filter, on the stated grounds that
   * "{@code C_Invoice_Reverse} enforces same-BP only where it applies (Verifactu orgs permit
   * cross-BP rectifications)" and that filtering "would hide rows the database would have
   * accepted". <b>That rationale was wrong.</b> Read the trigger: its own title is "Check the
   * introduced BP is the same as the Invoice", and the check
   * ({@code IF v_bpheader_id <> v_bpreversed_id THEN RAISE_APPLICATION_ERROR('@NotEqualBPartner@')})
   * is unconditional — Openbravo core, no Verifactu branch, no module gate. So the unfiltered list
   * did the opposite of what the comment claimed: it offered rows the database ALWAYS rejects, and
   * the user only found out on save. Filtering removes impossible choices, it does not remove
   * legitimate ones.
   *
   * <p>The same reasoning applies to {@link #fetchAutoDetectedInvoices}, which is NOT filtered here
   * because its chain (return line → cancelled shipment line → invoice line) can only reach another
   * partner's invoice through anomalous data. If that ever happens the suggestion would preselect an
   * invoice whose link insert is guaranteed to fail — worth revisiting if it is ever observed.
   *
   * <p>Organization IS filtered, unlike business partner. This is raw JDBC, so none of DAL's
   * implicit org scoping applies; without the clause the picker would offer invoices belonging to
   * organizations the current role cannot even read, and the rejection would arrive late (on the
   * link insert) or not at all.
   */
  @SuppressWarnings("java:S2077")
  static List<JSONObject> fetchSelectableInvoices(String inOutId, int startRow, int pageSize,
      String search) {
    // No context means we are outside a request and have nothing to scope by, so the clause is
    // dropped rather than guessed. A context WITH no readable organizations is a different case
    // and stays fail-closed: an empty IN () matches nothing, which is the correct answer for a
    // role that may read none.
    OBContext obContext = OBContext.getOBContext();
    String[] readableOrgs = obContext != null ? obContext.getReadableOrganizations() : null;
    String orgFilter = "";
    if (readableOrgs != null) {
      String placeholders = readableOrgs.length == 0
          ? "''"
          : String.join(",", Collections.nCopies(readableOrgs.length, "?"));
      orgFilter = "  AND i.AD_Org_ID IN (" + placeholders + ") ";
    }
    // Search runs in SQL because the client only holds the batches it has fetched: filtering there
    // would search a subset and answer "no matches" for an invoice that exists further down the
    // set. Matched against the two fields the picker actually renders as text — document number and
    // partner name — so what the user types corresponds to what they can see.
    boolean hasSearch = search != null && !search.isBlank();
    String searchParam = hasSearch ? "%" + search.trim().toLowerCase() + "%" : null;
    String searchFilter = hasSearch
        ? "  AND (LOWER(i.DocumentNo) LIKE ? OR LOWER(COALESCE(bp.Name, '')) LIKE ?) "
        : "";
    // A non-positive page size would turn LIMIT into a silent empty result, so it is clamped rather
    // than trusted; the ceiling keeps a hand-crafted request from asking for the whole table.
    int safePageSize = Math.min(Math.max(1, pageSize), 500);
    String sql =
        "SELECT i.C_Invoice_ID, i.DocumentNo, i.DateInvoiced, i.GrandTotal, " +
        "  cur.ISO_Code, bp.Name " +
        "FROM C_Invoice i " +
        "JOIN M_InOut ret ON ret.M_InOut_ID = ? " +
        "LEFT JOIN C_Currency cur ON cur.C_Currency_ID = i.C_Currency_ID " +
        "LEFT JOIN C_BPartner bp ON bp.C_BPartner_ID = i.C_BPartner_ID " +
        "WHERE i.DocStatus = 'CO' " +
        orgFilter +
        "  AND i.IsSOTrx = ret.IsSOTrx " +
        "  AND i.AD_Client_ID = ret.AD_Client_ID " +
        "  AND i.C_BPartner_ID = ret.C_BPartner_ID " +
        "  AND i.IsActive = 'Y' " +
        searchFilter +
        // Document number first, invoice date second, as the window asks. Note DocumentNo is a
        // VARCHAR, so this is a STRING sort: '9999' sorts after '10000099'. Within one partner and
        // one numbering series the widths match and the order reads naturally, which is the case
        // this picker is for; across series of different widths it can look odd. Kept DESC on both
        // so the newest invoice stays at the top, which is what the list has always done.
        //
        // The ORDER BY is what makes paging correct, not just pretty: OFFSET/LIMIT over an
        // unordered set may repeat or skip rows between batches. C_Invoice_ID is appended as a
        // tie-break so two invoices sharing a number and a date can never straddle a batch
        // boundary in a different order each time.
        "ORDER BY i.DocumentNo DESC, i.DateInvoiced DESC, i.C_Invoice_ID DESC " +
        "LIMIT ? OFFSET ?";
    List<Object> params = new ArrayList<>();
    params.add(inOutId);
    if (readableOrgs != null) {
      params.addAll(Arrays.asList(readableOrgs));
    }
    if (hasSearch) {
      params.add(searchParam);
      params.add(searchParam);
    }
    // Integers, NOT String.valueOf(...): see the binding loop in runInvoiceQuery — a stringified
    // LIMIT/OFFSET is rejected by PostgreSQL outright.
    params.add(safePageSize);
    params.add(Math.max(0, startRow));
    return runInvoiceQuery(sql, params, "selectable", "Could not load the invoices available to rectify");
  }

  /**
   * Shared execution + row mapping for the two invoice-candidate queries above.
   *
   * @param queryName short label identifying which query failed — the two share this method, so a
   *     single generic log line would not say which one blew up
   */
  @SuppressWarnings("java:S2077")
  private static List<JSONObject> runInvoiceQuery(String sql, List<Object> params, String queryName,
      String errorMessage) {
    List<JSONObject> result = new ArrayList<>();
    // getConnection() inside the try — see hasNonVoidedReturnInvoice for why.
    try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
      for (int i = 0; i < params.size(); i++) {
        Object p = params.get(i);
        // Bind by TYPE, not everything as a string. PostgreSQL types a `setString` parameter as
        // varchar, and `LIMIT`/`OFFSET` demand bigint: binding "80" there fails the whole statement
        // with "argument of OFFSET must be type bigint, not type character varying", before a
        // single row is read. That is not an edge case — the paging clause is unconditional, so it
        // broke every call until this loop learned to bind an Integer with setInt.
        if (p instanceof Integer) {
          ps.setInt(i + 1, (Integer) p);
        } else {
          ps.setString(i + 1, (String) p);
        }
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject inv = new JSONObject();
          inv.put("id", rs.getString(1));
          inv.put(ReturnShipmentUtils.FIELD_DOCUMENT_NO, rs.getString(2));
          inv.put("invoiceDate", rs.getDate(3) != null
              ? new SimpleDateFormat("yyyy-MM-dd").format(rs.getDate(3)) : null);
          inv.put("grandTotalAmount", rs.getBigDecimal(4));
          inv.put("currency", rs.getString(5));
          inv.put("businessPartner", rs.getString(6));
          result.add(inv);
        }
      }
    } catch (Exception e) {
      log.error("Error running the {} rectifiable-invoice query for {}: {}",
          queryName, params.isEmpty() ? "?" : params.get(0), e.getMessage(), e);
      throw new OBException(errorMessage, e);
    }
    return result;
  }
}
