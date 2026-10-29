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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;

/**
 * The frame every "import lines into a draft document" delegate shares (ETP-5713): the routing
 * guard, admin mode, and the translation of a {@link DocumentLineImport.ImportRefusal} or an
 * unexpected failure into a response. The three concrete delegates are injected into the header
 * handlers that serve the actions and dispatched through {@link NeoHeaderActionRouter}; none of
 * them names an entity, the header that injects it decides where it is served.
 *
 * <p>A failure the delegate did not foresee is rolled back whole and answered with a generic 500:
 * the response never carries the text of a database error.
 */
abstract class AbstractLineImportHandler implements NeoHandler {

  protected final Logger log = LogManager.getLogger(getClass());

  private static final String ERR_RECORD_ID_REQUIRED = "Record ID is required";
  private static final String ERR_INTERNAL = "An internal error occurred while importing lines";
  private static final String DATE_PATTERN = "yyyy-MM-dd";

  /** Seam: how many candidate documents a discovery call scans (newest first). */
  protected abstract int scanLimit();

  /** Seam: how many documents a discovery call returns at most. */
  protected abstract int rowLimit();

  /** The action names this delegate serves. */
  protected abstract Set<String> actions();

  /**
   * Runs one action, in admin mode, against the record of the request. Refusals are thrown as
   * {@link DocumentLineImport.ImportRefusal}.
   */
  protected abstract NeoResponse execute(NeoContext context, String action) throws Exception;

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())
        || !"POST".equals(context.getHttpMethod()) || context.getFieldName() == null
        || !actions().contains(context.getFieldName())) {
      return null;
    }
    if (StringUtils.isBlank(context.getRecordId())) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, ERR_RECORD_ID_REQUIRED);
    }
    try {
      enterAdminMode();
      try {
        return execute(context, context.getFieldName());
      } finally {
        leaveAdminMode();
      }
    } catch (NeoQuotaCreateCheck.Refused e) {
      return e.toResponse();
    } catch (DocumentLineImport.ImportRefusal refusal) {
      log.info("Import refused ({}): {}", refusal.code(), refusal.getMessage());
      return refusal.response();
    } catch (Exception e) {
      log.error("Error in {} on {}: {}", context.getFieldName(), context.getRecordId(),
          e.getMessage(), e);
      rollback();
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ERR_INTERNAL);
    }
  }

  /**
   * ETP-5051: the plan-quota check of the lines this import added to the document, before the
   * answer is built ({@link NeoQuotaCreateCheck}); a refusal rolls the whole import back.
   *
   * @param entityName the DAL entity of the created lines
   * @param created the lines the import created
   */
  protected void requireQuotaAllowed(String entityName,
      List<DocumentLineImport.ImportedLine> created) {
    List<String> ids = new ArrayList<>(created.size());
    for (DocumentLineImport.ImportedLine line : created) {
      ids.add(line.id);
    }
    NeoQuotaCreateCheck.requireAllowed(entityName, ids);
  }

  /** Seam: admin mode for the DAL reads and writes of the action. */
  protected void enterAdminMode() {
    OBContext.setAdminMode(true);
  }

  protected void leaveAdminMode() {
    OBContext.restorePreviousMode();
  }

  /** Seam: undo everything the action wrote. */
  protected void rollback() {
    OBDal.getInstance().rollbackAndClose();
  }

  /** Seam: the client of the session; documents of another client do not exist for the caller. */
  protected String sessionClientId() {
    return OBContext.getOBContext().getCurrentClient().getId();
  }

  /**
   * Admin mode reads across clients, so the documents the action works on are checked against the
   * session's client and a foreign one answers exactly like a missing one (no leak of existence).
   */
  protected void requireOwnClient(String what, String id, Client client) {
    if (client == null || !client.getId().equals(sessionClientId())) {
      throw DocumentLineImport.notFound(what, id);
    }
  }

  /** Builds the discovery row of one candidate, or {@code null} when it has nothing to offer. */
  protected interface RowBuilder<T> {
    /**
     * Builds the discovery row of one document read by the bounded scan.
     *
     * @param candidate a document read by the bounded scan
     * @return its discovery row, or {@code null} when it has nothing to offer
     * @throws JSONException if the row cannot be built
     */
    JSONObject build(T candidate) throws JSONException;
  }

  /**
   * The discovery page of a bounded scan (newest first): the rows of the candidates that have
   * something to offer, at most {@link #rowLimit()}, and {@code hasMore} — {@code true} when the
   * page stopped at its row limit with candidates unread, or when the scan itself reached
   * {@link #scanLimit()} (older candidates may exist that it never looked at). What a discovery
   * lists is accepted by the import; what it does not list may still be importable.
   */
  protected <T> NeoResponse discover(java.util.List<T> candidates, RowBuilder<T> rowOf)
      throws JSONException {
    org.codehaus.jettison.json.JSONArray rows = new org.codehaus.jettison.json.JSONArray();
    boolean stoppedAtRowLimit = false;
    boolean full = false;
    for (int i = 0; i < candidates.size() && !full; i++) {
      JSONObject row = rowOf.build(candidates.get(i));
      if (row != null) {
        rows.put(row);
        full = rows.length() >= rowLimit();
        stoppedAtRowLimit = full && i + 1 < candidates.size();
      }
    }
    boolean scanReachedItsLimit = candidates.size() >= scanLimit();
    return DocumentLineImport.wrapPage(rows, stoppedAtRowLimit || scanReachedItsLimit, rowLimit(),
        scanLimit());
  }

  /** A page that is complete by construction (the lines of one document). */
  protected NeoResponse completePage(org.codehaus.jettison.json.JSONArray rows)
      throws JSONException {
    return DocumentLineImport.wrapPage(rows, false, rowLimit(), scanLimit());
  }

  /** A required body key, or a 400 naming it. */
  protected static String requireParam(JSONObject body, String key) {
    String value = DocumentLineImport.text(body, key);
    if (value == null) {
      throw new DocumentLineImport.ImportRefusal(HttpServletResponse.SC_BAD_REQUEST,
          DocumentLineImport.ERROR_INVALID_LINE, key + " is required", null);
    }
    return value;
  }

  /** {@code yyyy-MM-dd}, or {@code null}. */
  protected static String day(Date date) {
    return date == null ? null : new SimpleDateFormat(DATE_PATTERN).format(date);
  }

  /** A document row of an {@code available*} discovery answer. */
  protected static JSONObject documentRow(String id, String documentNo, String date,
      String partnerId, String partnerName, String currencyId, int pendingLines)
      throws JSONException {
    JSONObject row = new JSONObject();
    row.put("id", id);
    row.put("documentNo", documentNo);
    row.put("date", date == null ? JSONObject.NULL : date);
    row.put("businessPartner", partnerId == null ? JSONObject.NULL : partnerId);
    row.put("businessPartner$_identifier", partnerName == null ? JSONObject.NULL : partnerName);
    row.put("currency", currencyId == null ? JSONObject.NULL : currencyId);
    row.put("pendingLines", pendingLines);
    return row;
  }
}
