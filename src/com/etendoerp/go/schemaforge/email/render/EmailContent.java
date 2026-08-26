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
     * Builds the immutable content.
     *
     * @return the content
     */
    public EmailContent build() {
      return new EmailContent(this);
    }
  }
}
