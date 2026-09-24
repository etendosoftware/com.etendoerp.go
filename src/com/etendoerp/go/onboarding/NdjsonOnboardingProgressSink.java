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
import java.time.Instant;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Streams onboarding progress as NDJSON lines — the wire format {@code POST /sws/go/onboarding}
 * has always produced (see {@code docs/onboarding-flow.md}, "NDJSON Progress Events"). Moved here
 * verbatim from {@code EtendoGoJwtServlet#sendProgress}/{@code #sendFinalResult} (ETP-5389) so the
 * provisioning chain no longer needs the servlet to report.
 */
public class NdjsonOnboardingProgressSink implements OnboardingProgressSink {

  private static final Logger log = LogManager.getLogger(NdjsonOnboardingProgressSink.class);

  private static final String FIELD_TYPE = "type";
  private static final String FIELD_STATUS = "status";
  private static final String FIELD_MESSAGE = "message";
  private static final String FIELD_SUCCESS = "success";
  private static final String FIELD_CODE = "code";
  private static final String FIELD_TIMESTAMP = "timestamp";

  private final PrintWriter writer;

  public NdjsonOnboardingProgressSink(PrintWriter writer) {
    this.writer = writer;
  }

  @Override
  public void progress(String step, String status, String message) {
    try {
      JSONObject progress = new JSONObject();
      progress.put(FIELD_TYPE, "progress");
      progress.put("step", step);
      progress.put(FIELD_STATUS, status);
      progress.put(FIELD_MESSAGE, message);
      progress.put(FIELD_TIMESTAMP, Instant.now().toString());
      writer.println(progress.toString());
      writer.flush();
      // If the flush failed the client is already gone (broken pipe, swallowed by
      // PrintWriter). Log at DEBUG which step was streaming so the cut point is
      // identifiable when onboarding-stream logging is enabled.
      if (writer.checkError()) {
        log.debug("Client connection lost while streaming onboarding step '{}' (status={})",
            step, status);
      }
    } catch (JSONException e) {
      log.warn("Error writing progress", e);
    }
  }

  /**
   * Writes the final NDJSON result line, optionally tagged with a stable error code.
   *
   * <p>Provisioning failures carry unresolved Etendo AD message keys (e.g.
   * {@code @CreateClientFailed@}) that the UI cannot translate and must never display. The code
   * gives the client something stable to localize, while {@code message} stays in the payload for
   * non-UI callers and logs (ETP-4665).
   */
  @Override
  public void result(boolean success, String message, String code) {
    try {
      JSONObject result = new JSONObject();
      result.put(FIELD_TYPE, "result");
      result.put(FIELD_SUCCESS, success);
      result.put(FIELD_MESSAGE, message);
      if (code != null) {
        result.put(FIELD_CODE, code);
      }
      result.put(FIELD_TIMESTAMP, Instant.now().toString());
      writer.println(result.toString());
      writer.flush();
      // The final result line is what the UI waits for. If the flush failed the client
      // never received it (broken pipe swallowed by PrintWriter) — the UI will report a
      // false failure even though the backend finished. Make that explicit.
      if (writer.checkError()) {
        log.warn("Onboarding final result (success={}) could not be delivered to the client; "
            + "the connection was already closed (likely a CloudFront/proxy stream timeout).",
            success);
      }
    } catch (JSONException e) {
      log.warn("Error writing final result", e);
    }
  }
}
