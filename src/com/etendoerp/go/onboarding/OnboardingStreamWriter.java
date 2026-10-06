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
package com.etendoerp.go.onboarding;

import java.io.PrintWriter;

/**
 * The onboarding NDJSON stream, remembering the first {@code result} line written to it.
 *
 * <p>A provisioning step that fails gracefully writes its own result line (with the message the
 * user needs, e.g. "company name already in use", and possibly a stable code) and returns
 * {@code false}. The onboarding handler then promotes that {@code false} to its exception path so
 * the transaction is rolled back. Without this record the handler cannot tell whether the stream
 * already carries a result, writes a second, generic one, and the client, which keeps the last
 * result line, shows the generic text instead of the real cause (ETP-5548).
 *
 * <p>Every {@link NdjsonOnboardingProgressSink} built on this writer reports its results here, so
 * the record holds however many sinks the provisioning chain creates.
 */
public class OnboardingStreamWriter extends PrintWriter {

  private final PrintWriter target;
  private boolean resultWritten;
  private boolean resultSuccess;
  private String resultMessage;
  private String resultCode;

  /**
   * Wraps the response writer.
   *
   * @param target the servlet response writer the stream is delivered through
   */
  public OnboardingStreamWriter(PrintWriter target) {
    super(target);
    this.target = target;
  }

  /**
   * Records a result line that was just written. Only the first one is kept: it is the line that
   * describes the failure, anything after it is follow-up.
   *
   * @param success whether the result reported success
   * @param message the result message
   * @param code the stable result code, possibly {@code null}
   */
  public synchronized void recordResult(boolean success, String message, String code) {
    if (resultWritten) {
      return;
    }
    resultWritten = true;
    resultSuccess = success;
    resultMessage = message;
    resultCode = code;
  }

  /** @return whether a result line has already been written to the stream */
  public synchronized boolean hasResult() {
    return resultWritten;
  }

  /** @return whether the first result line reported success */
  public synchronized boolean isResultSuccess() {
    return resultSuccess;
  }

  /** @return the message of the first result line, or {@code null} when none was written */
  public synchronized String getResultMessage() {
    return resultMessage;
  }

  /** @return the code of the first result line, or {@code null} when it carried none */
  public synchronized String getResultCode() {
    return resultCode;
  }

  /**
   * Also reports an error of the wrapped writer. A {@link PrintWriter} swallows the
   * {@code IOException} of a broken pipe and only flags it on itself, so the wrapper alone would
   * never see a client that disconnected.
   */
  @Override
  public boolean checkError() {
    boolean ownError = super.checkError();
    return ownError || target.checkError();
  }
}
