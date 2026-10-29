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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

/**
 * The vocabulary and the validation of the document template configuration: which document types
 * can be configured, which variants exist and what a logo or a footer may contain. Pure functions,
 * no database, so the endpoint's rules are testable on their own.
 *
 * <p>Every {@code *Error} method returns the message to answer a {@code 400} with, or {@code null}
 * when the value is acceptable. Nothing is escaped here: the footer is stored as the user typed it
 * and the renderer (T6) is the one that escapes it for its output.</p>
 */
final class DocTemplateConfigSupport {

  static final String DEFAULT_VARIANT = "classic";

  /**
   * The spec names of the existing printables ({@code print-<name>}), kebab-case, plus
   * {@code listing}: the print of a window's list (PDF, Excel, preview), whose design is chosen
   * apart from the documents'.
   */
  static final Set<String> DOC_TYPES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
      "sales-invoice", "sales-order", "sales-quotation", "purchase-order", "goods-shipment",
      "payment-in", "return-material-receipt", "return-to-vendor-shipment", "listing")));

  static final Set<String> VARIANTS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
      DEFAULT_VARIANT, "compact", "modern")));

  static final int MAX_FOOTER_LENGTH = 500;
  static final int MAX_LOGO_URL_LENGTH = 2000;
  /** Cap on a {@code data:} logo, counted on the whole string. */
  static final int MAX_LOGO_DATA_LENGTH = 512 * 1024;

  private static final String HTTPS_PREFIX = "https://";
  private static final String DATA_PREFIX = "data:image/";
  private static final String BASE64_MARKER = ";base64,";
  private static final Set<String> DATA_IMAGE_TYPES = new LinkedHashSet<>(
      Arrays.asList("png", "jpeg", "webp"));

  private DocTemplateConfigSupport() {
  }

  static String docTypeError(String docType) {
    if (StringUtils.isBlank(docType)) {
      return "docType is required";
    }
    return DOC_TYPES.contains(docType) ? null
        : "Unknown docType '" + abbreviate(docType) + "'. Allowed: " + String.join(", ", DOC_TYPES);
  }

  static String variantError(String variant) {
    return variant != null && VARIANTS.contains(variant) ? null
        : "Unknown variant '" + abbreviate(variant) + "'. Allowed: " + String.join(", ", VARIANTS);
  }

  /** The footer is optional; a blank one means "none" and is normalized by {@link #normalize}. */
  static String footerError(String footer) {
    if (footer == null) {
      return null;
    }
    if (footer.length() > MAX_FOOTER_LENGTH) {
      return "footerText is longer than " + MAX_FOOTER_LENGTH + " characters";
    }
    for (int i = 0; i < footer.length(); i++) {
      char c = footer.charAt(i);
      if (Character.isISOControl(c) && c != '\n' && c != '\r') {
        return "footerText contains a control character";
      }
    }
    return null;
  }

  /**
   * The logo is optional. It is either an absolute {@code https://} URL or a {@code data:} URI of
   * a png, jpeg or webp image in base64. SVG is refused on purpose, because the renderer embeds the
   * logo in a page that can run script, and so are {@code http:} (mixed content, SSRF) and any other
   * scheme.
   */
  static String logoError(String logo) {
    if (logo == null) {
      return null;
    }
    if (logo.startsWith(HTTPS_PREFIX)) {
      return httpsLogoError(logo);
    }
    if (logo.startsWith(DATA_PREFIX)) {
      return dataLogoError(logo);
    }
    return "logo must be an https:// URL or a png, jpeg or webp data: URI";
  }

  private static String httpsLogoError(String logo) {
    if (logo.length() > MAX_LOGO_URL_LENGTH) {
      return "logo URL is longer than " + MAX_LOGO_URL_LENGTH + " characters";
    }
    if (logo.length() == HTTPS_PREFIX.length() || StringUtils.containsAny(logo, " \t\r\n\"'<>")) {
      return "logo URL is not a valid https:// URL";
    }
    return null;
  }

  private static String dataLogoError(String logo) {
    if (logo.length() > MAX_LOGO_DATA_LENGTH) {
      return "logo image is larger than " + (MAX_LOGO_DATA_LENGTH / 1024) + " KiB";
    }
    int marker = logo.indexOf(BASE64_MARKER);
    String type = marker < 0 ? "" : logo.substring(DATA_PREFIX.length(), marker);
    if (!DATA_IMAGE_TYPES.contains(type)) {
      return "logo data: URI must be a base64 png, jpeg or webp image";
    }
    int start = marker + BASE64_MARKER.length();
    if (start == logo.length() || !isBase64(logo, start)) {
      return "logo data: URI is not valid base64";
    }
    return null;
  }

  private static boolean isBase64(String text, int from) {
    int padding = 0;
    for (int i = from; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '=') {
        padding++;
      } else if (padding > 0 || !(Character.isLetterOrDigit(c) && c < 128 || c == '+' || c == '/')) {
        return false;
      }
    }
    return padding <= 2;
  }

  /** Blank text is "not set": stored as null, so the column never holds an empty string. */
  static String normalize(String value) {
    return StringUtils.isBlank(value) ? null : value.trim();
  }

  private static String abbreviate(String value) {
    return StringUtils.abbreviate(StringUtils.defaultString(value), 40);
  }
}
