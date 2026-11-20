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
package com.etendoerp.go.portal;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.model.ad.utility.Image;

import com.etendoerp.go.common.CompanyLogoResolver;
import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService;
import com.etendoerp.go.schemaforge.util.NeoImageHelper;

/**
 * The token-free, public URL of a tenant's logo, for the portal emails' header (ETP-6209).
 *
 * <p>{@code GET /sws/portal/logo} needs the customer's portal token, which must never travel in an
 * image URL (image proxies such as Gmail's fetch and cache it). This reference grants exactly one
 * thing instead: the bytes of one tenant's document logo, the same image {@code /logo} serves.</p>
 *
 * <h2>The reference</h2>
 * <p>{@code base64url(clientId + "|" + orgId) + "." + HMAC}, the MAC made by
 * {@link DocumentDownloadTokenService#signDetached} under the {@value #PURPOSE} label with the
 * email signing secret the document emails already use, so no new secret exists. It never expires
 * (an email is opened long after it was sent) and it is stable per tenant, so image proxies cache
 * it. It cannot be forged without the secret, it names no document, no customer and no session, and
 * it can never validate as a download token (domain-separated MAC). It carries the tenant's internal
 * ids, which grant nothing on their own.</p>
 *
 * <p>Only PNG and JPEG are ever served, typed from the bytes themselves: a stored SVG or anything
 * else answers 404, so this public URL can never serve active content.</p>
 */
public final class PortalLogoReference {

  /** Signature label: a logo reference signature is valid for nothing else. */
  static final String PURPOSE = "portal-logo:v1";
  /** Servlet path of the endpoint, under {@code /sws/portal}. */
  static final String PATH = "/public-logo/"; // NOSONAR java:S1075 - route path, not a URL
  private static final String ROUTE = "/sws/portal" + PATH;
  private static final String SEPARATOR = "|";
  private static final Logger log = LogManager.getLogger(PortalLogoReference.class);

  private PortalLogoReference() {
  }

  /**
   * The public URL of the tenant's logo and its natural size, when the tenant has a servable logo
   * and both the app base URL and the email signing secret are configured.
   *
   * @param clientId the tenant
   * @param orgId the organization whose logo the document printed with, or {@code null}
   * @return the logo, or empty to fall back to the initials avatar
   */
  public static Optional<PublicLogo> find(String clientId, String orgId) {
    if (StringUtils.isBlank(clientId)) {
      return Optional.empty();
    }
    String baseUrl = PublicUrlResolver.resolveConfiguredAppBaseUrl();
    Optional<String> reference = reference(clientId, orgId);
    if (StringUtils.isBlank(baseUrl) || !reference.isPresent()) {
      return Optional.empty();
    }
    Image logo = CompanyLogoResolver.resolve(clientId, StringUtils.trimToNull(orgId));
    byte[] bytes = logo == null ? null : logo.getBindaryData();
    if (servableMimeType(bytes) == null) {
      return Optional.empty();
    }
    int[] size = naturalSize(logo, bytes);
    return Optional.of(new PublicLogo(
        PublicUrlResolver.appendPath(baseUrl, ROUTE + reference.get()), size[0], size[1]));
  }

  /**
   * Builds the signed reference of one tenant's logo.
   *
   * @param clientId the tenant
   * @param orgId the organization, or {@code null}
   * @return the reference, or empty when the signing secret is not configured
   */
  static Optional<String> reference(String clientId, String orgId) {
    String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
        (clientId + SEPARATOR + StringUtils.defaultString(orgId))
            .getBytes(StandardCharsets.UTF_8));
    return DocumentDownloadTokenService.signDetached(PURPOSE, payload)
        .map(signature -> payload + "." + signature);
  }

  /**
   * Reads a reference back, checking its signature.
   *
   * @param reference the reference from the URL
   * @return {@code [clientId, orgId-or-null]}, or empty for anything not signed by this server
   */
  static Optional<String[]> resolve(String reference) {
    String normalized = StringUtils.trimToNull(reference);
    int dot = normalized == null ? -1 : normalized.lastIndexOf('.');
    if (dot <= 0 || dot == normalized.length() - 1) {
      return Optional.empty();
    }
    String payload = normalized.substring(0, dot);
    if (!DocumentDownloadTokenService.verifyDetached(PURPOSE, payload,
        normalized.substring(dot + 1))) {
      return Optional.empty();
    }
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
      int separator = decoded.indexOf(SEPARATOR);
      if (separator <= 0) {
        return Optional.empty();
      }
      return Optional.of(new String[] { decoded.substring(0, separator),
          StringUtils.trimToNull(decoded.substring(separator + 1)) });
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /**
   * The type a public logo is served as, read from the bytes: PNG or JPEG, nothing else.
   *
   * @param bytes the logo bytes, may be {@code null}
   * @return the MIME type, or {@code null} when the bytes must not be served
   */
  static String servableMimeType(byte[] bytes) {
    return bytes == null || bytes.length == 0 ? null : NeoImageHelper.sniffMimeType(bytes);
  }

  /** The stored width and height, else read from the image header, else square. */
  private static int[] naturalSize(Image logo, byte[] bytes) {
    if (logo.getWidth() != null && logo.getHeight() != null && logo.getWidth() > 0
        && logo.getHeight() > 0) {
      return new int[] { logo.getWidth().intValue(), logo.getHeight().intValue() };
    }
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
      if (image != null) {
        return new int[] { image.getWidth(), image.getHeight() };
      }
    } catch (IOException e) {
      log.debug("Could not read the logo size: {}", e.getMessage(), e);
    }
    return new int[] { 1, 1 };
  }

  /** A tenant's public logo: where it is and its natural size, for an email to fit it. */
  public static final class PublicLogo {
    private final String url;
    private final int width;
    private final int height;

    PublicLogo(String url, int width, int height) {
      this.url = url;
      this.width = width;
      this.height = height;
    }

    public String getUrl() {
      return url;
    }

    public int getWidth() {
      return width;
    }

    public int getHeight() {
      return height;
    }
  }
}
