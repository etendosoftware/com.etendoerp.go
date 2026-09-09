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

package com.etendoerp.go.schemaforge.email.render;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for the shared email escaping and emphasis rules (ETP-5003).
 */
public class EmailEscapeTest {

  @Test
  public void turnsMarkersIntoStrongTags() {
    assertEquals("Le enviamos su Factura <strong>10000016</strong>.",
        EmailEscape.applyBold("Le enviamos su Factura **10000016**."));
  }

  @Test
  public void convertsEveryRunInTheSameLine() {
    assertEquals("<strong>Hola</strong> y <strong>chau</strong>",
        EmailEscape.applyBold("**Hola** y **chau**"));
  }

  @Test
  public void leavesAnUnclosedMarkerAlone() {
    // An unclosed marker must not swallow the rest of the message.
    assertEquals("Total ** pendiente", EmailEscape.applyBold("Total ** pendiente"));
    assertEquals("Precio **10 euros", EmailEscape.applyBold("Precio **10 euros"));
  }

  @Test
  public void neverSpansALineBreak() {
    assertEquals("abre **\ncierra**", EmailEscape.applyBold("abre **\ncierra**"));
  }

  @Test
  public void leavesTextWithoutMarkersUntouched() {
    assertEquals("sin marcadores", EmailEscape.applyBold("sin marcadores"));
    assertEquals("", EmailEscape.applyBold(null));
  }

  @Test
  public void escapingBeforeEmphasisKeepsCallerMarkupInert() {
    // This is the ordering contract the whole feature rests on: asterisks survive escaping, so
    // escaping first yields emphasis AND an inert <script>. Reversing it would emit live markup.
    String operatorText = "<script>alert(1)</script> con **negrita**";

    String rendered = EmailEscape.applyBold(EmailEscape.escapeHtml(operatorText));

    assertEquals("&lt;script&gt;alert(1)&lt;/script&gt; con <strong>negrita</strong>", rendered);
  }

  @Test
  public void emphasisCannotBeSmuggledThroughAnEscapedValue() {
    // A document number carrying markup is escaped before it ever reaches applyBold.
    assertEquals("&lt;strong&gt;x&lt;/strong&gt;",
        EmailEscape.applyBold(EmailEscape.escapeHtml("<strong>x</strong>")));
  }
}
