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

import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * HTML escaping shared by everything that builds email markup (ETP-5003).
 *
 * <p>Extracted from {@code EmailMessageEdits}, which held the only copy of these rules while it was
 * the only class emitting markup. {@link EmailLayout} needs exactly the same escaping, and two
 * copies of an escaping routine is how one of them ends up subtly weaker than the other.</p>
 */
public final class EmailEscape {

  /**
   * The one emphasis marker the emails understand: {@code **bold**}.
   *
   * <p>Non-greedy and newline-free on purpose — an unclosed {@code **} must not swallow the rest of
   * the message, and emphasis never spans a line break.</p>
   */
  private static final Pattern BOLD = Pattern.compile("\\*\\*([^*\\r\\n]+?)\\*\\*");

  private EmailEscape() {
  }

  /**
   * Escapes text for interpolation into HTML markup.
   *
   * @param value the raw text, may be {@code null}
   * @return the escaped text, never {@code null}
   */
  public static String escapeHtml(String value) {
    return StringUtils.defaultString(value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  /**
   * Turns {@code **bold**} markers into {@code <strong>} tags.
   *
   * <p>ETP-5003: emphasis used to be applied in Java, by wrapping an interpolated value in
   * {@code <strong>} before it reached the copy. That worked only for a message the module composed
   * itself — the moment an operator edited the text, the email lost every bold run, and there was no
   * way for them to put one back. Expressing emphasis in the copy itself means the operator reads
   * exactly the markers that will be rendered, and the same catalog string works on both sides.</p>
   *
   * <p><b>Must run after {@link #escapeHtml}</b>, never before. Asterisks survive escaping
   * untouched, so escaping first means a message containing {@code <script>} is still inert while
   * its {@code **} still becomes emphasis. Reversing the order would emit caller-controlled
   * markup.</p>
   *
   * @param escapedHtml text that has already been HTML-escaped, may be {@code null}
   * @return the text with emphasis markers replaced, never {@code null}
   */
  public static String applyBold(String escapedHtml) {
    return BOLD.matcher(StringUtils.defaultString(escapedHtml))
        .replaceAll("<strong>$1</strong>");
  }

}
