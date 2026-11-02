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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ETP-6013 — the printable builders' NEO reads: the dispatch key of the customization (resolved
 * through {@code NeoHandlerLookup.forEntity}, never by reading {@code Java_Qualifier} here) and the
 * unwrapping {@code fetchJson} / {@code fetchAll} do in the browser.
 *
 * @covers com.etendoerp.go.schemaforge.NeoPrintableReads
 */
@DisplayName("ETP-6013 — NeoPrintableReads")
class NeoPrintableReadsTest {

  /** A legacy, qualifier-bound customization: REST's resolver matches it by its {@code @Named}. */
  @Named("legacy-read-handler")
  static class LegacyHandler implements NeoHandler {
    @Override
    public NeoResponse handle(NeoContext context) {
      return null;
    }
  }

  /** A customization without {@code @Named}, as an {@code @NeoExtension} class is. */
  static class AnnotationBoundHandler implements NeoHandler {
    @Override
    public NeoResponse handle(NeoContext context) {
      return null;
    }
  }

  @Test
  @DisplayName("a legacy handler dispatches under its @Named; an annotated one, or none, under null")
  void dispatchKeyOfTheResolvedCustomization() {
    assertEquals("legacy-read-handler", NeoPrintableReads.dispatchKeyOf(new LegacyHandler()));
    assertNull(NeoPrintableReads.dispatchKeyOf(new AnnotationBoundHandler()),
        "an @NeoExtension class is resolved from the annotation index, not a qualifier");
    assertNull(NeoPrintableReads.dispatchKeyOf(null), "no customization: the generic read");
  }

  @Test
  @DisplayName("unwrapping: the first row of response.data, the data object, or the body as is")
  void unwrapsLikeTheBrowser() throws Exception {
    JSONObject row = new JSONObject().put("id", "A");
    assertEquals("A", NeoPrintableReads.unwrapOne(new JSONObject()
        .put("response", new JSONObject().put("data", new JSONArray().put(row)))).getString("id"));
    assertNull(NeoPrintableReads.unwrapOne(new JSONObject()
        .put("response", new JSONObject().put("data", new JSONArray()))));
    JSONObject body = new JSONObject().put("plain", true);
    assertSame(body, NeoPrintableReads.unwrapOne(body));
    assertEquals(1, NeoPrintableReads.unwrapAll(new JSONObject()
        .put("response", new JSONObject().put("data", new JSONArray().put(row)))).length());
    assertEquals(0, NeoPrintableReads.unwrapAll(new JSONObject()).length());
  }
}
