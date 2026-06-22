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

package com.etendoerp.go.schemaforge.email;

import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Provider payload resolved by a trusted server-side email contract.
 */
public final class EmailProviderRequest {

  private final EmailRecipientSet recipients;
  private final String recipient;
  private final String template;
  private final JSONObject data;
  private final String replyTo;

  /**
   * Creates a single-recipient provider request resolved by a trusted email contract.
   *
   * @param recipient server-resolved destination address
   * @param template provider template identifier
   * @param data provider template variables
   * @param replyTo optional reply-to address approved by the contract
   */
  public EmailProviderRequest(String recipient, String template, JSONObject data, String replyTo) {
    this(EmailRecipientSet.singleTo(Objects.requireNonNull(StringUtils.trimToNull(recipient),
        "Recipient address is mandatory")), template, data, replyTo);
  }

  /**
   * Creates a multi-channel provider request resolved by a trusted email contract.
   *
   * @param recipients server-resolved recipient set, with at least one to address
   * @param template provider template identifier
   * @param data provider template variables
   * @param replyTo optional reply-to address approved by the contract
   */
  public EmailProviderRequest(EmailRecipientSet recipients, String template, JSONObject data,
      String replyTo) {
    Objects.requireNonNull(recipients, "Recipient set is mandatory");
    if (recipients.isToEmpty()) {
      throw new NullPointerException("Recipient address is mandatory");
    }
    this.recipients = recipients;
    this.recipient = recipients.getTo().get(0);
    this.template = Objects.requireNonNull(StringUtils.trimToNull(template),
        "Template identifier is mandatory");
    this.data = data;
    this.replyTo = StringUtils.trimToNull(replyTo);
  }

  public String getRecipient() {
    return recipient;
  }

  /**
   * Returns the resolved multi-channel recipient set.
   *
   * @return recipient set carried by this request
   */
  public EmailRecipientSet getRecipients() {
    return recipients;
  }

  public String getTemplate() {
    return template;
  }

  public JSONObject getData() {
    return data;
  }

  public String getReplyTo() {
    return replyTo;
  }

  /**
   * Converts this request into the JSON shape expected by the email provider.
   *
   * @return provider payload with destination, template, data, and optional reply-to
   * @throws JSONException when the JSON payload cannot be built
   */
  JSONObject toProviderPayload() throws JSONException {
    JSONObject payload = new JSONObject();
    List<String> to = recipients.getTo();
    List<String> cc = recipients.getCc();
    if (to.size() == 1 && cc.isEmpty()) {
      // Single-recipient compatibility: emit "to" as a plain string.
      payload.put("to", to.get(0));
    } else {
      payload.put("to", new JSONArray(to));
      if (!cc.isEmpty()) {
        payload.put("cc", new JSONArray(cc));
      }
    }
    payload.put("template", template);
    payload.put("data", data == null ? new JSONObject() : new JSONObject(data.toString()));
    if (replyTo != null) {
      payload.put("replyTo", replyTo);
    }
    return payload;
  }
}
