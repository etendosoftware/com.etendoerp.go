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
package com.etendoerp.go.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ServletResponseUtils}.
 */
@ExtendWith(MockitoExtension.class)
class ServletResponseUtilsTest {

  @Mock private HttpServletResponse response;

  private StringWriter stringWriter;
  private PrintWriter printWriter;

  @BeforeEach
  void setUp() throws Exception {
    stringWriter = new StringWriter();
    printWriter = new PrintWriter(stringWriter);
    when(response.getWriter()).thenReturn(printWriter);
  }

  @Nested
  @DisplayName("sendError")
  class SendError {
    @Test
    void writesErrorJsonWithCorrectStatus() throws Exception {
      ServletResponseUtils.sendError(response, 400, "Bad request");
      verify(response).setStatus(400);

      printWriter.flush();
      JSONObject body = new JSONObject(stringWriter.toString());
      assertEquals("Bad request", body.getString("error"));
    }

    @Test
    void setsJsonContentType() throws Exception {
      ServletResponseUtils.sendError(response, 500, "Internal error");
      verify(response).setContentType("application/json");
    }

    @Test
    void setsUtf8Encoding() throws Exception {
      ServletResponseUtils.sendError(response, 401, "Unauthorized");
      verify(response).setCharacterEncoding("UTF-8");
    }
  }

  @Nested
  @DisplayName("writeJson")
  class WriteJson {
    @Test
    void writesJsonBodyWithGivenStatus() throws Exception {
      JSONObject body = new JSONObject();
      body.put("key", "value");

      ServletResponseUtils.writeJson(response, 200, body);
      verify(response).setStatus(200);

      printWriter.flush();
      JSONObject written = new JSONObject(stringWriter.toString());
      assertEquals("value", written.getString("key"));
    }

    @Test
    void setsContentTypeAndEncoding() throws Exception {
      ServletResponseUtils.writeJson(response, 201, new JSONObject());
      verify(response).setContentType("application/json");
      verify(response).setCharacterEncoding("UTF-8");
    }
  }
}
