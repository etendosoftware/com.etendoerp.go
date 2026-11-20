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

package com.etendoerp.go.schemaforge;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import javax.servlet.http.HttpServletResponse;

/**
 * The one place that decides the headers of a file download (ETP-4571, SEC-09b / SEC-12): an
 * inert content type derived from the file name (never the stored type, which a caller
 * influences), {@code nosniff}, {@code Content-Disposition: attachment}, a sandboxing CSP and
 * {@code Cache-Control: no-store}. A download is therefore never rendered as active content.
 */
final class NeoDownloadHeaders {

  static final String DEFAULT_NAME = "download";

  private NeoDownloadHeaders() {
  }

  /**
   * Sets the content type and the hardening headers of a download response.
   *
   * <p>The stored data type is deliberately not a parameter: it is caller-influenced and never
   * decides what a download is served as.</p>
   *
   * @param response the response being written
   * @param fileName the stored file name
   */
  static void apply(HttpServletResponse response, String fileName) {
    response.setContentType(NeoAttachmentPolicy.inertMimeType(fileName));
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
    response.setHeader("Content-Disposition", contentDisposition(fileName));
  }

  /**
   * Marks a private JSON response as non-cacheable and non-sniffable (SEC-12). Used by every
   * writer that puts authenticated JSON on the wire.
   *
   * @param response the response being written
   */
  static void markPrivateJson(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
  }

  /**
   * An RFC 5987 {@code attachment} disposition; the name is stripped of path components and
   * control characters and cannot break out of the quoted value.
   *
   * @param fileName the stored file name
   * @return the header value
   */
  static String contentDisposition(String fileName) {
    String safe = fileName == null ? DEFAULT_NAME
        : NeoAttachmentPolicy.sanitizeFileName(fileName).replaceAll("[\\p{Cc}\"]", "_");
    String encoded = URLEncoder.encode(safe, StandardCharsets.UTF_8).replace("+", "%20");
    return "attachment; filename=\"" + safe + "\"; filename*=UTF-8''" + encoded;
  }
}
