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
 *
 * <p>The ETP-6209 card ({@link EmailCardLayout}) and Etendo footer ({@link EmailFooter}) are drawn
 * only through {@link EmailLayout#render}, so their rendering rules are tested here too.
 *
 * @covers com.etendoerp.go.schemaforge.email.render.EmailLayout
 * @covers com.etendoerp.go.schemaforge.email.render.EmailCardLayout
 * @covers com.etendoerp.go.schemaforge.email.render.EmailFooter
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
    assertEquals("https://app.etendo.ai/favicon.png", EmailLayout.LOGO_URL);
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
        minimal().note("Válido 7 días.").signature("Saludos, Equipo de Etendo").build());

    assertTrue(html.contains("Válido 7 días."));
    assertTrue(html.contains("Saludos, Equipo de Etendo"));
  }

  @Test
  public void keepsTheDocumentWellUnderTheGmailClippingThreshold() {
    String html = EmailLayout.render(minimal()
        .greetingHtml("Hola, <strong>Santiago</strong>:")
        .cta("Aceptar invitación", "https://go.etendo.cloud/invite?token=abc")
        .linkFallbackText("Si el botón no funciona, copia el enlace:")
        .note("Válido 7 días.")
        .signature("Saludos, Equipo de Etendo")
        .build());

    // Gmail clips around 102KB. A layout that ever approaches it has grown a bug, not a feature.
    assertTrue("layout grew to " + html.length() + " chars", html.length() < 20000);
  }

  @Test
  public void rendersTheSummaryBlockAsATwoColumnTable() {
    String html = EmailLayout.render(minimal()
        .detail("Factura de venta", "10000016")
        .detail("Fecha", "26/08/2026")
        .build());

    assertTrue(html.contains(">Factura de venta</td>"));
    assertTrue(html.contains(">10000016</td>"));
    assertTrue(html.contains(">Fecha</td>"));
    assertTrue(html.contains(">26/08/2026</td>"));
    // The value column is right-aligned through the cell attribute: Word's engine ignores
    // text-align on a block, so the attribute is what actually holds the layout together.
    assertTrue(html.contains("align=\"right\""));
  }

  @Test
  public void omitsTheSummaryBlockWhenThereAreNoRows() {
    String html = EmailLayout.render(minimal().build());

    assertFalse(html.contains("border-collapse:collapse"));
  }

  @Test
  public void dropsASummaryRowWhoseValueIsMissing() {
    // A document without a due date must not print the label with nothing beside it.
    String html = EmailLayout.render(minimal()
        .detail("Vencimiento", null)
        .detail("Total", "133,10 EUR")
        .build());

    assertFalse(html.contains("Vencimiento"));
    assertTrue(html.contains(">133,10 EUR</td>"));
  }

  @Test
  public void escapesBothSidesOfASummaryRow() {
    String html = EmailLayout.render(minimal()
        .detail("<b>Label</b>", "<script>alert(1)</script>")
        .build());

    assertFalse(html.contains("<script>"));
    assertTrue(html.contains("&lt;script&gt;"));
    assertTrue(html.contains("&lt;b&gt;Label&lt;/b&gt;"));
  }

  // ── ETP-6209: the Etendo footer and the decision card ─────────────────────────

  /**
   * A standard letter (no title, no footer) carries none of the ETP-6209 markup, and asking for the
   * footer only inserts it: removing the inserted run gives back the letter byte for byte. This
   * stands in for a byte-for-byte comparison with the pre-change output, which cannot be captured
   * without running the old code; the refactor of the letter itself only swapped literals for
   * constants of the same value.
   */
  @Test
  public void theFooterIsPurelyAdditiveAndAStandardLetterCarriesNoneOfTheNewMarkup() {
    EmailFooter footer = EmailFooter.etendo(EmailFooter.Piece.DOCUMENT_SEND, "es_ES");
    String plain = EmailLayout.render(letter().build());
    String withFooter = EmailLayout.render(letter().footer(footer).build());

    // Only markup that did not exist before the card and footer (the letter already used sf-card).
    for (String marker : new String[] { EmailLayout.CARD_FONT_STACK,
        EmailLayout.FOOTER_ICON_BACKGROUND, footer.getTitle() }) {
      assertFalse(marker, plain.contains(marker));
    }
    int prefix = 0;
    while (prefix < plain.length() && plain.charAt(prefix) == withFooter.charAt(prefix)) {
      prefix++;
    }
    int inserted = withFooter.length() - plain.length();
    assertTrue(inserted > 0);
    assertEquals("the footer only inserts markup", plain,
        withFooter.substring(0, prefix) + withFooter.substring(prefix + inserted));
    String footerMarkup = withFooter.substring(prefix, prefix + inserted);
    assertTrue(footerMarkup, footerMarkup.contains(footer.getTitle()));
    assertTrue(footerMarkup, footerMarkup.contains(EmailLayout.FOOTER_ICON_BACKGROUND));
  }

  /** The privacy row is drawn exactly when its URL is set; while it is blank it is omitted. */
  @Test
  public void theFooterPrivacyRowFollowsWhetherItsUrlIsSet() {
    EmailFooter footer = EmailFooter.etendo(EmailFooter.Piece.LINK_RENEWAL, "es_ES");
    boolean urlSet = !EmailFooter.PRIVACY_URL.trim().isEmpty();

    String html = EmailLayout.render(minimal().footer(footer).build());

    assertEquals(urlSet, footer.getPrivacyUrl() != null);
    assertEquals(urlSet, html.contains(footer.getPrivacyLabel()));
    assertTrue(html.contains(footer.getSiteLabel()));
  }

  /**
   * No piece carries a tracking query yet, so every footer link is the bare URL, never with a
   * dangling {@code ?} or {@code &}.
   */
  @Test
  public void footerLinksAreTheBareUrlsWhileNoPieceHasATrackingQuery() {
    for (EmailFooter.Piece piece : EmailFooter.Piece.values()) {
      EmailFooter footer = EmailFooter.etendo(piece, "en_US");

      assertEquals(piece.name(), EmailFooter.PRICING_URL, footer.getCtaUrl());
      assertEquals(piece.name(), EmailFooter.SITE_URL, footer.getSiteUrl());
      assertEquals(piece.name(), "https://x.test/a?b=1", EmailFooter.link("https://x.test/a?b=1",
          piece));
    }
    assertEquals("https://x.test/a", EmailFooter.link("https://x.test/a", (EmailFooter.Piece) null));
  }

  /** A tracking query follows {@code ?}, or {@code &} when the link already has a query. */
  @Test
  public void aTrackingQueryIsAppendedWithTheRightSeparator() {
    // Columns: base, tracking query, expected link.
    String[][] cases = {
        { "https://x.test/a", null, "https://x.test/a" },
        { "https://x.test/a", " ", "https://x.test/a" },
        { "https://x.test/a", "utm_source=email", "https://x.test/a?utm_source=email" },
        { "https://x.test/a?b=1", "utm_source=email", "https://x.test/a?b=1&utm_source=email" },
    };
    for (String[] c : cases) {
      assertEquals(c[0] + " + " + c[1], c[2], EmailFooter.link(c[0], c[1]));
    }
  }

  @Test
  public void theCardDeclaresTheEmailsPrimaryLanguageAndFallsBackToSpanish() {
    // Columns: content language, lang attribute.
    String[][] cases = { { "es_ES", "es" }, { "en_US", "en" }, { "en-US", "en" }, { "FR", "fr" },
        { null, "es" }, { "", "es" }, { "1x_ES", "es" }, { "e$_ES", "es" } };
    for (String[] c : cases) {
      String html = EmailLayout.render(EmailContent.builder().title("Confirma")
          .language(c[0]).build());

      assertTrue(c[0] + ": " + html.substring(0, 60),
          html.contains("<html lang=\"" + c[1] + "\">"));
    }
  }

  @Test
  public void aLogoIsFittedInsideTheAvatarKeepingItsAspect() {
    // Columns: natural width, natural height, fitted width, fitted height (44px diagonal).
    int[][] cases = { { 100, 100, 31, 31 }, { 300, 100, 42, 14 }, { 100, 300, 14, 42 },
        { 0, 0, 31, 31 }, { -5, 40, 31, 31 } };
    for (int[] c : cases) {
      int[] fitted = EmailCardLayout.fitInAvatar(c[0], c[1]);

      assertEquals(c[0] + "x" + c[1], c[2] + "x" + c[3], fitted[0] + "x" + fitted[1]);
    }
  }

  private static EmailContent.Builder letter() {
    return EmailContent.builder().greeting("Hola Ana,").paragraph("Te enviamos la factura.")
        .detail("Total", "133,10 EUR").cta("Ver factura", "https://app.example.test/doc")
        .linkFallbackText("Si el botón no funciona:").note("Nota").signature("Acme");
  }
}
