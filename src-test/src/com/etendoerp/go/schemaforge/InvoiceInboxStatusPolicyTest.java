/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.codehaus.jettison.json.JSONException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.data.IncomingDocument;

/**
 * Which rows the validation verdict is allowed to move, and where: only
 * {@code PENDING_REVIEW <-> ERROR(MISSING_FIELDS | REFERENCE_TOO_LONG)}. Every other state is
 * decided elsewhere (the worker, the confirm, the discard) and must come out untouched.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxStatusPolicy
 */
class InvoiceInboxStatusPolicyTest {

  private static final String PENDING_REVIEW = "PENDING_REVIEW";
  private static final String ERROR = "ERROR";
  private static final String MISSING_FIELDS = "MISSING_FIELDS";
  private static final String TOO_LONG = "REFERENCE_TOO_LONG";

  private static IncomingDocument row(String status, String errorCode) {
    IncomingDocument row = mock(IncomingDocument.class);
    when(row.getStatus()).thenReturn(status);
    when(row.getErrorCode()).thenReturn(errorCode);
    return row;
  }

  private static InvoiceInboxValidator.Result valid() {
    return new InvoiceInboxValidator.Result(List.of(), null);
  }

  private static InvoiceInboxValidator.Result failed(String code) throws JSONException {
    return new InvoiceInboxValidator.Result(List.of("vendor"), code);
  }

  @Test
  @DisplayName("a valid verdict on a row waiting for review changes nothing")
  void validVerdictKeepsPendingReview() {
    IncomingDocument row = row(PENDING_REVIEW, null);

    assertFalse(InvoiceInboxStatusPolicy.apply(row, valid()));

    verify(row).setStatus(PENDING_REVIEW);
    verify(row).setErrorCode(null);
  }

  @Test
  @DisplayName("missing data moves a row waiting for review to ERROR / MISSING_FIELDS")
  void missingDataMovesToError() throws Exception {
    IncomingDocument row = row(PENDING_REVIEW, null);

    assertTrue(InvoiceInboxStatusPolicy.apply(row, failed(MISSING_FIELDS)));

    verify(row).setStatus(ERROR);
    verify(row).setErrorCode(MISSING_FIELDS);
  }

  @Test
  @DisplayName("a number that does not fit moves it to ERROR / REFERENCE_TOO_LONG")
  void tooLongMovesToError() throws Exception {
    IncomingDocument row = row(PENDING_REVIEW, null);

    assertTrue(InvoiceInboxStatusPolicy.apply(row, failed(TOO_LONG)));

    verify(row).setStatus(ERROR);
    verify(row).setErrorCode(TOO_LONG);
  }

  @Test
  @DisplayName("an edit that completes the data brings an ERROR / MISSING_FIELDS row back to review")
  void completingTheDataBringsItBack() {
    for (String code : new String[] { MISSING_FIELDS, TOO_LONG }) {
      IncomingDocument row = row(ERROR, code);

      assertTrue(InvoiceInboxStatusPolicy.apply(row, valid()), code);

      verify(row).setStatus(PENDING_REVIEW);
      verify(row).setErrorCode(null);
    }
  }

  @Test
  @DisplayName("a validation error can change into the other one; the same error is not a change")
  void validationErrorsMoveBetweenThemselves() throws Exception {
    IncomingDocument row = row(ERROR, MISSING_FIELDS);
    assertTrue(InvoiceInboxStatusPolicy.apply(row, failed(TOO_LONG)));
    verify(row).setErrorCode(TOO_LONG);

    assertFalse(InvoiceInboxStatusPolicy.apply(row(ERROR, MISSING_FIELDS), failed(MISSING_FIELDS)));
  }

  @Test
  @DisplayName("an ERROR row with no code yet is still a validation row")
  void anErrorWithoutCodeIsMovable() {
    IncomingDocument row = row(ERROR, null);

    assertTrue(InvoiceInboxStatusPolicy.apply(row, valid()));

    verify(row).setStatus(PENDING_REVIEW);
  }

  @Test
  @DisplayName("PROCESSING, READY, CONFIRMED and DISCARDED rows are never touched, whatever the verdict")
  void otherStatesAreUntouched() throws Exception {
    for (String status : new String[] { "PROCESSING", "READY", "CONFIRMED", "DISCARDED", null }) {
      for (InvoiceInboxValidator.Result verdict : List.of(valid(), failed(MISSING_FIELDS),
          failed(TOO_LONG))) {
        IncomingDocument row = row(status, null);

        assertFalse(InvoiceInboxStatusPolicy.apply(row, verdict), String.valueOf(status));

        verify(row, never()).setStatus(any());
        verify(row, never()).setErrorCode(any());
      }
    }
  }

  @Test
  @DisplayName("an ERROR decided elsewhere (UNREADABLE, SERVICE_FAILURE, CONFIRM_FAILED) is never touched")
  void errorsDecidedElsewhereAreUntouched() throws Exception {
    for (String code : new String[] { "UNREADABLE", "SERVICE_FAILURE", "CONFIRM_FAILED" }) {
      for (InvoiceInboxValidator.Result verdict : List.of(valid(), failed(MISSING_FIELDS))) {
        IncomingDocument row = row(ERROR, code);

        assertFalse(InvoiceInboxStatusPolicy.apply(row, verdict), code);

        verify(row, never()).setStatus(any());
        verify(row, never()).setErrorCode(any());
      }
    }
  }

  @Test
  @DisplayName("the policy never touches the adjusted or reviewed flags")
  void flagsAreLeftAlone() throws Exception {
    IncomingDocument row = row(PENDING_REVIEW, null);

    InvoiceInboxStatusPolicy.apply(row, failed(MISSING_FIELDS));

    verify(row, never()).setAdjusted(any());
    verify(row, never()).setReviewed(any());
  }
}
