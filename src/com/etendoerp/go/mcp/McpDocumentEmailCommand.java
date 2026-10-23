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
package com.etendoerp.go.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.email.EmailSendDefaults;

/**
 * Builds the command {@code etendo_send_document_email} posts to the email contract: the same body
 * the send modal posts ({@code buildEmailContractCommand} in {@code documentEmailSend.js}), so the
 * server cannot tell the two callers apart (ETP-5715).
 *
 * <p>Same rules as the modal. {@code messageEdits} always travels, carrying what the caller wrote or,
 * for what it omitted, the server's own defaults ({@link EmailSendDefaults}). {@code recipientEdits}
 * is the difference between the caller's {@code to} and the document's contact: present only when
 * they differ, absent for an untouched send, which then carries the client idempotency key (the
 * server ignores it when it derives its own). Nothing the contract forbids a caller to set (a
 * provider address, a template, a Reply-To) is ever in the body.</p>
 */
final class McpDocumentEmailCommand {

  private static final String INTENT = "send-document";
  private static final String KEY_ADD = "add";
  private static final String KEY_REMOVE = "remove";

  private McpDocumentEmailCommand() {
  }

  /**
   * Builds the command body.
   *
   * @param contractName the document-send contract, which names the client idempotency key
   * @param request the validated arguments
   * @param defaults the server's prefill for the document
   * @param language the language of the email copy
   * @return the body for {@code TransactionalEmailService#send}
   * @throws JSONException when the body cannot be built
   */
  static JSONObject build(String contractName, McpDocumentEmailRequest request,
      EmailSendDefaults defaults, String language) throws JSONException {
    JSONObject body = new JSONObject();
    body.put(EmailContractCommandSupport.FIELD_VERSION, EmailContractCommandSupport.VERSION);
    body.put(EmailContractCommandSupport.FIELD_RECORD_ID, request.getId());
    body.put("intent", INTENT);
    if (StringUtils.isNotBlank(language)) {
      body.put(EmailContractCommandSupport.FIELD_LANGUAGE, language);
    }
    body.put(EmailContractCommandSupport.FIELD_MESSAGE_EDITS, new JSONObject()
        .put(McpDocumentEmailRequest.PARAM_SUBJECT,
            StringUtils.defaultString(request.getSubject(), defaults.getSubject()))
        .put(McpDocumentEmailRequest.PARAM_MESSAGE,
            StringUtils.defaultString(request.getMessage(), defaults.getMessage())));
    JSONObject recipientEdits = recipientEdits(defaults.getBaseRecipient(), request.getTo(),
        request.getCc());
    if (recipientEdits == null) {
      body.put(EmailContractCommandSupport.FIELD_IDEMPOTENCY_KEY,
          contractName + ":" + request.getId() + ":send:" + EmailContractCommandSupport.VERSION);
    } else {
      body.put(EmailContractCommandSupport.FIELD_RECIPIENT_EDITS, recipientEdits);
    }
    return body;
  }

  /** Port of {@code buildRecipientEdits}: what the final lists add to, and remove from, the contact. */
  private static JSONObject recipientEdits(String base, List<String> to, List<String> cc)
      throws JSONException {
    List<String> toAdd = new ArrayList<>();
    for (String address : to) {
      if (base == null || !base.equalsIgnoreCase(address)) {
        toAdd.add(address);
      }
    }
    boolean removeBase = base != null && !containsIgnoreCase(to, base);
    JSONObject edits = new JSONObject();
    if (!toAdd.isEmpty() || removeBase) {
      JSONObject toChannel = new JSONObject();
      if (!toAdd.isEmpty()) {
        toChannel.put(KEY_ADD, new JSONArray(toAdd));
      }
      if (removeBase) {
        toChannel.put(KEY_REMOVE, new JSONArray(List.of(base)));
      }
      edits.put(McpDocumentEmailRequest.PARAM_TO, toChannel);
    }
    if (!cc.isEmpty()) {
      edits.put(McpDocumentEmailRequest.PARAM_CC, new JSONObject().put(KEY_ADD,
          new JSONArray(cc)));
    }
    return edits.length() == 0 ? null : edits;
  }

  private static boolean containsIgnoreCase(List<String> addresses, String wanted) {
    String key = wanted.toLowerCase(Locale.ROOT);
    for (String address : addresses) {
      if (address.toLowerCase(Locale.ROOT).equals(key)) {
        return true;
      }
    }
    return false;
  }
}
