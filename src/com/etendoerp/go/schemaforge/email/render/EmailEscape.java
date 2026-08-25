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

import org.apache.commons.lang3.StringUtils;

/**
 * HTML escaping shared by everything that builds email markup (ETP-5003).
 *
 * <p>Extracted from {@code EmailMessageEdits}, which held the only copy of these rules while it was
 * the only class emitting markup. {@link EmailLayout} needs exactly the same escaping, and two
 * copies of an escaping routine is how one of them ends up subtly weaker than the other.</p>
 */
public final class EmailEscape {

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

}
