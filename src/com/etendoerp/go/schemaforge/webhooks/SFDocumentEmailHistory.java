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

package com.etendoerp.go.schemaforge.webhooks;

import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.User;

import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Webhook that returns one document's readable email send history (ETP-5069), newest first.
 *
 * <pre>
 * GET /sws/neo/documentemailhistory?recordId=&lt;document id&gt;
 * GET /sws/neo/documentemailhistory?recordId=&lt;document id&gt;&amp;specName=sales-invoice
 * </pre>
 *
 * <p>Reached through the NEO pseudo-spec bridge ({@code NeoGoWebhookBridge}, allow-listed in
 * {@code NeoPseudoSpecDispatcher}) rather than the Webhooks module's {@code /webhooks/*} dispatch,
 * so it needs only a valid NEO bearer token and no {@code SMFWHE_DEFINEDWEBHOOK_ROLE} grant row —
 * that table is reset to its XML-only baseline by {@code update.database}, silently wiping any
 * tenant-specific grant. The response envelope is the module's usual
 * {@code {"result": "&lt;JSON string&gt;"}} / {@code {"error": "&lt;message&gt;"}}; note that
 * {@code result} is a STRING the caller parses, not a nested array.</p>
 *
 * <p><b>Access rule: plain DAL client/org filtering, deliberately no admin mode.</b> Unlike
 * {@link SFListMenu} and {@link SFWindowAccessMap} — which capture the caller's role first and
 * then enter {@link org.openbravo.dal.core.OBContext#setAdminMode()} because they must read
 * client-0 (system-owned) menu and window metadata that the caller cannot see on their own — this
 * endpoint reads only {@code ETGO_Email_Send_Log}, a Client/Organization table (AD access level 3)
 * whose rows carry the sending tenant. {@link OBQuery} filters on readable clients and readable
 * organizations by default (see core {@code OBQuery}'s {@code filterOnReadableClients} /
 * {@code filterOnReadableOrganizations}, both initialised to {@code true}), so the query below IS
 * the access rule: a caller can only ever read the history of documents in their own readable
 * clients and organizations. Entering admin mode here — or calling
 * {@code setFilterOnReadableClients(false)} — would replace that guarantee with a hand-rolled
 * check, which is strictly weaker. It is also why there is no role gate: reading the mail you sent
 * on your own documents is not an admin-only capability.</p>
 *
 * <p>The consumer is the document preview panel's Emails card
 * ({@code tools/app-shell/src/windows/custom/shared/preview-cards/EmailsCard.jsx} in
 * {@code etendo_schema_forge}). The row shape it reads is fixed by this class:
 * {@code id}, {@code sentAt} (ISO-8601 instant), {@code status}, {@code recipientsTo},
 * {@code recipientsCc} (both JSON arrays), {@code subject}, {@code messageBody},
 * {@code downloadLink}, {@code contractName}, {@code specName}, {@code errorMessage} and
 * {@code sentBy} — the sender's display name, resolved from the row's {@code CreatedBy}, which is
 * the real sending user precisely because {@code DalEmailSendLogStore} writes without admin
 * mode.</p>
 */
public class SFDocumentEmailHistory extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFDocumentEmailHistory.class);

  static final String ENTITY_EMAIL_SEND_LOG = "ETGO_Email_Send_Log";

  static final String PARAM_RECORD_ID = "recordId";
  static final String PARAM_SPEC_NAME = "specName";

  private static final String RESULT = "result";
  private static final String ERROR = "error";

  /** Defensive ceiling: a document with more sends than this is pathological, not a use case. */
  private static final int MAX_ROWS = 200;

  /** Recipient columns hold a joined string; addresses never contain either separator. */
  private static final String RECIPIENT_SEPARATORS = "[,;]";

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    String recordId = StringUtils.trimToNull(parameter.get(PARAM_RECORD_ID));
    if (recordId == null) {
      responseVars.put(ERROR, "recordId is required");
      return;
    }
    try {
      JSONArray history = new JSONArray();
      for (BaseOBObject entry : findHistory(recordId,
          StringUtils.trimToNull(parameter.get(PARAM_SPEC_NAME)))) {
        history.put(toJson(entry));
      }
      responseVars.put(RESULT, history.toString());
    } catch (Exception e) {
      log.error("Error in SFDocumentEmailHistory for record [{}]", recordId, e);
      responseVars.put(ERROR, e.getMessage());
    }
  }

  /**
   * Reads the send history of one document, newest first.
   *
   * <p>No {@code setFilterOnReadableClients}/{@code setFilterOnReadableOrganization} call on
   * purpose — see the class javadoc: their defaults ARE this endpoint's access rule.</p>
   */
  private List<BaseOBObject> findHistory(String recordId, String specName) {
    StringBuilder where = new StringBuilder("as h where h.recordID = :recordId");
    if (specName != null) {
      where.append(" and h.specName = :specName");
    }
    where.append(" order by h.sentAt desc");

    OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(ENTITY_EMAIL_SEND_LOG,
        where.toString());
    query.setNamedParameter(PARAM_RECORD_ID, recordId);
    if (specName != null) {
      query.setNamedParameter(PARAM_SPEC_NAME, specName);
    }
    query.setMaxResult(MAX_ROWS);
    return query.list();
  }

  private JSONObject toJson(BaseOBObject entry) throws JSONException {
    JSONObject row = new JSONObject();
    row.put("id", entry.getId());
    row.put("sentAt", isoInstant(entry.get("sentAt")));
    row.put("status", text(entry.get("status")));
    row.put("recipientsTo", addresses(entry.get("recipientsTo")));
    row.put("recipientsCc", addresses(entry.get("recipientsCC")));
    row.put("subject", text(entry.get("subject")));
    row.put("messageBody", text(entry.get("messageBody")));
    row.put("downloadLink", text(entry.get("downloadLink")));
    row.put("contractName", text(entry.get("contractName")));
    row.put("specName", text(entry.get("specName")));
    row.put("errorMessage", text(entry.get("errorMessage")));
    row.put("sentBy", senderName(entry));
    return row;
  }

  /**
   * Resolves the sender's display name from {@code CreatedBy}.
   *
   * <p>Best effort: a user record that cannot be read leaves the field null rather than failing
   * the whole history. The card simply omits the "Sent by" line then.</p>
   */
  private Object senderName(BaseOBObject entry) {
    try {
      Object createdBy = entry.get("createdBy");
      if (!(createdBy instanceof User)) {
        return JSONObject.NULL;
      }
      User sender = (User) createdBy;
      String name = StringUtils.firstNonBlank(sender.getName(), sender.getUsername());
      return name == null ? JSONObject.NULL : name;
    } catch (Exception e) {
      log.debug("Could not resolve the sender of an email history row: {}", e.getMessage());
      return JSONObject.NULL;
    }
  }

  private static Object isoInstant(Object value) {
    if (!(value instanceof Date)) {
      return JSONObject.NULL;
    }
    return DateTimeFormatter.ISO_INSTANT.format(((Date) value).toInstant());
  }

  private static Object text(Object value) {
    String normalized = value == null ? null : StringUtils.trimToNull(value.toString());
    return normalized == null ? JSONObject.NULL : normalized;
  }

  /**
   * Splits a stored recipient column back into a JSON array.
   *
   * <p>The column is a joined string because it is also meant to be readable in the backoffice
   * window; the endpoint hands the caller an array so it never has to guess the separator.</p>
   */
  private static JSONArray addresses(Object value) {
    JSONArray addresses = new JSONArray();
    String joined = value == null ? null : StringUtils.trimToNull(value.toString());
    if (joined == null) {
      return addresses;
    }
    for (String address : joined.split(RECIPIENT_SEPARATORS)) {
      String normalized = StringUtils.trimToNull(address);
      if (normalized != null) {
        addresses.put(normalized);
      }
    }
    return addresses;
  }
}
