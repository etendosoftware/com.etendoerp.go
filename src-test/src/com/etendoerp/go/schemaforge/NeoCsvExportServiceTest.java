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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NeoCsvExportService} — pure (no DB / OBContext). Builds
 * the standard list envelope, drives {@code tryExport}, and asserts the streamed
 * CSV plus the download headers.
 */
class NeoCsvExportServiceTest {

  private static NeoResponse envelope(String key, JSONArray rows) throws Exception {
    JSONObject data = new JSONObject().put(key, rows);
    JSONObject responseData = new JSONObject().put("data", data);
    JSONObject body = new JSONObject().put("response", responseData);
    return NeoResponse.ok(body);
  }

  private static Map<String, String> params(String... kv) {
    Map<String, String> m = new HashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return m;
  }

  /** Mocked response whose writer captures everything into a StringWriter. */
  private static class CapturingResponse {
    final StringWriter sw = new StringWriter();
    final HttpServletResponse response;

    CapturingResponse() throws IOException {
      response = mock(HttpServletResponse.class);
      when(response.getWriter()).thenReturn(new PrintWriter(sw));
    }

    String csv() {
      return sw.toString();
    }
  }

  @Test
  void doesNothingWithoutTheExportFlag() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    NeoResponse res = envelope("statements", new JSONArray().put(new JSONObject().put("id", "s1")));

    boolean handled = NeoCsvExportService.tryExport(res, params("foo", "bar"), cap.response);

    assertFalse(handled);
    verify(cap.response, never()).getWriter();
  }

  @Test
  void serializesSelectedColumnsWithLabelsDateFormattingAndEscaping() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    JSONArray rows = new JSONArray().put(new JSONObject()
        .put("id", "s1")
        .put("documentNo", "BS-001")
        .put("importDate", "2026-06-10")
        .put("note", "a,b\"c"));
    NeoResponse res = envelope("statements", rows);

    boolean handled = NeoCsvExportService.tryExport(
        res,
        params("export", "csv",
            "columns", "documentNo:Document No.|importDate:Import Date:date|note:Note",
            "filename", "Extractos"),
        cap.response);

    assertTrue(handled);
    String csv = cap.csv();
    // Header uses the labels; rows use the values, with ISO date → dd-MM-yyyy.
    assertTrue(csv.contains("\"Document No.\",\"Import Date\",\"Note\""), csv);
    assertTrue(csv.contains("\"BS-001\",\"10-06-2026\""), csv);
    // RFC 4180: inner quotes doubled, comma kept inside the quoted field.
    assertTrue(csv.contains("\"a,b\"\"c\""), csv);

    verify(cap.response).setContentType(contains("text/csv"));
    verify(cap.response).setHeader(eq("Content-Disposition"), contains("Extractos.csv"));
  }

  @Test
  void keepsOnlyRowsWhoseIdIsInTheIdsFilter() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    JSONArray rows = new JSONArray()
        .put(new JSONObject().put("id", "s1").put("documentNo", "KEEP"))
        .put(new JSONObject().put("id", "s2").put("documentNo", "DROP"));
    NeoResponse res = envelope("statements", rows);

    NeoCsvExportService.tryExport(res,
        params("export", "csv", "ids", "s1", "columns", "documentNo:Doc"), cap.response);

    String csv = cap.csv();
    assertTrue(csv.contains("KEEP"), csv);
    assertFalse(csv.contains("DROP"), csv);
  }

  @Test
  void resolvesDottedPathsIntoNestedArrays() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    JSONArray rows = new JSONArray().put(new JSONObject()
        .put("id", "l1")
        .put("txns", new JSONArray().put(new JSONObject().put("documentNo", "PAY-1"))));
    NeoResponse res = envelope("lines", rows);

    NeoCsvExportService.tryExport(res,
        params("export", "csv", "columns", "txns.0.documentNo:Transaction"), cap.response);

    String csv = cap.csv();
    assertTrue(csv.contains("\"Transaction\""), csv);
    assertTrue(csv.contains("\"PAY-1\""), csv);
  }

  @Test
  void derivesColumnsFromTheFirstRowWhenNoColumnsGiven() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    JSONArray rows = new JSONArray().put(new JSONObject().put("id", "s1").put("documentNo", "BS-9"));
    NeoResponse res = envelope("statements", rows);

    boolean handled = NeoCsvExportService.tryExport(res, params("export", "csv"), cap.response);

    assertTrue(handled);
    String csv = cap.csv();
    assertTrue(csv.contains("\"id\""), csv);
    assertTrue(csv.contains("\"documentNo\""), csv);
    assertTrue(csv.contains("\"BS-9\""), csv);
  }

  @Test
  void emptyRowArrayStillStreamsTheHeaderOnly() throws Exception {
    CapturingResponse cap = new CapturingResponse();
    NeoResponse res = envelope("statements", new JSONArray());

    boolean handled = NeoCsvExportService.tryExport(res,
        params("export", "csv", "columns", "documentNo:Doc|name:Name"), cap.response);

    assertTrue(handled);
    String csv = cap.csv();
    assertTrue(csv.contains("\"Doc\",\"Name\""), csv);
    // Only the header line (plus the leading BOM); no data rows.
    assertFalse(csv.replace("﻿", "").trim().contains("\n"), csv);
  }
}
