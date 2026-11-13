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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionResult;

/**
 * The outcome of ONE attempt to have the AEAT check a declaration file (ETP-5716) — the single
 * value every consumer of an attempt reads: the HTTP answer, the "Incidencias" persistence and,
 * the attempt log ({@link FiscalAeatAttemptLog}).
 *
 * <p>It says which {@link Path} the attempt came from, whether it ran against the validation
 * service ({@code testMode}), its {@link Outcome}, and the AEAT answer. {@link #errorCodes()} is
 * the code-only view (no AEAT free text) meant for anything that stores or reports attempts.
 *
 * <p>Model-agnostic: nothing here names a model beyond the {@code model} label it is given.
 */
final class FiscalAeatValidationOutcome {

  /** Where the attempt came from. */
  enum Path {
    /** "Generar fichero": the file is validated before it is downloaded. */
    DOWNLOAD,
    /** The direct presentation ("Presentación directa") — a filing. */
    DIRECT_SUBMISSION,
    /** "Validar sin presentar": the test mode of the direct presentation; nothing is filed. */
    MANUAL_VALIDATION;

    /** The path of a submit request: a validation in test mode, a presentation otherwise. */
    static Path ofSubmission(boolean testMode) {
      return testMode ? MANUAL_VALIDATION : DIRECT_SUBMISSION;
    }

    String key() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** What the attempt ended in. */
  enum Outcome {
    /** The AEAT answered and found no error. */
    ACCEPTED,
    /** The AEAT answered with errors. */
    REJECTED,
    /** The AEAT could not be reached, or did not answer in time. */
    CONNECTION_FAILURE,
    /** The file was not validated for any other reason (no usable answer). */
    NOT_VALIDATED;

    String key() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * What {@link #loggedErrorCodes()} holds for an AEAT error whose code cannot be read — the
   * error stays countable, and its text (which can carry fiscal data) is never stored.
   */
  static final String UNCODED = "UNCODED";
  /** An AEAT error code: short and alphanumeric. Anything else is not stored as a code. */
  private static final Pattern LOGGABLE_CODE = Pattern.compile("^[A-Za-z0-9]{1,20}$");

  private final String model;
  private final Path path;
  private final boolean testMode;
  private final Outcome outcome;
  private final List<String> errors;
  private final List<String> warnings;
  private final byte[] pdf;

  private FiscalAeatValidationOutcome(String model, Path path, boolean testMode, Outcome outcome,
      List<String> errors, List<String> warnings, byte[] pdf) {
    this.pdf = pdf != null && pdf.length > 0 ? pdf.clone() : null;
    this.model = model;
    this.path = path;
    this.testMode = testMode;
    this.outcome = outcome;
    this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
    this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
  }

  /** The outcome of an attempt the AEAT answered: accepted, or rejected with its errors. */
  static FiscalAeatValidationOutcome answered(String model, Path path,
      AEAT303SubmissionResult result) {
    return new FiscalAeatValidationOutcome(model, path, result.isTestMode(),
        result.isSuccessful() ? Outcome.ACCEPTED : Outcome.REJECTED, result.getErrors(),
        result.getWarnings(), result.isSuccessful() ? result.getPdfContent() : null);
  }

  /**
   * The outcome of an attempt that got no usable answer: {@link Outcome#CONNECTION_FAILURE} when
   * an {@link IOException} (timeout, refused connection, unknown host) is in the cause chain,
   * {@link Outcome#NOT_VALIDATED} otherwise.
   */
  static FiscalAeatValidationOutcome unanswered(String model, Path path, boolean testMode,
      Throwable failure) {
    return new FiscalAeatValidationOutcome(model, path, testMode,
        hasIoCause(failure) ? Outcome.CONNECTION_FAILURE : Outcome.NOT_VALIDATED, List.of(),
        List.of(), null);
  }

  private static boolean hasIoCause(Throwable failure) {
    Set<Throwable> seen = new LinkedHashSet<>();
    for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
      if (t instanceof IOException) {
        return true;
      }
    }
    return false;
  }

  String model() {
    return model;
  }

  Path path() {
    return path;
  }

  boolean testMode() {
    return testMode;
  }

  Outcome outcome() {
    return outcome;
  }

  /** True when the AEAT answered (accepted or rejected) — only then is there anything to store. */
  boolean answered() {
    return outcome == Outcome.ACCEPTED || outcome == Outcome.REJECTED;
  }

  /** The PDF of an accepted answer (the validator's draft, a justificante), or null. */
  byte[] pdf() {
    return pdf != null ? pdf.clone() : null;
  }

  /** The AEAT errors as they came ({@code "CODE - message"}). */
  List<String> errors() {
    return errors;
  }

  /** The AEAT warnings as they came ({@code "CODE - message"}). */
  List<String> warnings() {
    return warnings;
  }

  /** The distinct AEAT error codes, in order, without their text; an entry without a code is skipped. */
  List<String> errorCodes() {
    Set<String> codes = new LinkedHashSet<>();
    for (String raw : errors) {
      String code = FiscalDeclCrudHandler.splitAeatError(raw)[0];
      if (StringUtils.isNotBlank(code)) {
        codes.add(code);
      }
    }
    return new ArrayList<>(codes);
  }

  /**
   * The AEAT errors for the attempt log: one entry per distinct error, its code when it has a
   * readable one, {@link #UNCODED} otherwise. Never any AEAT text.
   */
  List<String> loggedErrorCodes() {
    List<String> codes = new ArrayList<>();
    for (String raw : new LinkedHashSet<>(errors)) {
      String code = FiscalDeclCrudHandler.splitAeatError(raw)[0];
      codes.add(LOGGABLE_CODE.matcher(code).matches() ? code : UNCODED);
    }
    return codes;
  }

  /**
   * The API shape: {@code { path, testMode, outcome, errorCodes, errors: [{code, message}],
   * warnings: [{code, message}] }}.
   */
  JSONObject toJson() throws JSONException {
    JSONObject o = new JSONObject();
    o.put("path", path.key());
    o.put(FiscalAeatSubmissionSupport.PARAM_TEST_MODE, testMode);
    o.put("outcome", outcome.key());
    o.put("errorCodes", new JSONArray(errorCodes()));
    o.put("errors", entriesJson(errors));
    o.put("warnings", entriesJson(warnings));
    return o;
  }

  private static JSONArray entriesJson(List<String> rawEntries) throws JSONException {
    JSONArray out = new JSONArray();
    for (String raw : new LinkedHashSet<>(rawEntries)) {
      String[] parsed = FiscalDeclCrudHandler.splitAeatError(raw);
      JSONObject entry = new JSONObject();
      entry.put("code", parsed[0]);
      entry.put("message", parsed[1]);
      out.put(entry);
    }
    return out;
  }
}
