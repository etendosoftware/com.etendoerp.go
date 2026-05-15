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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NeoServletSupport#parsePath(String)}.
 */
class NeoServletSupportTest {

  // -------------------------------------------------------------------------
  // parsePath
  // -------------------------------------------------------------------------

  @Nested
  @DisplayName("parsePath")
  class ParsePath {

    @Test
    @DisplayName("Null path returns discovery (all null)")
    void nullPath() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath(null);
      assertNotNull(info);
      assertNull(info.specName);
      assertNull(info.entityName);
      assertNull(info.recordId);
    }

    @Test
    @DisplayName("Empty path returns discovery")
    void emptyPath() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath("");
      assertNotNull(info);
      assertNull(info.specName);
    }

    @Test
    @DisplayName("Root slash returns discovery")
    void rootSlash() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath("/");
      assertNotNull(info);
      assertNull(info.specName);
    }

    @Test
    @DisplayName("Single segment is spec name only")
    void singleSegment() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath("/sales-order");
      assertNotNull(info);
      assertEquals("sales-order", info.specName);
      assertNull(info.entityName);
      assertNull(info.recordId);
    }

    @Test
    @DisplayName("Two segments: spec + entity")
    void twoSegments() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath("/sales-order/order");
      assertNotNull(info);
      assertEquals("sales-order", info.specName);
      assertEquals("order", info.entityName);
      assertNull(info.recordId);
    }

    @Test
    @DisplayName("Three segments: spec + entity + recordId")
    void threeSegments() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath("/sales-order/order/ABC123");
      assertNotNull(info);
      assertEquals("sales-order", info.specName);
      assertEquals("order", info.entityName);
      assertEquals("ABC123", info.recordId);
      assertFalse(info.isSelector);
      assertFalse(info.isAction);
    }

    @Test
    @DisplayName("Selectors sub-endpoint recognized")
    void selectorsPath() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath(
          "/sales-order/order/selectors");
      assertNotNull(info);
      assertEquals("sales-order", info.specName);
      assertEquals("order", info.entityName);
      assertTrue(info.isSelector);
      assertNull(info.selectorField);
    }

    @Test
    @DisplayName("Selectors with field name recognized")
    void selectorsWithField() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath(
          "/sales-order/order/selectors/businessPartner");
      assertNotNull(info);
      assertTrue(info.isSelector);
      assertEquals("businessPartner", info.selectorField);
    }

    @Test
    @DisplayName("Evaluate-display sub-endpoint recognized")
    void evaluateDisplayPath() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath(
          "/sales-order/order/evaluate-display");
      assertNotNull(info);
      assertTrue(info.isEvaluateDisplay);
      assertFalse(info.isSelector);
      assertFalse(info.isAction);
    }

    @Test
    @DisplayName("Callout sub-endpoint recognized")
    void calloutPath() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath(
          "/sales-order/order/callout");
      assertNotNull(info);
      assertTrue(info.isCallout);
    }

    @Test
    @DisplayName("Defaults sub-endpoint recognized")
    void defaultsPath() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath(
          "/sales-order/order/defaults");
      assertNotNull(info);
      assertTrue(info.isDefaults);
    }

    @Test
    @DisplayName("Action sub-endpoint recognized")
    void actionPath() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath(
          "/sales-order/order/REC123/action");
      assertNotNull(info);
      assertTrue(info.isAction);
      assertEquals("REC123", info.recordId);
      assertNull(info.actionName);
    }

    @Test
    @DisplayName("Action with name recognized")
    void actionWithName() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath(
          "/sales-order/order/REC123/action/docAction");
      assertNotNull(info);
      assertTrue(info.isAction);
      assertEquals("REC123", info.recordId);
      assertEquals("docAction", info.actionName);
    }

    @Test
    @DisplayName("Path without leading slash is handled")
    void noLeadingSlash() {
      NeoServlet.NeoPathInfo info = NeoServletSupport.parsePath("contacts/businessPartner");
      assertNotNull(info);
      assertEquals("contacts", info.specName);
      assertEquals("businessPartner", info.entityName);
    }
  }
}