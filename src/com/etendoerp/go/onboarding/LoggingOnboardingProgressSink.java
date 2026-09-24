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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Progress sink for provisioning runs that have no HTTP caller — the tenant pool filler
 * (ETP-5389). Logs every transition and remembers the last error, which is what the filler
 * records on the pool row when a run fails: the chain reports most failures by returning
 * {@code false} after an {@code error} event rather than by throwing.
 */
public class LoggingOnboardingProgressSink implements OnboardingProgressSink {

  private static final Logger log = LogManager.getLogger(LoggingOnboardingProgressSink.class);
  private static final String STATUS_ERROR = "error";

  private final String subject;
  private String lastError;

  /**
   * @param subject what is being provisioned, prefixed to every log line
   */
  public LoggingOnboardingProgressSink(String subject) {
    this.subject = subject;
  }

  @Override
  public void progress(String step, String status, String message) {
    if (STATUS_ERROR.equals(status)) {
      lastError = step + ": " + message;
      log.warn("[{}] {} {}: {}", subject, step, status, message);
    } else {
      log.debug("[{}] {} {}: {}", subject, step, status, message);
    }
  }

  @Override
  public void result(boolean success, String message, String code) {
    if (!success) {
      if (lastError == null) {
        lastError = message;
      }
      log.warn("[{}] provisioning failed: {} (code={})", subject, message, code);
    } else {
      log.info("[{}] provisioning finished: {}", subject, message);
    }
  }

  /** @return the last reported error, or {@code null} when nothing failed */
  public String getLastError() {
    return lastError;
  }
}
