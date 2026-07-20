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

package com.etendoerp.go.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;

import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SupportMessage;

/**
 * Attachment-related helpers for the support chat: locating a message by its Jira attachment
 * id, extracting/streaming a single attachment's metadata and content, and mapping the
 * outgoing/persisted JSON shapes used by {@link SupportConversationsServlet}. Extracted from
 * that class (SonarQube S1448 — too many methods) as a cohesive "attachment helpers" group.
 */
final class SupportAttachmentHelpers {

  private static final Logger log = LogManager.getLogger(SupportAttachmentHelpers.class);

  private static final String FIELD_ID = "id";

  private SupportAttachmentHelpers() {
    // Static helpers only.
  }

  /** Scans messages whose {@code attachments} column is non-null for a JSON-encoded object with
   * this id, confirming with an actual JSON parse (the {@code LIKE} below is only a cheap
   * pre-filter, not the authorization decision — a substring collision here would just make this
   * candidate get rejected by {@link #findAttachmentMeta} returning null). */
  static SupportMessage findMessageByAttachmentId(String jiraAttachmentId) {
    OBCriteria<SupportMessage> crit = OBDal.getInstance().createCriteria(SupportMessage.class);
    crit.setFilterOnReadableClients(false);
    crit.setFilterOnReadableOrganization(false);
    crit.add(Restrictions.isNotNull(SupportMessage.PROPERTY_ATTACHMENTS));
    crit.add(Restrictions.like(SupportMessage.PROPERTY_ATTACHMENTS, "%\"" + jiraAttachmentId + "\"%"));
    for (SupportMessage msg : crit.list()) {
      if (findAttachmentMeta(msg, jiraAttachmentId) != null) return msg;
    }
    return null;
  }

  static JSONObject findAttachmentMeta(SupportMessage msg, String jiraAttachmentId) {
    try {
      JSONArray arr = new JSONArray(msg.getAttachments());
      for (int i = 0; i < arr.length(); i++) {
        JSONObject att = arr.optJSONObject(i);
        if (att != null && jiraAttachmentId.equals(att.optString(FIELD_ID, ""))) return att;
      }
    } catch (JSONException e) {
      // Malformed attachments JSON on this row — treat as no match rather than fail the request.
    }
    return null;
  }

  static void streamJiraAttachment(HttpServletResponse response, String jiraAttachmentId,
      String filename, String mimeType) throws IOException {
    HttpResponse<InputStream> jiraResp = SupportJiraWebhookHandler.fetchAttachmentContent(jiraAttachmentId);
    if (jiraResp == null) {
      SupportConversationsServlet.writeError(response, HttpServletResponse.SC_NOT_FOUND, "Attachment not available");
      return;
    }
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(mimeType);
    response.setHeader("Content-Disposition", "inline; filename=\"" + sanitizeFilenameHeader(filename) + "\"");
    try (InputStream in = jiraResp.body(); OutputStream out = response.getOutputStream()) {
      in.transferTo(out);
    }
  }

  /** Strips characters that would break the {@code Content-Disposition} header value (quotes,
   * control chars) — the filename only ever drives a UI download hint, never a filesystem path. */
  static String sanitizeFilenameHeader(String filename) {
    return filename.replaceAll("[\"\\r\\n]", "_");
  }

  /** Maps the request's outgoing {@code attachments} array ({@code name}/{@code mimeType}/{@code
   * data}, per {@link SupportIntegrationClient#appendSingleAttachmentPart}) to the persisted
   * {@code [{filename, mimeType}]} shape. Returns {@code null} — never an empty-array string —
   * when there is nothing to store, matching {@link SupportJiraWebhookHandler#insertJiraMessage}. */
  static String buildOutgoingAttachmentsJson(JSONArray attachments) {
    if (attachments == null || attachments.length() == 0) return null;
    JSONArray stored = new JSONArray();
    for (int i = 0; i < attachments.length(); i++) {
      JSONObject att = attachments.optJSONObject(i);
      if (att == null) continue;
      try {
        stored.put(new JSONObject()
            .put("filename", att.optString("name", ""))
            .put(SupportConversationsServlet.FIELD_MIME_TYPE,
                att.optString(SupportConversationsServlet.FIELD_MIME_TYPE,
                    SupportConversationsServlet.DEFAULT_MIME_TYPE)));
      } catch (JSONException e) {
        // Malformed attachment entry — skip it rather than fail the whole message save.
        log.warn("Skipping malformed outgoing attachment entry: {}", e.getMessage());
      }
    }
    return stored.length() > 0 ? stored.toString() : null;
  }

  /** Parses the {@code attachments} column (a JSON array string, or null/blank for the common
   * case of a message with none) into the {@code [{id, filename, mimeType}]} array the frontend
   * contract expects. Never fails the response — malformed JSON degrades to an empty array. */
  static JSONArray parseAttachments(String raw) {
    if (raw == null || raw.isEmpty()) return new JSONArray();
    try {
      return new JSONArray(raw);
    } catch (JSONException e) {
      log.warn("Malformed attachments JSON, returning empty array: {}", e.getMessage());
      return new JSONArray();
    }
  }
}
