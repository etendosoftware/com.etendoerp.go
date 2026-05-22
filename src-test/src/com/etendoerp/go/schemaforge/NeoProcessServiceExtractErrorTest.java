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

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Unit tests for the private {@code extractProcessViewError} method of
 * {@link NeoProcessService} and for the {@code translateObuiappResult} behavior
 * it feeds into.
 *
 * <p>Both methods are inaccessible from outside the class, so tests use
 * reflection to invoke them directly without spinning up Etendo infrastructure.
 */
public class NeoProcessServiceExtractErrorTest {

  private static Method extractError;
  private static Method translateResult;

  @BeforeClass
  public static void setUp() throws Exception {
    extractError = NeoProcessService.class
        .getDeclaredMethod("extractProcessViewError", JSONObject.class);
    extractError.setAccessible(true);

    translateResult = NeoProcessService.class
        .getDeclaredMethod("translateObuiappResult", JSONObject.class);
    translateResult.setAccessible(true);
  }

  private JSONObject invokeExtract(JSONObject input) throws Exception {
    try {
      return (JSONObject) extractError.invoke(null, input);
    } catch (InvocationTargetException e) {
      throw (Exception) e.getCause();
    }
  }

  private NeoResponse invokeTranslate(JSONObject input) throws Exception {
    try {
      return (NeoResponse) translateResult.invoke(null, input);
    } catch (InvocationTargetException e) {
      throw (Exception) e.getCause();
    }
  }

  @Test
  public void testExtractReturnsNullWhenNoResponseActionsKey() throws Exception {
    JSONObject input = new JSONObject().put("status", "success");
    assertNull(invokeExtract(input));
  }

  @Test
  public void testExtractReturnsNullForEmptyResponseActionsArray() throws Exception {
    JSONObject input = new JSONObject().put("responseActions", new JSONArray());
    assertNull(invokeExtract(input));
  }

  @Test
  public void testExtractReturnsNullWhenNoShowMsgInProcessView() throws Exception {
    JSONObject action = new JSONObject().put("showMsgInView", new JSONObject().put("msgType", "error"));
    JSONObject input = new JSONObject().put("responseActions", new JSONArray().put(action));
    assertNull(invokeExtract(input));
  }

  @Test
  public void testExtractReturnsNullWhenMsgTypeIsNotError() throws Exception {
    JSONObject msg = new JSONObject().put("msgType", "success").put("msgText", "All good");
    JSONObject action = new JSONObject().put("showMsgInProcessView", msg);
    JSONObject input = new JSONObject().put("responseActions", new JSONArray().put(action));
    assertNull(invokeExtract(input));
  }

  @Test
  public void testExtractReturnsMessageWhenMsgTypeIsError() throws Exception {
    JSONObject msg = new JSONObject().put("msgType", "error").put("msgText", "Invoice not posted");
    JSONObject action = new JSONObject().put("showMsgInProcessView", msg);
    JSONObject input = new JSONObject().put("responseActions", new JSONArray().put(action));

    JSONObject result = invokeExtract(input);

    assertNotNull(result);
    assertEquals("Invoice not posted", result.getString("msgText"));
  }

  @Test
  public void testExtractIsCaseInsensitiveOnMsgType() throws Exception {
    JSONObject msg = new JSONObject().put("msgType", "ERROR").put("msgText", "Something went wrong");
    JSONObject action = new JSONObject().put("showMsgInProcessView", msg);
    JSONObject input = new JSONObject().put("responseActions", new JSONArray().put(action));

    JSONObject result = invokeExtract(input);

    assertNotNull(result);
    assertEquals("Something went wrong", result.getString("msgText"));
  }

  @Test
  public void testExtractReturnsFirstErrorAmongMultipleActions() throws Exception {
    JSONObject successMsg = new JSONObject().put("msgType", "success").put("msgText", "ok");
    JSONObject errorMsg   = new JSONObject().put("msgType", "error").put("msgText", "first error");

    JSONArray actions = new JSONArray()
        .put(new JSONObject().put("showMsgInProcessView", successMsg))
        .put(new JSONObject().put("showMsgInProcessView", errorMsg));

    JSONObject input = new JSONObject().put("responseActions", actions);

    JSONObject result = invokeExtract(input);

    assertNotNull(result);
    assertEquals("first error", result.getString("msgText"));
  }

  @Test
  public void testTranslateReturns400WhenResponseActionsContainsError() throws Exception {
    JSONObject msg = new JSONObject().put("msgType", "error").put("msgText", "SII rejected");
    JSONObject action = new JSONObject().put("showMsgInProcessView", msg);
    JSONObject handlerResult = new JSONObject().put("responseActions", new JSONArray().put(action));

    NeoResponse response = invokeTranslate(handlerResult);

    assertNotNull(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals("error", response.getBody().getString("status"));
    assertEquals("SII rejected", response.getBody().getString("message"));
  }

  @Test
  public void testTranslateReturns200WhenResponseActionsContainsNoError() throws Exception {
    JSONObject msg = new JSONObject().put("msgType", "success").put("msgText", "Sent OK");
    JSONObject action = new JSONObject().put("showMsgInProcessView", msg);
    JSONObject handlerResult = new JSONObject().put("responseActions", new JSONArray().put(action));

    NeoResponse response = invokeTranslate(handlerResult);

    assertNotNull(response);
    assertEquals(200, response.getHttpStatus());
  }

  @Test
  public void testTranslateDefaultsMsgTextWhenMissing() throws Exception {
    JSONObject msg = new JSONObject().put("msgType", "error");
    JSONObject action = new JSONObject().put("showMsgInProcessView", msg);
    JSONObject handlerResult = new JSONObject().put("responseActions", new JSONArray().put(action));

    NeoResponse response = invokeTranslate(handlerResult);

    assertEquals(400, response.getHttpStatus());
    assertEquals("Process failed", response.getBody().getString("message"));
  }
}
