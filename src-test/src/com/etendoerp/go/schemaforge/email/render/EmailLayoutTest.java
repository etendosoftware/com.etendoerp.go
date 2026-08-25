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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link EmailLayout}, the single place in the module allowed to emit email markup
 * (ETP-5003).
 */
public class EmailLayoutTest {

  private static EmailContent.Builder minimal() {
    return EmailContent.builder().paragraph("Cuerpo del mensaje");
  }

  @Test
  public void rendersACompleteDocumentBecauseTheProviderWrapsNothing() {
    String html = EmailLayout.render(minimal().build());

    assertTrue(html.startsWith("<!DOCTYPE html>"));
    assertTrue(html.trim().endsWith("</html>"));
    assertTrue(html.contains("<body"));
  }

  @Test
  public void carriesTheLogoAsImagePlusLiveText() {
    String html = EmailLayout.render(minimal().build());

    assertTrue(html.contains(EmailLayout.LOGO_URL));
    assertTrue(html.contains("alt=\"Etendo\""));
    // The wordmark must be text, not part of the image: it has to survive a client that blocks
    // remote images.
    assertTrue(html.contains(">Etendo</td>"));
  }

  @Test
  public void pinsTheLogoToProductionRegardlessOfEnvironment() {
    assertEquals("https://go.etendo.cloud/favicon.png", EmailLayout.LOGO_URL);
  }

  @Test
  public void emitsBothPalettes() {
    String html = EmailLayout.render(minimal().build());

    assertTrue(html.contains(EmailPalette.LIGHT_CARD_BACKGROUND));
    assertTrue(html.contains("@media (prefers-color-scheme: dark)"));
    assertTrue(html.contains(EmailPalette.DARK_CARD_BACKGROUND));
  }

  @Test
  public void escapesUntrustedText() {
    String html = EmailLayout.render(
        EmailContent.builder().paragraph("Tom & Jerry <script>alert(1)</script>").build());

    assertFalse(html.contains("<script>"));
    assertTrue(html.contains("Tom &amp; Jerry &lt;script&gt;"));
  }

  @Test
  public void leavesPreEscapedMarkupAlone() {
    String html = EmailLayout.render(
        EmailContent.builder().paragraphHtml("Hola <strong>ACME</strong>").build());

    assertTrue(html.contains("Hola <strong>ACME</strong>"));
  }

  @Test
  public void rendersTheCtaWithAnOutlookFallback() {
    String html = EmailLayout.render(
        minimal().cta("Aceptar invitación", "https://go.etendo.cloud/invite?token=abc").build());

    assertTrue(html.contains("v:roundrect"));
    assertTrue(html.contains("<!--[if mso]>"));
    assertTrue(html.contains("href=\"https://go.etendo.cloud/invite?token=abc\""));
  }

  @Test
  public void omitsTheCtaBlockWhenEitherHalfIsMissing() {
    assertFalse(EmailLayout.render(minimal().cta("Aceptar", null).build()).contains("v:roundrect"));
    assertFalse(EmailLayout.render(minimal().cta(null, "https://x.test").build())
        .contains("v:roundrect"));
  }

  @Test
  public void omitsTheLinkFallbackWhenThereIsNoCta() {
    String html = EmailLayout.render(minimal().linkFallbackText("Si el botón no funciona:").build());

    assertFalse(html.contains("Si el botón no funciona:"));
  }

  @Test
  public void rendersNotesAndSignature() {
    String html = EmailLayout.render(
        minimal().note("Válido 24 horas.").signature("Saludos, Equipo de Etendo Go").build());

    assertTrue(html.contains("Válido 24 horas."));
    assertTrue(html.contains("Saludos, Equipo de Etendo Go"));
  }

  @Test
  public void keepsTheDocumentWellUnderTheGmailClippingThreshold() {
    String html = EmailLayout.render(minimal()
        .greetingHtml("Hola, <strong>Santiago</strong>:")
        .cta("Aceptar invitación", "https://go.etendo.cloud/invite?token=abc")
        .linkFallbackText("Si el botón no funciona, copia el enlace:")
        .note("Válido 24 horas.")
        .signature("Saludos, Equipo de Etendo Go")
        .build());

    // Gmail clips around 102KB. A layout that ever approaches it has grown a bug, not a feature.
    assertTrue("layout grew to " + html.length() + " chars", html.length() < 20000);
  }
}
