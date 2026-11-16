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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.openbravo.base.exception.OBException;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionResult;

import com.etendoerp.go.schemaforge.FiscalAeatValidationOutcome.Outcome;
import com.etendoerp.go.schemaforge.FiscalAeatValidationOutcome.Path;

/**
 * Unit tests for {@link FiscalAeatValidationOutcome}: the code-only views of an AEAT answer (what
 * the attempt log may store), the path of a submit request, the PDF of an accepted answer and the
 * API shape.
 *
 * @covers com.etendoerp.go.schemaforge.FiscalAeatValidationOutcome
 */
public class FiscalAeatValidationOutcomeTest {

  private static final String CODE_20 = "A1234567890123456789";
  private static final String CODE_21 = CODE_20 + "0";

  private static AEAT303SubmissionResult result(boolean accepted, boolean testMode) {
    AEAT303SubmissionResult r = new AEAT303SubmissionResult();
    r.setStatus(accepted ? AEAT303SubmissionResult.Status.SUCCESS
        : AEAT303SubmissionResult.Status.ERROR);
    r.setTestMode(testMode);
    return r;
  }

  private static FiscalAeatValidationOutcome rejectedWith(String... errors) {
    AEAT303SubmissionResult r = result(false, true);
    for (String error : errors) {
      r.addError(error);
    }
    return FiscalAeatValidationOutcome.answered("390", Path.DOWNLOAD, r);
  }

  private static FiscalAeatValidationOutcome acceptedWithWarnings(String... warnings) {
    AEAT303SubmissionResult r = result(true, true);
    for (String warning : warnings) {
      r.addWarning(warning);
    }
    return FiscalAeatValidationOutcome.answered("390", Path.DOWNLOAD, r);
  }

  // ── errorCodes ──────────────────────────────────────────────────────

  /** One entry per distinct code, in order; an error whose code cannot be read is skipped. */
  @Test
  public void errorCodesAreDistinctAndSkipEntriesWithoutACode() {
    FiscalAeatValidationOutcome outcome = rejectedWith("E0100803 - Razon social",
        "NIF no identificado", "35068 - Resultado a ingresar", "E0100803 - Otra causa");

    assertEquals(Arrays.asList("E0100803", "35068"), outcome.errorCodes());
  }

  // ── loggedErrorCodes / loggedWarningCodes ───────────────────────────

  /** Only short alphanumeric codes are stored; anything else is counted as UNCODED. */
  @Test
  public void loggedErrorCodesKeepOnlyCodes() {
    FiscalAeatValidationOutcome outcome = rejectedWith("E0100803 - Razon social",
        "NIF 12345678Z no identificado", "B12345678/ACME - texto con datos fiscales",
        CODE_20 + " - at the limit", CODE_21 + " - one char too long");

    assertEquals(Arrays.asList("E0100803", FiscalAeatValidationOutcome.UNCODED,
        FiscalAeatValidationOutcome.UNCODED, CODE_20, FiscalAeatValidationOutcome.UNCODED),
        outcome.loggedErrorCodes());
  }

  /** The same AEAT error twice is one entry; no AEAT text ever reaches the logged view. */
  @Test
  public void loggedErrorCodesCollapseRepeatedErrors() {
    FiscalAeatValidationOutcome outcome = rejectedWith("E0100803 - Razon social",
        "E0100803 - Razon social", "sin codigo", "sin codigo");

    assertEquals(Arrays.asList("E0100803", FiscalAeatValidationOutcome.UNCODED),
        outcome.loggedErrorCodes());
  }

  /**
   * "One entry per distinct error": two different errors stay two entries even when they share a
   * code (or have none), so the count of the attempt log is the count of AEAT errors.
   */
  @Test
  public void loggedErrorCodesCountDistinctErrorsNotDistinctCodes() {
    FiscalAeatValidationOutcome outcome = rejectedWith("E0100803 - Razon social",
        "E0100803 - Otra causa", "sin codigo", "tampoco tiene codigo");

    assertEquals(Arrays.asList("E0100803", "E0100803", FiscalAeatValidationOutcome.UNCODED,
        FiscalAeatValidationOutcome.UNCODED), outcome.loggedErrorCodes());
  }

  @Test
  public void loggedWarningCodesFollowTheSameRules() {
    FiscalAeatValidationOutcome outcome = acceptedWithWarnings("A001 - Aviso informativo",
        "A001 - Aviso informativo", "aviso sin codigo", "A.02/x - codigo no alfanumerico",
        CODE_21 + " - too long");

    assertEquals(Arrays.asList("A001", FiscalAeatValidationOutcome.UNCODED,
        FiscalAeatValidationOutcome.UNCODED, FiscalAeatValidationOutcome.UNCODED),
        outcome.loggedWarningCodes());
    assertTrue(outcome.loggedErrorCodes().isEmpty());
  }

  // ── Path ────────────────────────────────────────────────────────────

  @Test
  public void pathOfASubmissionFollowsTheTestMode() {
    assertEquals(Path.MANUAL_VALIDATION, Path.ofSubmission(true));
    assertEquals(Path.DIRECT_SUBMISSION, Path.ofSubmission(false));
    assertEquals("manual_validation", Path.MANUAL_VALIDATION.key());
    assertEquals("direct_submission", Path.DIRECT_SUBMISSION.key());
    assertEquals("pre_submission_validation", Path.PRE_SUBMISSION_VALIDATION.key());
    assertEquals("download", Path.DOWNLOAD.key());
  }

  // ── pdf ─────────────────────────────────────────────────────────────

  @Test
  public void pdfIsKeptOnlyForAnAcceptedAnswerWithContent() {
    byte[] pdf = { 1, 2, 3 };

    AEAT303SubmissionResult accepted = result(true, true);
    accepted.setPdfContent(pdf);
    FiscalAeatValidationOutcome withPdf =
        FiscalAeatValidationOutcome.answered("390", Path.DOWNLOAD, accepted);
    assertArrayEquals(pdf, withPdf.pdf());
    assertNotSame("a defensive copy on every read", withPdf.pdf(), withPdf.pdf());

    AEAT303SubmissionResult rejected = result(false, true);
    rejected.setPdfContent(pdf);
    assertNull(FiscalAeatValidationOutcome.answered("390", Path.DOWNLOAD, rejected).pdf());

    AEAT303SubmissionResult empty = result(true, true);
    empty.setPdfContent(new byte[0]);
    assertNull(FiscalAeatValidationOutcome.answered("390", Path.DOWNLOAD, empty).pdf());

    assertNull(FiscalAeatValidationOutcome.answered("390", Path.DOWNLOAD, result(true, true))
        .pdf());
    assertNull(FiscalAeatValidationOutcome.unanswered("390", Path.DOWNLOAD, true,
        new IllegalStateException("no answer")).pdf());
  }

  // ── answered / unanswered ───────────────────────────────────────────

  @Test
  public void anAnsweredAttemptIsAcceptedOrRejectedAndKeepsTheResultTestMode() {
    FiscalAeatValidationOutcome accepted = FiscalAeatValidationOutcome.answered("303",
        Path.DIRECT_SUBMISSION, result(true, false));
    assertEquals(Outcome.ACCEPTED, accepted.outcome());
    assertTrue(accepted.answered());
    assertFalse(accepted.testMode());
    assertEquals("303", accepted.model());
    assertEquals(Path.DIRECT_SUBMISSION, accepted.path());

    FiscalAeatValidationOutcome rejected = rejectedWith("E1 - x");
    assertEquals(Outcome.REJECTED, rejected.outcome());
    assertTrue(rejected.answered());
    assertTrue(rejected.testMode());
  }

  /** An IOException anywhere in the cause chain is a connection failure; the rest, not validated. */
  @Test
  public void anUnansweredAttemptIsAConnectionFailureOnlyWithAnIoCause() {
    FiscalAeatValidationOutcome timeout = FiscalAeatValidationOutcome.unanswered("390",
        Path.DOWNLOAD, true, new OBException("wrapped", new SocketTimeoutException("slow")));
    assertEquals(Outcome.CONNECTION_FAILURE, timeout.outcome());
    assertEquals("connection_failure", timeout.outcome().key());
    assertFalse(timeout.answered());
    assertTrue(timeout.testMode());
    assertEquals(Collections.emptyList(), timeout.errors());
    assertEquals(Collections.emptyList(), timeout.loggedErrorCodes());

    FiscalAeatValidationOutcome other = FiscalAeatValidationOutcome.unanswered("390",
        Path.DOWNLOAD, false, new OBException("not the validator's answer"));
    assertEquals(Outcome.NOT_VALIDATED, other.outcome());
    assertEquals("not_validated", other.outcome().key());
    assertFalse(other.answered());
    assertFalse(other.testMode());
  }

  // ── toJson ──────────────────────────────────────────────────────────

  @Test
  public void toJsonCarriesPathOutcomeCodesAndTheParsedEntries() throws Exception {
    AEAT303SubmissionResult r = result(false, true);
    r.addError("E0100803 - Razon social");
    r.addError("E0100803 - Razon social");
    r.addError("NIF no identificado");
    r.addWarning("A001 - Aviso informativo");

    JSONObject json = FiscalAeatValidationOutcome.answered("390", Path.DOWNLOAD, r).toJson();

    assertEquals("download", json.getString("path"));
    assertTrue(json.getBoolean("testMode"));
    assertEquals("rejected", json.getString("outcome"));
    assertEquals(1, json.getJSONArray("errorCodes").length());
    assertEquals("E0100803", json.getJSONArray("errorCodes").getString(0));
    assertEquals(2, json.getJSONArray("errors").length());
    assertEquals("E0100803", json.getJSONArray("errors").getJSONObject(0).getString("code"));
    assertEquals("Razon social",
        json.getJSONArray("errors").getJSONObject(0).getString("message"));
    assertEquals("", json.getJSONArray("errors").getJSONObject(1).getString("code"));
    assertEquals("NIF no identificado",
        json.getJSONArray("errors").getJSONObject(1).getString("message"));
    assertEquals(1, json.getJSONArray("warnings").length());
    assertEquals("A001", json.getJSONArray("warnings").getJSONObject(0).getString("code"));
    assertEquals("Aviso informativo",
        json.getJSONArray("warnings").getJSONObject(0).getString("message"));
    assertEquals(6, json.length());
  }

  @Test
  public void toJsonOfAnUnansweredAttemptHasEmptyLists() throws Exception {
    JSONObject json = FiscalAeatValidationOutcome.unanswered("390", Path.DOWNLOAD, true,
        new IllegalStateException("x")).toJson();

    assertEquals("not_validated", json.getString("outcome"));
    assertEquals(0, json.getJSONArray("errorCodes").length());
    assertEquals(0, json.getJSONArray("errors").length());
    assertEquals(0, json.getJSONArray("warnings").length());
  }

  /** The lists handed out cannot be used to alter the outcome. */
  @Test(expected = UnsupportedOperationException.class)
  public void errorsAreUnmodifiable() {
    List<String> errors = rejectedWith("E1 - x").errors();
    errors.add("E2 - y");
  }
}
