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

import java.io.Writer;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The reverse of {@link NdjsonOnboardingProgressSink}: a {@link Writer} that reads the NDJSON
 * onboarding stream line by line and replays each line onto an {@link OnboardingProgressSink}.
 *
 * <p>ETP-5048 — the onboarding run writes its progress as NDJSON, because the browser is its main
 * reader. A run with no browser attached (a paid purchase the billing reconciliation completes)
 * wraps this writer in a {@link java.io.PrintWriter} and an {@link OnboardingStreamWriter}, so the
 * very same run reports to a sink instead, with no second code path to drift from the first.
 *
 * <p>Blank lines (the keepalive) and lines that are not a JSON object of a known type are skipped:
 * they carry nothing a sink can use.
 */
public class NdjsonSinkWriter extends Writer {

  private static final Logger log = LogManager.getLogger(NdjsonSinkWriter.class);
  private static final String FIELD_TYPE = "type";

  private final OnboardingProgressSink sink;
  private final StringBuilder line = new StringBuilder();

  /**
   * Creates a writer that replays the stream onto {@code sink}.
   *
   * @param sink where each progress and result line goes
   */
  public NdjsonSinkWriter(OnboardingProgressSink sink) {
    this.sink = sink;
  }

  @Override
  public synchronized void write(char[] buffer, int offset, int length) {
    for (int i = offset; i < offset + length; i++) {
      char c = buffer[i];
      if (c == '\n') {
        dispatch(line.toString());
        line.setLength(0);
      } else if (c != '\r') {
        line.append(c);
      }
    }
  }

  @Override
  public synchronized void flush() {
    // Lines are dispatched as soon as they end; a partial line waits for its terminator.
  }

  @Override
  public synchronized void close() {
    if (line.length() > 0) {
      dispatch(line.toString());
      line.setLength(0);
    }
  }

  private void dispatch(String text) {
    if (StringUtils.isBlank(text)) {
      return;
    }
    JSONObject json;
    try {
      json = new JSONObject(text);
    } catch (JSONException e) {
      log.debug("Skipping a non-JSON onboarding stream line");
      return;
    }
    String type = json.optString(FIELD_TYPE, "");
    if ("progress".equals(type)) {
      sink.progress(json.optString("step", ""), json.optString("status", ""),
          json.optString("message", ""));
    } else if ("result".equals(type)) {
      sink.result(json.optBoolean("success", false), json.optString("message", ""),
          json.has("code") && !json.isNull("code") ? json.optString("code") : null);
    }
  }
}
