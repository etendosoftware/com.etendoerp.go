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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

/**
 * The content of one email, expressed as ordered blocks (ETP-5003).
 *
 * <p>Contracts compose blocks and never write markup; {@link EmailLayout} turns the blocks into
 * the shared HTML document. Every string handed in is treated as untrusted text and is escaped by
 * the layout, with the single exception of {@link Paragraph#getHtml()}, which carries copy a
 * contract has already assembled and escaped itself.</p>
 */
public final class EmailContent {

  private final String greeting;
  private final boolean greetingIsHtml;
  private final List<Paragraph> paragraphs;
  private final List<Detail> details;
  private final String ctaLabel;
  private final String ctaUrl;
  private final String linkFallbackText;
  private final List<String> notes;
  private final String signature;
  private final EmailFooter footer;
  private final String issuerName;
  private final String issuerLogoUrl;
  private final int issuerLogoWidth;
  private final int issuerLogoHeight;
  private final String badge;
  private final String title;
  private final String subtitle;
  private final String summaryTitle;
  private final String ctaNote;
  private final String warningTitle;
  private final String warningText;

  private EmailContent(Builder builder) {
    this.greeting = builder.greeting;
    this.greetingIsHtml = builder.greetingIsHtml;
    this.paragraphs = Collections.unmodifiableList(new ArrayList<>(builder.paragraphs));
    this.details = Collections.unmodifiableList(new ArrayList<>(builder.details));
    this.ctaLabel = builder.ctaLabel;
    this.ctaUrl = builder.ctaUrl;
    this.linkFallbackText = builder.linkFallbackText;
    this.notes = Collections.unmodifiableList(new ArrayList<>(builder.notes));
    this.signature = builder.signature;
    this.footer = builder.footer;
    this.issuerName = builder.issuerName;
    this.issuerLogoUrl = builder.issuerLogoUrl;
    this.issuerLogoWidth = builder.issuerLogoWidth;
    this.issuerLogoHeight = builder.issuerLogoHeight;
    this.badge = builder.badge;
    this.title = builder.title;
    this.subtitle = builder.subtitle;
    this.summaryTitle = builder.summaryTitle;
    this.ctaNote = builder.ctaNote;
    this.warningTitle = builder.warningTitle;
    this.warningText = builder.warningText;
  }

  /**
   * Creates an empty builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Greeting line rendered above the body copy.
   *
   * @return the greeting, or {@code null} when the email opens straight into its copy
   */
  public String getGreeting() {
    return greeting;
  }

  /**
   * Indicates whether {@link #getGreeting()} already carries escaped markup.
   *
   * @return {@code true} when the greeting must not be escaped again
   */
  public boolean isGreetingHtml() {
    return greetingIsHtml;
  }

  /**
   * Body paragraphs in render order.
   *
   * @return the paragraphs, never {@code null}
   */
  public List<Paragraph> getParagraphs() {
    return paragraphs;
  }

  /**
   * Summary rows rendered between the body copy and the call to action.
   *
   * @return the details, never {@code null}
   */
  public List<Detail> getDetails() {
    return details;
  }

  /**
   * Label of the call-to-action button.
   *
   * @return the label, or {@code null} when the email has no button
   */
  public String getCtaLabel() {
    return ctaLabel;
  }

  /**
   * Target of the call-to-action button.
   *
   * @return the URL, or {@code null} when the email has no button
   */
  public String getCtaUrl() {
    return ctaUrl;
  }

  /**
   * Intro line of the "if the button does not work" block.
   *
   * @return the fallback text, or {@code null} when the block is omitted
   */
  public String getLinkFallbackText() {
    return linkFallbackText;
  }

  /**
   * Fine-print lines rendered under the call to action.
   *
   * @return the notes, never {@code null}
   */
  public List<String> getNotes() {
    return notes;
  }

  /**
   * Closing signature.
   *
   * @return the signature, or {@code null} when the email closes without one
   */
  public String getSignature() {
    return signature;
  }

  /**
   * The marketing footer (ETP-6209).
   *
   * @return the footer, or {@code null} for an email that carries none — every email but the
   *     customer portal ones
   */
  public EmailFooter getFooter() {
    return footer;
  }

  /**
   * Indicates whether the email uses the centred card design (ETP-6209: the portal decision
   * confirmations) instead of the standard Etendo letter. A title is what selects it.
   *
   * @return {@code true} when a title was set
   */
  public boolean isCard() {
    return title != null;
  }

  /**
   * Name of the company the card email comes from, shown as initials when it has no logo.
   *
   * @return the name, or {@code null}
   */
  public String getIssuerName() {
    return issuerName;
  }

  /**
   * Public URL of the issuing company's logo.
   *
   * @return the URL, or {@code null} to show the initials avatar
   */
  public String getIssuerLogoUrl() {
    return issuerLogoUrl;
  }

  /**
   * Natural width of the issuer logo, so the layout can fit it inside the round avatar.
   *
   * @return the width in pixels, or {@code 0} when unknown (treated as square)
   */
  public int getIssuerLogoWidth() {
    return issuerLogoWidth;
  }

  /**
   * Natural height of the issuer logo.
   *
   * @return the height in pixels, or {@code 0} when unknown (treated as square)
   */
  public int getIssuerLogoHeight() {
    return issuerLogoHeight;
  }

  /**
   * Status pill under the issuer avatar of a card email.
   *
   * @return the badge text, or {@code null}
   */
  public String getBadge() {
    return badge;
  }

  /**
   * Headline of a card email.
   *
   * @return the title, or {@code null} for a standard email
   */
  public String getTitle() {
    return title;
  }

  /**
   * Line under the headline of a card email.
   *
   * @return the subtitle, or {@code null}
   */
  public String getSubtitle() {
    return subtitle;
  }

  /**
   * Caption of a card email's summary box, whose columns are {@link #getDetails()}.
   *
   * @return the caption, or {@code null}
   */
  public String getSummaryTitle() {
    return summaryTitle;
  }

  /**
   * Line right above a card email's button.
   *
   * @return the note, or {@code null}
   */
  public String getCtaNote() {
    return ctaNote;
  }

  /**
   * Heading of a card email's "did you not ask for this" block.
   *
   * @return the heading, or {@code null} when the block is omitted
   */
  public String getWarningTitle() {
    return warningTitle;
  }

  /**
   * Body of a card email's "did you not ask for this" block.
   *
   * @return the text, or {@code null}
   */
  public String getWarningText() {
    return warningText;
  }

  /**
   * Indicates whether the layout should render the call-to-action button and its link fallback.
   *
   * @return {@code true} when both a label and a URL are present
   */
  public boolean hasCta() {
    return StringUtils.isNotBlank(ctaLabel) && StringUtils.isNotBlank(ctaUrl);
  }

  /** One label/value row of the summary block. Both sides are escaped at render time. */
  public static final class Detail {

    private final String label;
    private final String value;

    private Detail(String label, String value) {
      this.label = label;
      this.value = value;
    }

    public String getLabel() {
      return label;
    }

    public String getValue() {
      return value;
    }
  }

  /** A single body paragraph, either plain text or contract-assembled HTML. */
  public static final class Paragraph {

    private final String text;
    private final String html;

    private Paragraph(String text, String html) {
      this.text = text;
      this.html = html;
    }

    /**
     * Creates a paragraph from untrusted text, escaped at render time.
     *
     * @param text the paragraph text
     * @return the paragraph
     */
    public static Paragraph text(String text) {
      return new Paragraph(text, null);
    }

    /**
     * Creates a paragraph from markup the caller has already escaped.
     *
     * <p>Reserved for copy a contract assembles itself, such as an operator-authored message that
     * {@code EmailMessageEdits} has escaped, or a sentence carrying an emphasised fragment.</p>
     *
     * @param html the paragraph markup
     * @return the paragraph
     */
    public static Paragraph preEscapedHtml(String html) {
      return new Paragraph(null, html);
    }

    /**
     * Plain-text form of the paragraph.
     *
     * @return the text, or {@code null} when this paragraph carries markup
     */
    public String getText() {
      return text;
    }

    /**
     * Pre-escaped markup form of the paragraph.
     *
     * @return the markup, or {@code null} when this paragraph carries plain text
     */
    public String getHtml() {
      return html;
    }
  }

  /** Builder for {@link EmailContent}. */
  public static final class Builder {

    private final List<Paragraph> paragraphs = new ArrayList<>();
    private final List<Detail> details = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private String greeting;
    private boolean greetingIsHtml;
    private String ctaLabel;
    private String ctaUrl;
    private String linkFallbackText;
    private String signature;
    private EmailFooter footer;
    private String issuerName;
    private String issuerLogoUrl;
    private int issuerLogoWidth;
    private int issuerLogoHeight;
    private String badge;
    private String title;
    private String subtitle;
    private String summaryTitle;
    private String ctaNote;
    private String warningTitle;
    private String warningText;

    /**
     * Sets the greeting line.
     *
     * @param value the greeting
     * @return this builder
     */
    public Builder greeting(String value) {
      this.greeting = StringUtils.trimToNull(value);
      return this;
    }

    /**
     * Sets a greeting whose markup the caller has already escaped.
     *
     * @param value the greeting markup
     * @return this builder
     */
    public Builder greetingHtml(String value) {
      this.greeting = StringUtils.trimToNull(value);
      this.greetingIsHtml = this.greeting != null;
      return this;
    }

    /**
     * Appends a plain-text paragraph.
     *
     * @param value the paragraph text
     * @return this builder
     */
    public Builder paragraph(String value) {
      String normalized = StringUtils.trimToNull(value);
      if (normalized != null) {
        paragraphs.add(Paragraph.text(normalized));
      }
      return this;
    }

    /**
     * Appends a paragraph whose markup the caller has already escaped.
     *
     * @param value the paragraph markup
     * @return this builder
     */
    public Builder paragraphHtml(String value) {
      String normalized = StringUtils.trimToNull(value);
      if (normalized != null) {
        paragraphs.add(Paragraph.preEscapedHtml(normalized));
      }
      return this;
    }

    /**
     * Appends a summary row.
     *
     * <p>A row with a blank value is dropped rather than rendered empty, so a document that has no
     * due date does not print a "Due date" label with nothing beside it.</p>
     *
     * @param label the row label
     * @param value the row value
     * @return this builder
     */
    public Builder detail(String label, String value) {
      String normalizedLabel = StringUtils.trimToNull(label);
      String normalizedValue = StringUtils.trimToNull(value);
      if (normalizedLabel != null && normalizedValue != null) {
        details.add(new Detail(normalizedLabel, normalizedValue));
      }
      return this;
    }

    /**
     * Sets the call-to-action button.
     *
     * @param label the button label
     * @param url the button target
     * @return this builder
     */
    public Builder cta(String label, String url) {
      this.ctaLabel = StringUtils.trimToNull(label);
      this.ctaUrl = StringUtils.trimToNull(url);
      return this;
    }

    /**
     * Sets the intro line of the link-fallback block.
     *
     * @param value the fallback text
     * @return this builder
     */
    public Builder linkFallbackText(String value) {
      this.linkFallbackText = StringUtils.trimToNull(value);
      return this;
    }

    /**
     * Appends a fine-print line.
     *
     * @param value the note text
     * @return this builder
     */
    public Builder note(String value) {
      String normalized = StringUtils.trimToNull(value);
      if (normalized != null) {
        notes.add(normalized);
      }
      return this;
    }

    /**
     * Sets the closing signature.
     *
     * @param value the signature
     * @return this builder
     */
    public Builder signature(String value) {
      this.signature = StringUtils.trimToNull(value);
      return this;
    }

    /**
     * Adds the marketing footer (ETP-6209). Omitted by default, which keeps every email that does
     * not ask for it byte-identical.
     *
     * @param value the footer, or {@code null} for none
     * @return this builder
     */
    public Builder footer(EmailFooter value) {
      this.footer = value;
      return this;
    }

    /**
     * Sets the issuing company of a card email: its name, and its logo when it has a public one.
     *
     * @param name the company name, shown as initials when there is no logo
     * @param logoUrl a public logo URL, or {@code null}
     * @return this builder
     */
    public Builder issuer(String name, String logoUrl) {
      this.issuerName = StringUtils.trimToNull(name);
      this.issuerLogoUrl = StringUtils.trimToNull(logoUrl);
      return this;
    }

    /**
     * Sets the natural size of the issuer logo, so it is fitted inside the round avatar without
     * being stretched (email clients ignore {@code object-fit}).
     *
     * @param width the logo width in pixels
     * @param height the logo height in pixels
     * @return this builder
     */
    public Builder issuerLogoSize(int width, int height) {
      this.issuerLogoWidth = Math.max(0, width);
      this.issuerLogoHeight = Math.max(0, height);
      return this;
    }

    /**
     * Sets the status pill of a card email.
     *
     * @param value the badge text
     * @return this builder
     */
    public Builder badge(String value) {
      this.badge = StringUtils.trimToNull(value);
      return this;
    }

    /**
     * Sets the headline, which switches the email to the card design.
     *
     * @param value the title
     * @return this builder
     */
    public Builder title(String value) {
      this.title = StringUtils.trimToNull(value);
      return this;
    }

    /**
     * Sets the line under the headline of a card email.
     *
     * @param value the subtitle
     * @return this builder
     */
    public Builder subtitle(String value) {
      this.subtitle = StringUtils.trimToNull(value);
      return this;
    }

    /**
     * Sets the caption of a card email's summary box; the box's columns are the details.
     *
     * @param value the caption
     * @return this builder
     */
    public Builder summaryTitle(String value) {
      this.summaryTitle = StringUtils.trimToNull(value);
      return this;
    }

    /**
     * Sets the line right above a card email's button.
     *
     * @param value the note
     * @return this builder
     */
    public Builder ctaNote(String value) {
      this.ctaNote = StringUtils.trimToNull(value);
      return this;
    }

    /**
     * Sets a card email's "did you not ask for this" block.
     *
     * @param heading the block heading
     * @param text the block body
     * @return this builder
     */
    public Builder warning(String heading, String text) {
      this.warningTitle = StringUtils.trimToNull(heading);
      this.warningText = StringUtils.trimToNull(text);
      return this;
    }

    /**
     * Builds the immutable content.
     *
     * @return the content
     */
    public EmailContent build() {
      return new EmailContent(this);
    }
  }
}
