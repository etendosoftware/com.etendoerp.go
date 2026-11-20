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
 * The "Creado con Etendo" marketing footer of the emails that reach our customers' customers
 * (ETP-6209): the customer portal emails and the document emails that carry a portal link.
 *
 * <p>An email carries it only when its contract asks for one with
 * {@link EmailContent.Builder#footer(EmailFooter)}; every other email renders exactly as before.
 * The markup lives in {@link EmailLayout}, like every other block.</p>
 *
 * <h2>Links</h2>
 * <p>Every link is built by {@link #link(String, Piece)}, the one place a per-piece tracking query
 * (UTM) is appended. Marketing will send one per {@link Piece}; adding it means filling
 * {@link Piece}'s query, never touching a contract.</p>
 */
public final class EmailFooter {

  /** PROVISIONAL — marketing will send the final pricing page URL. */
  static final String PRICING_URL = "https://etendo.ai";
  /**
   * Cookie and privacy policy URL. Empty on purpose: the footer omits the privacy link until
   * marketing sends the real URL — a privacy link to the home page would mislead. Set the real URL
   * from marketing here to show it; nothing else changes (copy {@code footer.privacy} is kept).
   */
  static final String PRIVACY_URL = "";
  /** The public site shown as text in the footer. */
  static final String SITE_URL = "https://www.etendo.ai";

  private static final String KEY_PREFIX = "footer.";

  /** The email a footer goes out in, so each can carry its own tracking query. */
  public enum Piece {
    /** A sales document email carrying a customer portal link. */
    DOCUMENT_SEND(""),
    /** The renewed customer portal link. */
    LINK_RENEWAL(""),
    /** The confirmation of a quotation acceptance requested in the portal. */
    DECISION_ACCEPT(""),
    /** The confirmation of a quotation rejection requested in the portal. */
    DECISION_REJECT("");

    /**
     * Query string appended to every footer link of this piece, without the leading {@code ?}.
     * Empty until marketing sends the per-piece UTM (e.g. {@code utm_source=email&utm_campaign=…}).
     */
    private final String trackingQuery;

    Piece(String trackingQuery) {
      this.trackingQuery = trackingQuery;
    }
  }

  private final String title;
  private final String ctaLabel;
  private final String ctaUrl;
  private final String siteLabel;
  private final String siteUrl;
  private final String privacyLabel;
  private final String privacyUrl;

  private EmailFooter(Piece piece, String language) {
    this.title = EmailMessages.get(KEY_PREFIX + "title", language);
    this.ctaLabel = EmailMessages.get(KEY_PREFIX + "cta", language);
    this.ctaUrl = link(PRICING_URL, piece);
    this.siteLabel = EmailMessages.get(KEY_PREFIX + "site", language);
    this.siteUrl = link(SITE_URL, piece);
    this.privacyLabel = EmailMessages.get(KEY_PREFIX + "privacy", language);
    this.privacyUrl = StringUtils.isBlank(PRIVACY_URL) ? null : link(PRIVACY_URL, piece);
  }

  /**
   * The Etendo footer of one email, in the recipient's language.
   *
   * @param piece the email it goes out in
   * @param language the recipient language
   * @return the footer
   */
  public static EmailFooter etendo(Piece piece, String language) {
    return new EmailFooter(piece, language);
  }

  /**
   * Appends the piece's tracking query to a footer link.
   *
   * @param base the link without tracking
   * @param piece the email the link goes out in
   * @return the link to emit
   */
  static String link(String base, Piece piece) {
    return link(base, piece == null ? null : piece.trackingQuery);
  }

  /**
   * Appends a tracking query to a link: after {@code ?}, or after {@code &} when the link already
   * has a query. A blank query leaves the link as it is.
   *
   * @param base the link without tracking
   * @param trackingQuery the query, without the leading {@code ?}; may be blank
   * @return the link to emit
   */
  static String link(String base, String trackingQuery) {
    if (StringUtils.isBlank(trackingQuery)) {
      return base;
    }
    return base + (base.contains("?") ? "&" : "?") + trackingQuery;
  }

  public String getTitle() {
    return title;
  }

  public String getCtaLabel() {
    return ctaLabel;
  }

  public String getCtaUrl() {
    return ctaUrl;
  }

  public String getSiteLabel() {
    return siteLabel;
  }

  public String getSiteUrl() {
    return siteUrl;
  }

  public String getPrivacyLabel() {
    return privacyLabel;
  }

  /**
   * The cookie and privacy policy link.
   *
   * @return the URL, or {@code null} while {@link #PRIVACY_URL} is not set (the row is omitted)
   */
  public String getPrivacyUrl() {
    return privacyUrl;
  }
}
