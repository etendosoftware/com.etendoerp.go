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

import javax.servlet.http.HttpServletResponse;

import org.openbravo.base.exception.OBException;

/**
 * A business rejection of a follow-up document request (ETP-5576): the request was understood
 * but the source cannot produce a document now. Carries a stable machine-readable
 * {@link Reason#getCode() code} so a client can react (and translate) without parsing the
 * English message.
 */
class FollowUpException extends OBException {

  private static final long serialVersionUID = 1L;

  /** Why a follow-up document cannot be created. */
  enum Reason {
    /** No source record with that id. */
    NOT_FOUND("FOLLOW_UP_SOURCE_NOT_FOUND", HttpServletResponse.SC_NOT_FOUND,
        "Source document not found"),
    /** The source belongs to the other transaction direction (e.g. a purchase invoice id on the sales endpoint). */
    WRONG_DIRECTION("FOLLOW_UP_WRONG_DIRECTION", HttpServletResponse.SC_BAD_REQUEST,
        "The source document belongs to the other transaction direction"),
    /** The source is not in a status that allows a follow-up document (e.g. not Completed). */
    NOT_COMPLETED("FOLLOW_UP_SOURCE_NOT_COMPLETED", HttpServletResponse.SC_BAD_REQUEST,
        "The source document must be completed"),
    /** The source document type does not allow it (e.g. a rectificative invoice). */
    NOT_ELIGIBLE_TYPE("FOLLOW_UP_SOURCE_TYPE_NOT_ELIGIBLE", HttpServletResponse.SC_BAD_REQUEST,
        "This document type does not allow creating a follow-up document"),
    /** Every line is already fully carried by existing documents (drafts included). */
    NOTHING_PENDING("FOLLOW_UP_NOTHING_PENDING", HttpServletResponse.SC_BAD_REQUEST,
        "There is nothing pending on this document"),
    /**
     * A draft of the target document already exists and the resolver chose to wait for it rather
     * than count it as already moved (a per-resolver decision, see {@link PendingResolver}).
     */
    DRAFT_IN_PROGRESS("FOLLOW_UP_DRAFT_IN_PROGRESS", HttpServletResponse.SC_BAD_REQUEST,
        "A draft follow-up document already exists"),
    /** A configuration prerequisite is missing (warehouse, document type, storage bin, …). */
    MISSING_SETUP("FOLLOW_UP_MISSING_SETUP", HttpServletResponse.SC_BAD_REQUEST,
        "A required configuration is missing");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;

    Reason(String code, int httpStatus, String defaultMessage) {
      this.code = code;
      this.httpStatus = httpStatus;
      this.defaultMessage = defaultMessage;
    }

    String getCode() {
      return code;
    }

    int getHttpStatus() {
      return httpStatus;
    }

    String getDefaultMessage() {
      return defaultMessage;
    }
  }

  private final Reason reason;

  FollowUpException(Reason reason) {
    this(reason, reason.getDefaultMessage());
  }

  FollowUpException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  Reason getReason() {
    return reason;
  }
}
