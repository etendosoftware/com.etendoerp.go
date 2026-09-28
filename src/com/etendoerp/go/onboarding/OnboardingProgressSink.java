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

/**
 * Where the tenant-provisioning chain reports its progress (ETP-5389).
 *
 * <p>The chain used to write NDJSON lines straight into the servlet's {@code PrintWriter}, which
 * tied it to an HTTP response. This is the seam that lets the same chain run with no request at
 * all: the onboarding endpoint streams through {@link NdjsonOnboardingProgressSink}, the tenant
 * pool filler through {@link LoggingOnboardingProgressSink}. A sink never throws — a reporting
 * failure must not turn into a provisioning failure.
 */
public interface OnboardingProgressSink {

  /**
   * Reports one step transition.
   *
   * @param step stable step key, e.g. {@code dataset}
   * @param status {@code in_progress}, {@code done} or {@code error}
   * @param message human-readable detail
   */
  void progress(String step, String status, String message);

  /**
   * Reports the terminal outcome of the run.
   *
   * @param success whether provisioning finished
   * @param message human-readable detail
   * @param code stable error code the UI can localize, or {@code null}
   */
  void result(boolean success, String message, String code);
}
