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
package com.etendoerp.go.schemaforge.email;

import java.util.Date;

import org.apache.commons.lang3.StringUtils;

/**
 * One row of the document summary block rendered above the call to action (ETP-5003).
 *
 * <p>A detail pairs a catalog key with a value the resolver read from the record. Dates are kept
 * unformatted on purpose: the resolver runs while the sender's session is active and cannot know
 * the recipient's language, so formatting happens in the contract, where the language is known.</p>
 */
public final class EmailDocumentDetail {

  private final String labelKey;
  private final Date date;
  private final String text;

  private EmailDocumentDetail(String labelKey, Date date, String text) {
    this.labelKey = StringUtils.trimToNull(labelKey);
    this.date = date == null ? null : new Date(date.getTime());
    this.text = StringUtils.trimToNull(text);
  }

  /**
   * Creates a row holding a calendar date.
   *
   * @param labelKey catalog key of the row label
   * @param value the date, or {@code null} when the record does not carry it
   * @return the detail, which reports {@link #hasValue()} as {@code false} when the date is absent
   */
  public static EmailDocumentDetail date(String labelKey, Date value) {
    return new EmailDocumentDetail(labelKey, value, null);
  }

  /**
   * Creates a row holding an already formatted value, such as a currency amount.
   *
   * @param labelKey catalog key of the row label
   * @param value the value, or {@code null} when the record does not carry it
   * @return the detail, which reports {@link #hasValue()} as {@code false} when the value is blank
   */
  public static EmailDocumentDetail text(String labelKey, String value) {
    return new EmailDocumentDetail(labelKey, null, value);
  }

  /**
   * Indicates whether this row carries something worth rendering.
   *
   * <p>A row whose value is absent is dropped rather than printed empty: a document without a due
   * date must not show a "Due date" label with nothing beside it.</p>
   *
   * @return {@code true} when the row has both a label key and a value
   */
  public boolean hasValue() {
    return labelKey != null && (date != null || text != null);
  }

  /**
   * @return {@code true} when the value is a date that still needs formatting
   */
  public boolean isDate() {
    return date != null;
  }

  public String getLabelKey() {
    return labelKey;
  }

  /**
   * @return a defensive copy of the date, or {@code null} when this row holds text
   */
  public Date getDate() {
    return date == null ? null : new Date(date.getTime());
  }

  public String getText() {
    return text;
  }
}
