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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.supportaccess;

/**
 * ETP-5351 — a support pass that cannot be redeemed. {@link Reason#getErrorCode()} is the value
 * the handoff endpoint answers in {@code {"error": "..."}} with HTTP 401.
 */
public class SupportTicketException extends SupportAccessException {

  private static final long serialVersionUID = 1L;

  /** Why the pass was refused. */
  public enum Reason {
    /** Unknown, malformed or tampered pass. */
    INVALID("support_ticket_invalid"),
    /** The pass existed but its 60 seconds are over, or its access was closed. */
    EXPIRED("support_ticket_expired"),
    /** The pass was already redeemed. */
    USED("support_ticket_used");

    private final String errorCode;

    Reason(String errorCode) {
      this.errorCode = errorCode;
    }

    /**
     * The error code of the handoff contract.
     *
     * @return the code
     */
    public String getErrorCode() {
      return errorCode;
    }
  }

  private final Reason reason;

  /**
   * Creates the exception.
   *
   * @param reason why the pass was refused
   */
  public SupportTicketException(Reason reason) {
    super(reason.getErrorCode(), "Support pass refused: " + reason.getErrorCode());
    this.reason = reason;
  }

  /** @return why the pass was refused */
  public Reason getReason() {
    return reason;
  }
}
