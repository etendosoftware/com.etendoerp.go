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

/**
 * Colour tokens for the shared email layout (ETP-5003).
 *
 * <p>Two palettes are emitted on every email: the light one inline on the elements, and the dark
 * one as a {@code prefers-color-scheme} override in the document head. A client that supports the
 * media query picks the dark values; every other client keeps the inline light values.</p>
 *
 * <p><b>The dark values are provisional</b> and pending design sign-off. They are derived from the
 * light palette rather than authored: backgrounds inverted, text lifted, and the call-to-action
 * flipped to a light button with a dark label, because the light palette's near-black button would
 * vanish against a dark card.</p>
 */
public final class EmailPalette {

  /** Page background behind the card. */
  public static final String LIGHT_PAGE_BACKGROUND = "#F5F7F9";
  /** Card background. */
  public static final String LIGHT_CARD_BACKGROUND = "#FFFFFF";
  /** Hairline under the logo. */
  public static final String LIGHT_DIVIDER = "#E8EAEF";
  /** Body copy and fine print. */
  public static final String LIGHT_TEXT = "#555B6D";
  /** Emphasised fragments inside body copy. */
  public static final String LIGHT_TEXT_STRONG = "#121217";
  /** Call-to-action background. */
  public static final String LIGHT_CTA_BACKGROUND = "#121217";
  /** Call-to-action label. */
  public static final String LIGHT_CTA_LABEL = "#FFFFFF";
  /** Hyperlinks in body copy and the link fallback block. */
  public static final String LIGHT_LINK = "#0A7AFF";

  /** Provisional — pending design sign-off. */
  public static final String DARK_PAGE_BACKGROUND = "#0F1115";
  /** Provisional — pending design sign-off. */
  public static final String DARK_CARD_BACKGROUND = "#1A1D23";
  /** Provisional — pending design sign-off. */
  public static final String DARK_DIVIDER = "#2C313A";
  /** Provisional — pending design sign-off. */
  public static final String DARK_TEXT = "#B9BFCC";
  /** Provisional — pending design sign-off. */
  public static final String DARK_TEXT_STRONG = "#F5F7F9";
  /** Provisional — pending design sign-off. */
  public static final String DARK_CTA_BACKGROUND = "#F5F7F9";
  /** Provisional — pending design sign-off. */
  public static final String DARK_CTA_LABEL = "#121217";
  /** Provisional — pending design sign-off. */
  public static final String DARK_LINK = "#6BB2FF";

  private EmailPalette() {
  }
}
