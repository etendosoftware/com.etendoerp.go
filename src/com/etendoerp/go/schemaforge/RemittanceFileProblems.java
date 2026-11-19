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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * ETP-6247 — the problems that keep a remittance's SEPA file from being generated, as
 * {@link RemittanceFileReadiness} finds them and {@code fileReadiness} / {@code confirm} answer
 * them: {@code [{scope, code, message, params, ...ids}]}.
 *
 * <p>{@code message} is the text of {@code code} in the request language
 * ({@link NeoMessageText#text}); {@code params} are its positional placeholders, so a client can
 * word the problem itself. A problem of a business partner (no IBAN, no mandate, no address...) is
 * reported once per partner and code even when several lines share the partner: the first line
 * gives {@code lineId}, {@code invoiceId} and {@code documentNo}, and {@code lineIds} lists every
 * line it affects.
 */
final class RemittanceFileProblems {

  static final String SCOPE_ORG = "org";
  static final String SCOPE_ACCOUNT = "account";
  static final String SCOPE_LINE = "line";

  static final String KEY_SCOPE = "scope";
  static final String KEY_CODE = "code";
  static final String KEY_MESSAGE = "message";
  static final String KEY_PARAMS = "params";
  static final String KEY_LINE_ID = "lineId";
  static final String KEY_LINE_IDS = "lineIds";
  static final String KEY_BP_ID = "businessPartnerId";
  static final String KEY_BP_NAME = "businessPartnerName";
  static final String KEY_INVOICE_ID = "invoiceId";
  static final String KEY_DOCUMENT_NO = "documentNo";
  static final String KEY_ORGANIZATION_ID = "organizationId";
  static final String KEY_ACCOUNT_ID = "accountId";

  /** Insertion-ordered: org problems first, then the account's, then the lines' in line order. */
  private final Map<String, JSONObject> problems = new LinkedHashMap<>();

  /**
   * Adds an organization problem.
   *
   * @param organizationId the remittance's organization
   * @param code           the message key
   * @param params         its placeholders
   */
  void org(String organizationId, String code, Object... params) throws JSONException {
    JSONObject problem = problem(SCOPE_ORG, code, params);
    problem.put(KEY_ORGANIZATION_ID, organizationId);
    problems.put(SCOPE_ORG + '|' + code, problem);
  }

  /**
   * Adds a financial account problem.
   *
   * @param accountId the remittance's financial account
   * @param code      the message key
   * @param params    its placeholders
   */
  void account(String accountId, String code, Object... params) throws JSONException {
    JSONObject problem = problem(SCOPE_ACCOUNT, code, params);
    problem.put(KEY_ACCOUNT_ID, accountId);
    problems.put(SCOPE_ACCOUNT + '|' + code, problem);
  }

  /**
   * Adds a financial account problem that carries one more value for the client to act on (ETP-6247:
   * the creditor identifier computed from the organization, so the SPA can offer to use it).
   *
   * @param accountId the remittance's financial account
   * @param code      the message key
   * @param extraKey  the key of the extra value
   * @param extra     the extra value, {@code null} is written as JSON null
   * @param params    the placeholders of the message
   */
  void accountWith(String accountId, String code, String extraKey, String extra,
      Object... params) throws JSONException {
    JSONObject problem = problem(SCOPE_ACCOUNT, code, params);
    problem.put(KEY_ACCOUNT_ID, accountId);
    putOrNull(problem, extraKey, extra);
    problems.put(SCOPE_ACCOUNT + '|' + code, problem);
  }

  /**
   * Adds a problem of the line's business partner, folded into an earlier one of the same partner
   * and code.
   *
   * @param line   the line it was found on
   * @param code   the message key
   * @param params its placeholders
   */
  void line(LineRef line, String code, Object... params) throws JSONException {
    addLine(line, code, StringUtils.defaultIfBlank(line.businessPartnerId, "line:" + line.lineId),
        params);
  }

  /**
   * Adds a problem of the line itself (its amount), never folded with another line's.
   *
   * @param line   the line
   * @param code   the message key
   * @param params its placeholders
   */
  void lineItself(LineRef line, String code, Object... params) throws JSONException {
    addLine(line, code, "line:" + line.lineId, params);
  }

  /**
   * Adds one problem shared by several lines of the same due (their amounts together), named by
   * the first of them, with {@code lineIds} = all of them.
   *
   * @param first   the first line of the due
   * @param dueId   the due ({@code FIN_Payment_Schedule}) they share
   * @param lineIds every line of the due
   * @param code    the message key
   * @param params  its placeholders
   */
  void due(LineRef first, String dueId, List<String> lineIds, String code, Object... params)
      throws JSONException {
    addLine(first, code, "due:" + dueId, params);
    JSONObject problem = problems.get(SCOPE_LINE + '|' + code + "|due:" + dueId);
    JSONArray ids = new JSONArray();
    for (String id : lineIds) {
      ids.put(id);
    }
    problem.put(KEY_LINE_IDS, ids);
  }

  private void addLine(LineRef line, String code, String fold, Object... params)
      throws JSONException {
    String key = SCOPE_LINE + '|' + code + '|' + fold;
    JSONObject existing = problems.get(key);
    if (existing != null) {
      existing.getJSONArray(KEY_LINE_IDS).put(line.lineId);
      return;
    }
    JSONObject problem = problem(SCOPE_LINE, code, params);
    problem.put(KEY_LINE_ID, line.lineId);
    problem.put(KEY_LINE_IDS, new JSONArray().put(line.lineId));
    putOrNull(problem, KEY_BP_ID, line.businessPartnerId);
    putOrNull(problem, KEY_BP_NAME, line.businessPartnerName);
    putOrNull(problem, KEY_INVOICE_ID, line.invoiceId);
    putOrNull(problem, KEY_DOCUMENT_NO, line.documentNo);
    problems.put(key, problem);
  }

  boolean isEmpty() {
    return problems.isEmpty();
  }

  int size() {
    return problems.size();
  }

  JSONArray toJson() {
    JSONArray array = new JSONArray();
    for (JSONObject problem : problems.values()) {
      array.put(problem);
    }
    return array;
  }

  private static JSONObject problem(String scope, String code, Object... params)
      throws JSONException {
    JSONArray paramArray = new JSONArray();
    Object[] shown = new Object[params.length];
    for (int i = 0; i < params.length; i++) {
      shown[i] = params[i] != null ? params[i].toString() : "-";
      paramArray.put(shown[i]);
    }
    JSONObject problem = new JSONObject();
    problem.put(KEY_SCOPE, scope);
    problem.put(KEY_CODE, code);
    problem.put(KEY_MESSAGE, NeoMessageText.text(code, shown));
    problem.put(KEY_PARAMS, paramArray);
    return problem;
  }

  private static void putOrNull(JSONObject target, String key, String value)
      throws JSONException {
    target.put(key, value != null ? value : JSONObject.NULL);
  }

  /** What a line problem names: the line, its business partner and its invoice. */
  static final class LineRef {
    final String lineId;
    final String businessPartnerId;
    final String businessPartnerName;
    final String invoiceId;
    final String documentNo;

    LineRef(String lineId, String businessPartnerId, String businessPartnerName, String invoiceId,
        String documentNo) {
      this.lineId = lineId;
      this.businessPartnerId = businessPartnerId;
      this.businessPartnerName = businessPartnerName;
      this.invoiceId = invoiceId;
      this.documentNo = documentNo;
    }
  }
}
