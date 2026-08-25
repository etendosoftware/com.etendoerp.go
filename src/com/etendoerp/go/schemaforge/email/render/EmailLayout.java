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

import java.util.List;

import org.apache.commons.lang3.StringUtils;

/**
 * Renders {@link EmailContent} into the shared Etendo email document (ETP-5003).
 *
 * <p>This is the only place in the module allowed to emit email markup. Contracts compose
 * {@link EmailContent} blocks; everything about how an Etendo email looks lives here.</p>
 *
 * <h2>Why the markup looks like this</h2>
 * <ul>
 *   <li><b>Tables, not flexbox.</b> The design is specified in flexbox and absolute positioning;
 *   neither survives Outlook, which renders through Word's HTML engine.</li>
 *   <li><b>A complete document, not a fragment.</b> Verified against the provider on 2026-08-25:
 *   its {@code custom} template wraps nothing at all, so the document shipped here is exactly what
 *   the recipient receives.</li>
 *   <li><b>Inline styles for the light palette, a {@code <style>} block for the dark one.</b>
 *   Clients that strip {@code <style>} still get the full light design.</li>
 *   <li><b>The logo is an image plus live text.</b> Images are blocked by default in many clients;
 *   keeping the wordmark as text means the brand still reads when the image never loads.</li>
 * </ul>
 */
public final class EmailLayout {

  /** Pinned to production: an email is opened long after the environment that sent it may exist. */
  static final String LOGO_URL = "https://go.etendo.cloud/favicon.png";

  private static final String FONT_STACK =
      "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";
  private static final int CARD_WIDTH = 600;
  private static final int CARD_PADDING = 56;

  private EmailLayout() {
  }

  /**
   * Renders the content into a complete HTML document.
   *
   * @param content the blocks to render
   * @return the email document
   */
  public static String render(EmailContent content) {
    StringBuilder html = new StringBuilder(4096);
    openDocument(html);
    appendHeader(html);
    appendGreeting(html, content);
    appendParagraphs(html, content.getParagraphs());
    appendCta(html, content);
    appendNotes(html, content.getNotes());
    appendSignature(html, content.getSignature());
    closeDocument(html);
    return html.toString();
  }

  private static void openDocument(StringBuilder html) {
    html.append("<!DOCTYPE html>\n")
        .append("<html lang=\"es\"><head>")
        .append("<meta charset=\"utf-8\">")
        .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        .append("<meta name=\"color-scheme\" content=\"light dark\">")
        .append("<meta name=\"supported-color-schemes\" content=\"light dark\">")
        .append("<style>")
        .append("@media (prefers-color-scheme: dark){")
        .append(".sf-page{background:").append(EmailPalette.DARK_PAGE_BACKGROUND)
        .append(" !important}")
        .append(".sf-card{background:").append(EmailPalette.DARK_CARD_BACKGROUND)
        .append(" !important}")
        .append(".sf-divider{border-color:").append(EmailPalette.DARK_DIVIDER).append(" !important}")
        .append(".sf-text{color:").append(EmailPalette.DARK_TEXT).append(" !important}")
        .append(".sf-strong{color:").append(EmailPalette.DARK_TEXT_STRONG).append(" !important}")
        .append(".sf-link{color:").append(EmailPalette.DARK_LINK).append(" !important}")
        .append(".sf-cta{background:").append(EmailPalette.DARK_CTA_BACKGROUND).append(" !important}")
        .append(".sf-cta-label{color:").append(EmailPalette.DARK_CTA_LABEL).append(" !important}")
        .append("}")
        .append("@media (max-width:480px){")
        .append(".sf-card{width:100% !important}")
        .append(".sf-pad{padding:32px 24px !important}")
        .append("}")
        .append("</style></head>")
        .append("<body class=\"sf-page\" style=\"margin:0;padding:0;background:")
        .append(EmailPalette.LIGHT_PAGE_BACKGROUND).append(";\">")
        .append("<table role=\"presentation\" class=\"sf-page\" width=\"100%\" cellpadding=\"0\" ")
        .append("cellspacing=\"0\" border=\"0\" style=\"background:")
        .append(EmailPalette.LIGHT_PAGE_BACKGROUND).append(";padding:40px 12px;\"><tr><td align=\"center\">")
        .append("<table role=\"presentation\" class=\"sf-card\" width=\"").append(CARD_WIDTH)
        .append("\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"width:")
        .append(CARD_WIDTH).append("px;max-width:100%;background:")
        .append(EmailPalette.LIGHT_CARD_BACKGROUND).append(";\">")
        .append("<tr><td class=\"sf-pad\" style=\"padding:").append(CARD_PADDING).append("px;\">");
  }

  private static void closeDocument(StringBuilder html) {
    html.append("</td></tr></table></td></tr></table></body></html>");
  }

  private static void appendHeader(StringBuilder html) {
    html.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">")
        .append("<tr><td style=\"padding-right:12px;\" valign=\"middle\">")
        .append("<img src=\"").append(LOGO_URL).append("\" width=\"40\" height=\"40\" alt=\"Etendo\" ")
        .append("style=\"display:block;width:40px;height:40px;border:0;border-radius:10px;\"></td>")
        .append("<td valign=\"middle\" class=\"sf-strong\" style=\"font-family:").append(FONT_STACK)
        .append(";font-size:22px;font-weight:600;letter-spacing:-0.03em;color:")
        .append(EmailPalette.LIGHT_TEXT_STRONG).append(";\">Etendo</td></tr></table>")
        .append("<div class=\"sf-divider\" style=\"border-top:1px solid ")
        .append(EmailPalette.LIGHT_DIVIDER).append(";margin:28px 0 32px 0;font-size:0;line-height:0;\">")
        .append("&nbsp;</div>");
  }

  private static void appendGreeting(StringBuilder html, EmailContent content) {
    String greeting = content.getGreeting();
    if (StringUtils.isBlank(greeting)) {
      return;
    }
    html.append(paragraphOpen())
        .append(content.isGreetingHtml() ? greeting : escape(greeting))
        .append("</p>");
  }

  private static void appendParagraphs(StringBuilder html, List<EmailContent.Paragraph> paragraphs) {
    for (EmailContent.Paragraph paragraph : paragraphs) {
      html.append(paragraphOpen())
          .append(paragraph.getHtml() != null ? paragraph.getHtml() : escape(paragraph.getText()))
          .append("</p>");
    }
  }

  private static void appendCta(StringBuilder html, EmailContent content) {
    if (!content.hasCta()) {
      return;
    }
    String url = escape(content.getCtaUrl());
    // Outlook desktop drops the background of a styled anchor, so the button is drawn as a VML
    // rounded rectangle there and as a normal anchor everywhere else.
    html.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" ")
        .append("style=\"margin:32px 0;\"><tr><td class=\"sf-cta\" bgcolor=\"")
        .append(EmailPalette.LIGHT_CTA_BACKGROUND).append("\" style=\"background:")
        .append(EmailPalette.LIGHT_CTA_BACKGROUND).append(";border-radius:8px;\">")
        .append("<!--[if mso]><v:roundrect xmlns:v=\"urn:schemas-microsoft-com:vml\" ")
        .append("href=\"").append(url).append("\" style=\"height:40px;v-text-anchor:middle;width:200px;\" ")
        .append("arcsize=\"20%\" fillcolor=\"").append(EmailPalette.LIGHT_CTA_BACKGROUND)
        .append("\" stroke=\"f\"><w:anchorlock/><center style=\"color:")
        .append(EmailPalette.LIGHT_CTA_LABEL).append(";font-family:Arial,sans-serif;font-size:14px;")
        .append("font-weight:500;\">").append(escape(content.getCtaLabel()))
        .append("</center></v:roundrect><![endif]-->")
        .append("<!--[if !mso]><!--><a href=\"").append(url)
        .append("\" class=\"sf-cta-label\" style=\"display:inline-block;padding:8px 24px;font-family:")
        .append(FONT_STACK).append(";font-size:14px;line-height:24px;font-weight:500;color:")
        .append(EmailPalette.LIGHT_CTA_LABEL).append(";text-decoration:none;\">")
        .append(escape(content.getCtaLabel())).append("</a><!--<![endif]-->")
        .append("</td></tr></table>");
    appendLinkFallback(html, content.getLinkFallbackText(), url);
  }

  private static void appendLinkFallback(StringBuilder html, String fallbackText, String escapedUrl) {
    if (StringUtils.isBlank(fallbackText)) {
      return;
    }
    html.append(finePrintOpen()).append(escape(fallbackText)).append("<br>")
        .append("<a class=\"sf-link\" href=\"").append(escapedUrl).append("\" style=\"color:")
        .append(EmailPalette.LIGHT_LINK).append(";text-decoration:none;word-break:break-all;\">")
        .append(escapedUrl).append("</a></p>");
  }

  private static void appendNotes(StringBuilder html, List<String> notes) {
    for (String note : notes) {
      html.append(finePrintOpen()).append(escape(note)).append("</p>");
    }
  }

  private static void appendSignature(StringBuilder html, String signature) {
    if (StringUtils.isBlank(signature)) {
      return;
    }
    html.append(finePrintOpen()).append(escape(signature)).append("</p>");
  }

  private static String paragraphOpen() {
    return "<p class=\"sf-text\" style=\"margin:0 0 16px 0;font-family:" + FONT_STACK
        + ";font-size:14px;line-height:22px;font-weight:400;color:" + EmailPalette.LIGHT_TEXT + ";\">";
  }

  private static String finePrintOpen() {
    return "<p class=\"sf-text\" style=\"margin:0 0 12px 0;font-family:" + FONT_STACK
        + ";font-size:12px;line-height:16px;font-weight:500;color:" + EmailPalette.LIGHT_TEXT + ";\">";
  }

  private static String escape(String value) {
    return EmailEscape.escapeHtml(value);
  }
}
