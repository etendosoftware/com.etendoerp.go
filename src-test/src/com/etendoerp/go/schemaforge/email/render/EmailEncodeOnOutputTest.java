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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * Guardrail for ADR-0005: stored free text is encoded on output. Mirrors
 * {@code docs/security/html-encoding-fixtures.md} row for row.
 */
public class EmailEncodeOnOutputTest {

  private static final String[][] FIXTURES = {
      { "<script>alert(document.cookie)</script>",
          "&lt;script&gt;alert(document.cookie)&lt;/script&gt;" },
      { "<img src=x onerror=alert(1)>", "&lt;img src=x onerror=alert(1)&gt;" },
      { "Tom & Jerry", "Tom &amp; Jerry" },
      { "\" onmouseover=\"x", "&quot; onmouseover=&quot;x" },
      { "' onmouseover='x", "&#39; onmouseover=&#39;x" },
      { "&lt;", "&amp;lt;" },
      { "Normal Value", "Normal Value" },
      { "", "" },
      { null, "" },
  };

  @Test
  public void escapeHtmlMatchesTheFixtures() {
    for (String[] fixture : FIXTURES) {
      assertEquals(fixture[0], fixture[1], EmailEscape.escapeHtml(fixture[0]));
    }
  }

  @Test
  public void emphasisRunsAfterEncoding() {
    assertEquals("<strong>&lt;b&gt;x&lt;/b&gt;</strong>",
        EmailEscape.applyBold(EmailEscape.escapeHtml("**<b>x</b>**")));
  }

  @Test
  public void layoutNeverEmitsStoredMarkupFromAnyTextSlot() {
    String payload = "<script>alert(document.cookie)</script>";
    String html = EmailLayout.render(EmailContent.builder()
        .greeting(payload)
        .paragraph(payload)
        .detail(payload, payload)
        .note(payload)
        .signature(payload)
        .cta(payload, "https://example.com/?q=\"><script>x</script>")
        .build());
    assertFalse(html.contains("<script>"));
    assertFalse(html.contains("\"><script"));
  }
}
