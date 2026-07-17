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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.enterprise.inject.Vetoed;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit tests for {@link AbstractNeoHandler}: isWriteMethod, optTrimmed, and
 * runWriteHook. OBContext side-effects are avoided by overriding enterAdminMode /
 * exitAdminMode in a minimal concrete subclass.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class AbstractNeoHandlerTest {

  private static final Logger LOG = LogManager.getLogger(AbstractNeoHandlerTest.class);

  /** Minimal concrete subclass that stubs out OBContext side-effects. */
  @Vetoed // not a CDI bean: a discoverable subclass makes @Inject of the real handler ambiguous
  private static class TestHandler extends AbstractNeoHandler {
    @Override
    public NeoResponse handle(NeoContext ctx) {
      return null;
    }

    @Override
    protected void enterAdminMode() {
      // no-op: suppresses OBContext side-effects in unit tests
    }

    @Override
    protected void exitAdminMode() {
      // no-op: suppresses OBContext side-effects in unit tests
    }
  }

  private final TestHandler handler = new TestHandler();

  // ── isWriteMethod ────────────────────────────────────────────────────────────

  @Test
  public void postIsWriteMethod() {
    assertTrue(handler.isWriteMethod("POST"));
  }

  @Test
  public void putIsWriteMethod() {
    assertTrue(handler.isWriteMethod("PUT"));
  }

  @Test
  public void patchIsWriteMethod() {
    assertTrue(handler.isWriteMethod("PATCH"));
  }

  @Test
  public void getIsNotWriteMethod() {
    assertFalse(handler.isWriteMethod("GET"));
  }

  @Test
  public void deleteIsNotWriteMethod() {
    assertFalse(handler.isWriteMethod("DELETE"));
  }

  @Test
  public void nullIsNotWriteMethod() {
    assertFalse(handler.isWriteMethod(null));
  }

  // ── optTrimmed ───────────────────────────────────────────────────────────────

  @Test
  public void optTrimmedNullBodyReturnsNull() {
    assertNull(AbstractNeoHandler.optTrimmed(null, "key"));
  }

  @Test
  public void optTrimmedMissingKeyReturnsNull() throws Exception {
    assertNull(AbstractNeoHandler.optTrimmed(new JSONObject(), "missing"));
  }

  @Test
  public void optTrimmedJsonNullReturnsNull() throws Exception {
    JSONObject obj = new JSONObject().put("k", JSONObject.NULL);
    assertNull(AbstractNeoHandler.optTrimmed(obj, "k"));
  }

  @Test
  public void optTrimmedBlankReturnsNull() throws Exception {
    JSONObject obj = new JSONObject().put("k", "   ");
    assertNull(AbstractNeoHandler.optTrimmed(obj, "k"));
  }

  @Test
  public void optTrimmedTrimsLeadingTrailingWhitespace() throws Exception {
    JSONObject obj = new JSONObject().put("k", "  hello  ");
    assertEquals("hello", AbstractNeoHandler.optTrimmed(obj, "k"));
  }

  @Test
  public void optTrimmedReturnsNormalString() throws Exception {
    JSONObject obj = new JSONObject().put("k", "value");
    assertEquals("value", AbstractNeoHandler.optTrimmed(obj, "k"));
  }

  // ── runWriteHook ─────────────────────────────────────────────────────────────

  private NeoContext ctxFor(String spec, String method, JSONObject body) {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn(spec);
    when(ctx.getHttpMethod()).thenReturn(method);
    when(ctx.getRequestBody()).thenReturn(body);
    return ctx;
  }

  @Test
  public void runWriteHookSpecMismatchReturnsNull() throws Exception {
    NeoContext ctx = ctxFor("other-spec", "POST", new JSONObject());
    assertNull(handler.runWriteHook(ctx, "target-spec", LOG, body -> null));
  }

  @Test
  public void runWriteHookGetMethodReturnsNull() throws Exception {
    NeoContext ctx = ctxFor("my-spec", "GET", new JSONObject());
    assertNull(handler.runWriteHook(ctx, "my-spec", LOG, body -> null));
  }

  @Test
  public void runWriteHookDeleteMethodReturnsNull() throws Exception {
    NeoContext ctx = ctxFor("my-spec", "DELETE", new JSONObject());
    assertNull(handler.runWriteHook(ctx, "my-spec", LOG, body -> null));
  }

  @Test
  public void runWriteHookNullBodyReturnsNull() throws Exception {
    NeoContext ctx = ctxFor("my-spec", "POST", null);
    assertNull(handler.runWriteHook(ctx, "my-spec", LOG, body -> null));
  }

  @Test
  public void runWriteHookValidatorReturnsNullPassesThrough() throws Exception {
    NeoContext ctx = ctxFor("my-spec", "POST", new JSONObject());
    assertNull(handler.runWriteHook(ctx, "my-spec", LOG, body -> null));
  }

  @Test
  public void runWriteHookValidatorReturnsErrorResponse() throws Exception {
    NeoResponse expected = NeoResponse.error(400, "Bad Request");
    NeoContext ctx = ctxFor("my-spec", "PUT", new JSONObject());
    NeoResponse result = handler.runWriteHook(ctx, "my-spec", LOG, body -> expected);
    assertSame(expected, result);
  }

  @Test
  public void runWriteHookPatchMethodIsHandled() throws Exception {
    NeoResponse expected = NeoResponse.error(422, "Validation failed");
    NeoContext ctx = ctxFor("my-spec", "PATCH", new JSONObject());
    NeoResponse result = handler.runWriteHook(ctx, "my-spec", LOG, body -> expected);
    assertSame(expected, result);
  }

  @Test
  public void runWriteHookValidatorExceptionMaps500() throws Exception {
    NeoContext ctx = ctxFor("my-spec", "POST", new JSONObject());
    NeoResponse result = handler.runWriteHook(ctx, "my-spec", LOG,
        body -> { throw new RuntimeException("unexpected"); });
    assertEquals(500, result.getHttpStatus());
  }
}
