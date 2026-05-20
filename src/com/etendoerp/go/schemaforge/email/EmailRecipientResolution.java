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

import org.apache.commons.lang3.StringUtils;

/**
 * Recipient resolved by a contract before provider payload creation.
 */
public final class EmailRecipientResolution {

  public static final String SOURCE_SERVER = "SERVER";
  public static final String SOURCE_CALLER = "CALLER";

  private final boolean resolved;
  private final String recipient;
  private final String source;
  private final int httpStatus;
  private final String message;

  private EmailRecipientResolution(boolean resolved, String recipient, String source,
      int httpStatus, String message) {
    this.resolved = resolved;
    this.recipient = StringUtils.trimToNull(recipient);
    this.source = source;
    this.httpStatus = httpStatus;
    this.message = message;
  }

  /**
   * Creates a recipient derived from trusted server-side state.
   *
   * @param recipient resolved email address
   * @return server-side recipient resolution
   */
  public static EmailRecipientResolution serverResolved(String recipient) {
    return new EmailRecipientResolution(true, requireRecipient(recipient), SOURCE_SERVER, 200,
        null);
  }

  /**
   * Creates a recipient supplied by the caller for an explicit support/admin contract.
   *
   * @param recipient caller-provided email address
   * @return caller-provided recipient resolution
   */
  public static EmailRecipientResolution callerProvided(String recipient) {
    return new EmailRecipientResolution(true, requireRecipient(recipient), SOURCE_CALLER, 200,
        null);
  }

  /**
   * Creates a failed recipient resolution.
   *
   * @param httpStatus HTTP status for the rejection
   * @param message client-visible rejection message
   * @return rejected recipient resolution
   */
  public static EmailRecipientResolution rejected(int httpStatus, String message) {
    return new EmailRecipientResolution(false, null, null, httpStatus, message);
  }

  private static String requireRecipient(String recipient) {
    String normalized = StringUtils.trimToNull(recipient);
    if (normalized == null) {
      throw new IllegalArgumentException("Recipient email cannot be null or empty");
    }
    return normalized;
  }

  /**
   * Indicates whether a recipient was resolved.
   *
   * @return {@code true} when a recipient is available
   */
  public boolean isResolved() {
    return resolved;
  }

  /**
   * Returns the resolved destination address.
   *
   * @return resolved email address
   */
  public String getRecipient() {
    return recipient;
  }

  /**
   * Returns the source used to resolve the recipient.
   *
   * @return recipient source
   */
  public String getSource() {
    return source;
  }

  /**
   * Indicates whether the recipient was supplied by the caller.
   *
   * @return {@code true} when the recipient source is caller-provided
   */
  public boolean isCallerProvided() {
    return SOURCE_CALLER.equals(source);
  }

  /**
   * Returns the HTTP status for explicit recipient-resolution rejection.
   *
   * @return rejection HTTP status
   */
  public int getHttpStatus() {
    return httpStatus;
  }

  /**
   * Returns the recipient-resolution rejection message.
   *
   * @return client-visible rejection message
   */
  public String getMessage() {
    return message;
  }
}
