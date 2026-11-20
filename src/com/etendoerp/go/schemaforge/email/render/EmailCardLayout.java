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

import static com.etendoerp.go.schemaforge.email.render.EmailLayout.CARD_FONT_STACK;
import static com.etendoerp.go.schemaforge.email.render.EmailLayout.CENTERED_ROW_OPEN;
import static com.etendoerp.go.schemaforge.email.render.EmailLayout.PRESENTATION_TABLE;
import static com.etendoerp.go.schemaforge.email.render.EmailLayout.ROW_CLOSE;
import static com.etendoerp.go.schemaforge.email.render.EmailLayout.TABLE_CLOSE;
import static com.etendoerp.go.schemaforge.email.render.EmailLayout.WHITE;
import static com.etendoerp.go.schemaforge.email.render.EmailLayout.escape;

import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

/**
 * The centred card design of the customer portal decision confirmations (ETP-6209), drawn for
 * {@link EmailLayout#render} when the content has a title. Not a second entry point: only
 * {@link EmailLayout} calls it.
 *
 * <p>Same rules as the standard letter: tables and inline styles only (no flex, no positioning),
 * a VML twin for the button so Outlook keeps its background. Light only: the design has no dark
 * palette yet, so the document declares {@code color-scheme: light} instead of letting a client
 * half-apply the standard letter's dark overrides.</p>
 *
 * <p>Order: issuer avatar + badge, title + subtitle, summary box (the details as columns), note +
 * button, link fallback, warning, footer. Each block is optional except the title.</p>
 */
final class EmailCardLayout {

  private static final int CARD_WIDTH = 620;
  /** 620 wide minus 2 × 20 padding minus 2 × 1 border: the button's VML width. */
  private static final int BUTTON_WIDTH = 578;
  private static final String BORDER = "#E3E7EC";
  private static final String BORDER_1PX = "1px solid " + BORDER;
  private static final String BADGE_BACKGROUND = "#FFF2EE";
  private static final String BADGE_TEXT = "#B82E00";
  private static final String TITLE_TEXT = "#000000";
  private static final String BODY_TEXT = "#2C3142";
  private static final String STRONG_TEXT = "#1B1A1F";
  private static final String MUTED_TEXT = "#6B7280";
  private static final String NOTE_TEXT = "#555B6D";
  private static final String SUMMARY_BACKGROUND = "#F3F5F9";
  private static final String AVATAR_TEXT = "#555B6D";
  private static final String BUTTON_BACKGROUND = "#325CFF";
  private static final String LINK = "#325CFF";
  private static final String TD_STYLE_OPEN = "<td style=\"";
  private static final String TEXT_CENTER = "text-align:center;";
  private static final String FONT = "font-family:" + CARD_FONT_STACK + ";";
  private static final String P_CLOSE = "</p>";
  private static final String DIV_CLOSE = "</div>";
  private static final String TAG_END = "\">";
  private static final int AVATAR_SIZE = 52;
  /** Diagonal of a fitted logo: inside the 50px inner circle with a little air around it. */
  private static final int LOGO_DIAGONAL = 44;

  private EmailCardLayout() {
  }

  static String render(EmailContent content) {
    StringBuilder html = new StringBuilder(8192);
    openDocument(html);
    boolean first = appendIssuer(html, content, true);
    first = appendTexts(html, content, first);
    first = appendSummary(html, content, first);
    first = appendCta(html, content, first);
    first = appendWarning(html, content, first);
    if (content.getFooter() != null) {
      openSection(html, first);
      EmailLayout.appendFooter(html, content.getFooter());
      html.append(ROW_CLOSE);
    }
    html.append(TABLE_CLOSE).append(ROW_CLOSE).append(TABLE_CLOSE)
        .append(ROW_CLOSE).append(TABLE_CLOSE).append("</body></html>");
    return html.toString();
  }

  private static void openDocument(StringBuilder html) {
    html.append("<!DOCTYPE html>\n")
        .append("<html lang=\"es\"><head>")
        .append("<meta charset=\"utf-8\">")
        .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        .append("<meta name=\"color-scheme\" content=\"light\">")
        .append("<meta name=\"supported-color-schemes\" content=\"light\">")
        .append("<style>@media (max-width:480px){")
        .append(".sf-card{width:100% !important}")
        // The three summary columns stack on a phone instead of squeezing.
        .append(".sf-col{display:block !important;width:auto !important}")
        .append(".sf-col-sep{border-left:0 !important;border-top:").append(BORDER_1PX)
        .append(" !important}")
        .append("}</style></head>")
        .append("<body style=\"margin:0;padding:0;background:").append(WHITE).append(";\">")
        .append(PRESENTATION_TABLE).append(" width=\"100%\" style=\"width:100%;background:")
        .append(WHITE).append(";\">")
        .append(CENTERED_ROW_OPEN).append(" style=\"padding:24px 12px;\">")
        .append(PRESENTATION_TABLE).append(" class=\"sf-card\" width=\"").append(CARD_WIDTH)
        .append("\" style=\"width:").append(CARD_WIDTH).append("px;max-width:100%;background:")
        .append(WHITE).append(";border:").append(BORDER_1PX)
        .append(";border-radius:12px;border-collapse:separate;\">")
        .append("<tr><td style=\"padding:20px;\">")
        .append(PRESENTATION_TABLE).append(" width=\"100%\" style=\"width:100%;\">");
  }

  /** Opens one block of the card; blocks are 12px apart. */
  private static void openSection(StringBuilder html, boolean first) {
    html.append("<tr><td").append(first ? "" : " style=\"padding-top:12px;\"").append('>');
  }

  private static boolean appendIssuer(StringBuilder html, EmailContent content, boolean first) {
    boolean hasAvatar = content.getIssuerLogoUrl() != null
        || StringUtils.isNotBlank(initials(content.getIssuerName()));
    if (!hasAvatar && content.getBadge() == null) {
      return first;
    }
    openSection(html, first);
    html.append(PRESENTATION_TABLE).append(" align=\"center\">");
    if (hasAvatar) {
      html.append(CENTERED_ROW_OPEN).append('>');
      appendAvatar(html, content);
      html.append(ROW_CLOSE);
    }
    if (content.getBadge() != null) {
      html.append(CENTERED_ROW_OPEN).append(hasAvatar ? " style=\"padding-top:8px;\"" : "")
          .append('>').append(PRESENTATION_TABLE).append(" align=\"center\"><tr><td bgcolor=\"")
          .append(BADGE_BACKGROUND).append("\" style=\"").append(FONT).append("padding:4px 8px;")
          .append("background:").append(BADGE_BACKGROUND).append(";border-radius:360px;")
          .append("font-size:12px;line-height:16px;font-weight:400;color:").append(BADGE_TEXT)
          .append(';').append(TAG_END).append(escape(content.getBadge())).append(ROW_CLOSE)
          .append(TABLE_CLOSE).append(ROW_CLOSE);
    }
    html.append(TABLE_CLOSE).append(ROW_CLOSE);
    return false;
  }

  /**
   * The issuer's logo when it has a public one; otherwise its initials in a grey circle, built
   * from a table cell so it needs no image at all.
   */
  private static void appendAvatar(StringBuilder html, EmailContent content) {
    String size = AVATAR_SIZE + "px";
    if (content.getIssuerLogoUrl() != null) {
      int[] fitted = fitInAvatar(content.getIssuerLogoWidth(), content.getIssuerLogoHeight());
      html.append(PRESENTATION_TABLE).append(" align=\"center\"><tr><td width=\"").append(AVATAR_SIZE)
          .append("\" height=\"").append(AVATAR_SIZE).append("\" align=\"center\" valign=\"middle\" ")
          .append("bgcolor=\"").append(WHITE).append("\" style=\"width:").append(size)
          .append(";height:").append(size).append(";background:").append(WHITE)
          .append(";border:").append(BORDER_1PX)
          .append(";border-radius:50%;font-size:0;line-height:0;\"><img src=\"")
          .append(escape(content.getIssuerLogoUrl())).append("\" width=\"").append(fitted[0])
          .append("\" height=\"").append(fitted[1]).append("\" alt=\"")
          .append(escape(StringUtils.defaultString(content.getIssuerName())))
          .append("\" style=\"display:block;margin:0 auto;width:").append(fitted[0])
          .append("px;height:").append(fitted[1]).append("px;border:0;\">").append(ROW_CLOSE)
          .append(TABLE_CLOSE);
      return;
    }
    html.append(PRESENTATION_TABLE).append(" align=\"center\"><tr><td width=\"").append(AVATAR_SIZE)
        .append("\" height=\"").append(AVATAR_SIZE).append("\" align=\"center\" valign=\"middle\" ")
        .append("bgcolor=\"").append(SUMMARY_BACKGROUND).append("\" style=\"").append(FONT)
        .append("width:").append(size).append(";height:").append(size).append(";background:")
        .append(SUMMARY_BACKGROUND).append(";border:").append(BORDER_1PX)
        .append(";border-radius:50%;font-size:18px;line-height:").append(size)
        .append(";font-weight:600;color:").append(AVATAR_TEXT).append(";").append(TEXT_CENTER)
        .append(TAG_END).append(escape(initials(content.getIssuerName()))).append(ROW_CLOSE)
        .append(TABLE_CLOSE);
  }

  /**
   * Fits a logo inside the round avatar without stretching it: scaled, aspect kept, so that its
   * diagonal is {@value #LOGO_DIAGONAL}px — a rectangle of that diagonal always fits inside the
   * 52px circle, whatever its proportions. Email clients ignore {@code object-fit}, so the fit is
   * done here with a fixed width and height. An unknown size is treated as square.
   *
   * @param width the natural width, {@code 0} when unknown
   * @param height the natural height, {@code 0} when unknown
   * @return {@code [width, height]} to render, each at least 1
   */
  static int[] fitInAvatar(int width, int height) {
    double w = width > 0 && height > 0 ? width : 1;
    double h = width > 0 && height > 0 ? height : 1;
    double scale = LOGO_DIAGONAL / Math.hypot(w, h);
    return new int[] { (int) Math.max(1, Math.round(w * scale)),
        (int) Math.max(1, Math.round(h * scale)) };
  }

  /**
   * Up to two initials: the first letter or digit of the first two words of the name.
   *
   * @param name the company name, may be {@code null}
   * @return the initials, upper case, or an empty string
   */
  static String initials(String name) {
    StringBuilder initials = new StringBuilder(2);
    for (String word : StringUtils.split(StringUtils.defaultString(name))) {
      int codePoint = word.codePointAt(0);
      if (Character.isLetterOrDigit(codePoint)) {
        initials.appendCodePoint(codePoint);
      }
      if (initials.codePointCount(0, initials.length()) == 2) {
        break;
      }
    }
    return initials.toString().toUpperCase(Locale.ROOT);
  }

  private static boolean appendTexts(StringBuilder html, EmailContent content, boolean first) {
    openSection(html, first);
    html.append("<h1 style=\"margin:0;").append(FONT).append("font-size:24px;line-height:36px;")
        .append("font-weight:600;color:").append(TITLE_TEXT).append(';').append(TEXT_CENTER)
        .append(TAG_END).append(escape(content.getTitle())).append("</h1>");
    if (content.getSubtitle() != null) {
      html.append("<p style=\"margin:2px 0 0 0;").append(FONT)
          .append("font-size:12px;line-height:20px;font-weight:400;color:").append(BODY_TEXT)
          .append(';').append(TEXT_CENTER).append(TAG_END).append(escape(content.getSubtitle()))
          .append(P_CLOSE);
    }
    html.append(ROW_CLOSE);
    return false;
  }

  /** The summary box: a caption over a white table whose equal columns are the details. */
  private static boolean appendSummary(StringBuilder html, EmailContent content, boolean first) {
    List<EmailContent.Detail> details = content.getDetails();
    if (details.isEmpty()) {
      return first;
    }
    openSection(html, first);
    html.append(PRESENTATION_TABLE).append(" width=\"100%\" bgcolor=\"").append(SUMMARY_BACKGROUND)
        .append("\" style=\"width:100%;background:").append(SUMMARY_BACKGROUND)
        .append(";border-radius:8px;border-collapse:separate;\"><tr><td style=\"padding:4px;\">")
        .append(PRESENTATION_TABLE).append(" width=\"100%\" style=\"width:100%;\">");
    if (content.getSummaryTitle() != null) {
      html.append("<tr>").append(TD_STYLE_OPEN).append(FONT)
          .append("padding:0 8px;font-size:12px;line-height:24px;font-weight:500;color:")
          .append(STRONG_TEXT).append(';').append(TAG_END)
          .append(escape(content.getSummaryTitle())).append(ROW_CLOSE);
    }
    html.append("<tr><td>").append(PRESENTATION_TABLE).append(" width=\"100%\" bgcolor=\"")
        .append(WHITE).append("\" style=\"width:100%;background:").append(WHITE)
        .append(";border:").append(BORDER_1PX)
        .append(";border-radius:8px;border-collapse:separate;table-layout:fixed;\"><tr>");
    int width = 100 / details.size();
    for (int i = 0; i < details.size(); i++) {
      appendSummaryCell(html, details.get(i), width, i > 0);
    }
    html.append("</tr>").append(TABLE_CLOSE).append(ROW_CLOSE)
        .append(TABLE_CLOSE).append(ROW_CLOSE).append(TABLE_CLOSE).append(ROW_CLOSE);
    return false;
  }

  private static void appendSummaryCell(StringBuilder html, EmailContent.Detail detail, int width,
      boolean separated) {
    html.append("<td class=\"sf-col").append(separated ? " sf-col-sep" : "")
        .append("\" width=\"").append(width).append("%\" valign=\"top\" style=\"width:")
        .append(width).append("%;padding:16px 24px;")
        .append(separated ? "border-left:" + BORDER_1PX + ";" : "").append(TAG_END)
        .append("<div style=\"").append(FONT)
        .append("font-size:12px;line-height:16px;font-weight:400;color:").append(MUTED_TEXT)
        .append(';').append(TAG_END).append(escape(detail.getLabel())).append(DIV_CLOSE)
        .append("<div style=\"").append(FONT)
        .append("padding-top:4px;font-size:14px;line-height:20px;font-weight:600;color:")
        .append(STRONG_TEXT).append(";word-break:break-word;\">")
        .append(escape(detail.getValue())).append(DIV_CLOSE).append("</td>");
  }

  /** The note, the full-width button and its "the button does not work" fallback. */
  private static boolean appendCta(StringBuilder html, EmailContent content, boolean first) {
    if (!content.hasCta()) {
      return first;
    }
    String url = escape(content.getCtaUrl());
    String label = escape(content.getCtaLabel());
    openSection(html, first);
    if (content.getCtaNote() != null) {
      html.append("<p style=\"margin:0 0 8px 0;").append(FONT)
          .append("font-size:11px;line-height:16px;font-weight:500;color:").append(NOTE_TEXT)
          .append(';').append(TEXT_CENTER).append(TAG_END).append(escape(content.getCtaNote()))
          .append(P_CLOSE);
    }
    html.append(PRESENTATION_TABLE).append(" width=\"100%\" style=\"width:100%;\">")
        .append(CENTERED_ROW_OPEN).append(" bgcolor=\"").append(BUTTON_BACKGROUND)
        .append("\" style=\"background:").append(BUTTON_BACKGROUND).append(";border-radius:8px;\">")
        .append("<!--[if mso]><v:roundrect xmlns:v=\"urn:schemas-microsoft-com:vml\" href=\"")
        .append(url).append("\" style=\"height:40px;v-text-anchor:middle;width:")
        .append(BUTTON_WIDTH).append("px;\" arcsize=\"20%\" fillcolor=\"")
        .append(BUTTON_BACKGROUND).append("\" stroke=\"f\"><w:anchorlock/><center style=\"color:")
        .append(WHITE).append(";font-family:Arial,sans-serif;font-size:14px;font-weight:500;\">")
        .append(label).append("</center></v:roundrect><![endif]-->")
        .append("<!--[if !mso]><!--><a href=\"").append(url)
        .append("\" style=\"display:block;padding:8px 12px;").append(FONT)
        .append("font-size:14px;line-height:24px;font-weight:500;color:").append(WHITE)
        .append(";text-decoration:none;").append(TEXT_CENTER).append("border-radius:8px;\">")
        .append(label).append("</a><!--<![endif]-->").append(ROW_CLOSE).append(TABLE_CLOSE)
        .append(ROW_CLOSE);
    if (content.getLinkFallbackText() != null) {
      // Centred, as the design's CTA block (ETP-6209 review); the warning below stays left.
      html.append("<tr><td align=\"center\" style=\"padding:12px 0 12px 0;\">")
          .append("<p style=\"margin:0 0 4px 0;").append(FONT)
          .append("font-size:11px;line-height:16px;font-weight:400;color:").append(MUTED_TEXT)
          .append(';').append(TEXT_CENTER).append(TAG_END)
          .append(escape(content.getLinkFallbackText()))
          .append(P_CLOSE).append("<p style=\"margin:0;").append(FONT)
          .append("font-size:11px;line-height:16px;font-weight:400;").append(TEXT_CENTER)
          .append("\"><a href=\"").append(url)
          .append("\" style=\"color:").append(LINK)
          .append(";text-decoration:none;word-break:break-all;\">").append(url).append("</a>")
          .append(P_CLOSE).append(ROW_CLOSE);
    }
    return false;
  }

  /** "Did you not ask for this?" between two hairlines. */
  private static boolean appendWarning(StringBuilder html, EmailContent content, boolean first) {
    if (content.getWarningTitle() == null && content.getWarningText() == null) {
      return first;
    }
    openSection(html, first);
    html.append(PRESENTATION_TABLE).append(" width=\"100%\" style=\"width:100%;\"><tr>")
        .append(TD_STYLE_OPEN).append("padding:16px 0;border-top:").append(BORDER_1PX)
        .append(";border-bottom:").append(BORDER_1PX).append(';').append(TAG_END);
    if (content.getWarningTitle() != null) {
      html.append("<p style=\"margin:0 0 4px 0;").append(FONT)
          .append("font-size:14px;line-height:16px;font-weight:600;color:").append(TITLE_TEXT)
          .append(';').append(TAG_END).append(escape(content.getWarningTitle())).append(P_CLOSE);
    }
    if (content.getWarningText() != null) {
      html.append("<p style=\"margin:0;").append(FONT)
          .append("font-size:12px;line-height:20px;font-weight:400;color:").append(BODY_TEXT)
          .append(';').append(TAG_END).append(escape(content.getWarningText())).append(P_CLOSE);
    }
    html.append(ROW_CLOSE).append(TABLE_CLOSE).append(ROW_CLOSE);
    return false;
  }
}
