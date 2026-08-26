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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.User;

/**
 * Unit tests for the Reply-To address resolved from the operator's session (ETP-5003).
 */
public class EmailSenderIdentityTest {

  @Test
  public void prefersTheDedicatedEmailFieldOverTheUsername() {
    assertEquals("sales@example.com",
        EmailSenderIdentity.pickAddress("sales@example.com", "login@example.com"));
  }

  @Test
  public void fallsBackToTheUsernameWhenTheEmailFieldIsEmpty() {
    // Etendo GO signs users up by email, so the only address on the record is the username.
    assertEquals("operator@example.com",
        EmailSenderIdentity.pickAddress(null, "operator@example.com"));
    assertEquals("operator@example.com",
        EmailSenderIdentity.pickAddress("   ", "operator@example.com"));
  }

  @Test
  public void fallsBackToTheUsernameWhenTheEmailFieldIsNotAnAddress() {
    assertEquals("operator@example.com",
        EmailSenderIdentity.pickAddress("not-an-address", "operator@example.com"));
  }

  @Test
  public void trimsSurroundingWhitespace() {
    assertEquals("operator@example.com",
        EmailSenderIdentity.pickAddress("  operator@example.com  ", null));
  }

  @Test
  public void resolvesNothingWhenNeitherFieldHoldsAnAddress() {
    // Service accounts such as System and GOAdmin land here: the send proceeds with no Reply-To.
    assertNull(EmailSenderIdentity.pickAddress(null, null));
    assertNull(EmailSenderIdentity.pickAddress("", ""));
    assertNull(EmailSenderIdentity.pickAddress("Admin", "System"));
    assertNull(EmailSenderIdentity.pickAddress("operator@localhost", null));
  }

  @Test
  public void rejectsValuesThatWouldRewriteTheHeader() {
    // A stored address must not be able to inject a header break or smuggle extra recipients.
    assertNull(EmailSenderIdentity.pickAddress("a@example.com\r\nBcc: spy@evil.test", null));
    assertNull(EmailSenderIdentity.pickAddress("a@example.com\nBcc: spy@evil.test", null));
    assertNull(EmailSenderIdentity.pickAddress("a@example.com, spy@evil.test", null));
    assertNull(EmailSenderIdentity.pickAddress("a@example.com; spy@evil.test", null));
    assertNull(EmailSenderIdentity.pickAddress("Operator <a@example.com>", null));
  }

  @Test
  public void readsTheAddressOfTheUserOwningTheSession() {
    User user = mock(User.class);
    when(user.getEmail()).thenReturn(null);
    when(user.getUsername()).thenReturn("valentin@example.com");
    OBContext context = mock(OBContext.class);
    when(context.getUser()).thenReturn(user);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(context);

      assertEquals("valentin@example.com", EmailSenderIdentity.resolveReplyTo());
    }
  }

  @Test
  public void resolvesNothingWhenThereIsNoSession() {
    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(null);

      assertNull(EmailSenderIdentity.resolveReplyTo());
    }
  }

  @Test
  public void resolvesNothingWhenTheSessionCarriesNoUser() {
    OBContext context = mock(OBContext.class);
    when(context.getUser()).thenReturn(null);

    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(context);

      assertNull(EmailSenderIdentity.resolveReplyTo());
    }
  }

  @Test
  public void neverFailsASendWhenTheSessionCannotBeRead() {
    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class)) {
      obContextMock.when(OBContext::getOBContext)
          .thenThrow(new IllegalStateException("no session"));

      assertNull(EmailSenderIdentity.resolveReplyTo());
    }
  }
}
