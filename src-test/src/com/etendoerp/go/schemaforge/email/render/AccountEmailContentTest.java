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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link AccountEmailContent} (ETP-5003).
 */
public class AccountEmailContentTest {

  @Test
  public void emphasisesTheOrganizationInTheBody() {
    String html = EmailLayout.render(AccountEmailContent.build("organization-joined", "es_ES",
        "Santiago", "https://go.etendo.cloud/dashboard",
        "<strong>" + EmailEscape.escapeHtml("SMF Consulting") + "</strong>"));

    // The body is emitted as markup so copy can emphasise a name. Escaping it here would ship
    // "&lt;strong&gt;" to the reader, which is exactly what happened before this was fixed.
    assertTrue(html.contains("<strong>SMF Consulting</strong>"));
    assertFalse(html.contains("&lt;strong&gt;"));
  }

  @Test
  public void escapesAValueTheCallerEscaped() {
    String hostile = EmailEscape.escapeHtml("<script>alert(1)</script>");
    String html = EmailLayout.render(AccountEmailContent.build("organization-joined", "es_ES",
        "Santiago", null, hostile));

    assertFalse(html.contains("<script>"));
    assertTrue(html.contains("&lt;script&gt;"));
  }

  @Test
  public void omitsTheButtonWhenThereIsNoLink() {
    String html = EmailLayout.render(AccountEmailContent.build("organization-joined", "es_ES",
        "Santiago", null, "ACME"));

    assertFalse(html.contains("v:roundrect"));
    // Without a button there is nothing for the "if the button does not work" block to point at.
    assertFalse(html.contains("Si el botón no funciona"));
  }

  @Test
  public void appendsOnlyTheNotesTheCatalogDefines() {
    String html = EmailLayout.render(AccountEmailContent.buildWithNotes("password-changed", "es_ES",
        "Santiago", null, new String[] { "note.warning", "note.doesNotExist" }, null));

    assertTrue(html.contains("contacta a soporte"));
    assertFalse(html.contains("note.doesNotExist"));
  }
}
