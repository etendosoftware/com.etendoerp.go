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

package com.etendoerp.go.usage;

import org.apache.commons.lang3.StringUtils;

/**
 * Makes text safe to put in a message a user will see.
 *
 * <p>Openbravo treats {@code @} as its message-parameter delimiter: {@code translateError}
 * parses {@code @CODE=} and searches for {@code @...@} placeholders, so a message carrying a
 * stray at-sign can reach the user <b>blank</b>. That has already cost this feature twice --
 * once in the undeployed-qualifier message, which named {@code @Named} and {@code
 * @ApplicationScoped}, and it remains latent anywhere a message interpolates text we do not
 * control.
 *
 * <p>The fixed wording of a message is ours to keep clean by hand. What this is for is the
 * <b>dynamic</b> halves: a catalog search key typed by an administrator, an HQL fragment, an
 * exception message from Hibernate or CDI. Any of those may legitimately contain an at-sign,
 * so the choice is between a slightly altered rendering and a message that vanishes entirely.
 * A reader can work out what {@code (at)Named} meant; nobody can act on an empty popup.
 */
public final class UsageMessages {

  private static final String AT = "@";
  private static final String AT_REPLACEMENT = "(at)";

  private UsageMessages() {
  }

  /**
   * Replaces at-signs so the text survives Openbravo's message rendering.
   *
   * <p>Replaced rather than stripped: dropping the character turns {@code a@b.com} into
   * {@code ab.com}, which reads as a real value and is wrong, while {@code a(at)b.com} is
   * visibly a substitution.
   *
   * @param text any value interpolated into a user-facing message; null and empty pass through
   * @return the text with every at-sign replaced
   */
  public static String atSafe(String text) {
    return StringUtils.isEmpty(text) ? text : StringUtils.replace(text, AT, AT_REPLACEMENT);
  }
}
