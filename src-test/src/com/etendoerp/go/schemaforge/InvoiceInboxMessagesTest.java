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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.erpCommon.utility.OBMessageUtils;

/**
 * The texts of the invoice inbox upload: the {@code AD_MESSAGE} translation with the English
 * fallback, placeholder replacement, and the neutralization of user-supplied values (file names
 * can carry the {@code @} delimiter).
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxMessages
 */
class InvoiceInboxMessagesTest {

  private static final String EMPTY_FILE_DEFAULT = "'@fileName@' is empty.";

  @Test
  void anAdMessageTextWinsOverTheEnglishDefaultAndItsPlaceholdersAreFilled() {
    try (MockedStatic<OBMessageUtils> utils = mockStatic(OBMessageUtils.class)) {
      utils.when(() -> OBMessageUtils.messageBD(InvoiceInboxMessages.EMPTY_FILE))
          .thenReturn("El archivo '@fileName@' esta vacio.");

      assertEquals("El archivo 'a.pdf' esta vacio.", InvoiceInboxMessages.emptyFile("a.pdf"));
    }
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "   ", InvoiceInboxMessages.EMPTY_FILE })
  void aMissingBlankOrEchoedKeyFallsBackToTheEnglishText(String fromAd) {
    try (MockedStatic<OBMessageUtils> utils = mockStatic(OBMessageUtils.class)) {
      utils.when(() -> OBMessageUtils.messageBD(InvoiceInboxMessages.EMPTY_FILE))
          .thenReturn(fromAd);

      assertEquals("'a.pdf' is empty.", InvoiceInboxMessages.emptyFile("a.pdf"));
    }
  }

  @Test
  void aFailingMessageLookupFallsBackToTheEnglishTextWithoutThrowing() {
    try (MockedStatic<OBMessageUtils> utils = mockStatic(OBMessageUtils.class)) {
      utils.when(() -> OBMessageUtils.messageBD(InvoiceInboxMessages.STORE_FAILED))
          .thenThrow(new IllegalStateException("no session"));

      assertEquals("'a.pdf' could not be stored. Try again.",
          InvoiceInboxMessages.storeFailed("a.pdf"));
    }
  }

  @Test
  void everyKeyHasAnEnglishDefaultSoAnAnswerIsNeverABareKey() {
    String[] keys = { InvoiceInboxMessages.DISABLED, InvoiceInboxMessages.NOT_MULTIPART,
        InvoiceInboxMessages.NO_FILES, InvoiceInboxMessages.TOO_MANY_FILES,
        InvoiceInboxMessages.REQUEST_TOO_LARGE, InvoiceInboxMessages.UNSUPPORTED_TYPE,
        InvoiceInboxMessages.EMPTY_FILE, InvoiceInboxMessages.STORE_FAILED,
        InvoiceInboxMessages.ORG_NOT_ALLOWED };
    try (MockedStatic<OBMessageUtils> utils = mockStatic(OBMessageUtils.class)) {
      for (String key : keys) {
        utils.when(() -> OBMessageUtils.messageBD(key)).thenReturn(key);

        String text = InvoiceInboxMessages.text(key);

        assertFalse(text == null || text.isBlank() || text.equals(key), key);
      }
    }
  }

  @Test
  void severalPlaceholdersAreReplacedFromTheAlternatingNameValueArguments() {
    try (MockedStatic<OBMessageUtils> utils = mockStatic(OBMessageUtils.class)) {
      utils.when(() -> OBMessageUtils.messageBD(InvoiceInboxMessages.TOO_MANY_FILES))
          .thenReturn("Sent @count@, maximum @max@.");

      assertEquals("Sent 21, maximum 20.",
          InvoiceInboxMessages.text(InvoiceInboxMessages.TOO_MANY_FILES, "count", "21", "max", "20"));
    }
  }

  @Test
  void aNullValueReplacesItsPlaceholderWithNothing() {
    try (MockedStatic<OBMessageUtils> utils = mockStatic(OBMessageUtils.class)) {
      utils.when(() -> OBMessageUtils.messageBD(InvoiceInboxMessages.EMPTY_FILE))
          .thenReturn(EMPTY_FILE_DEFAULT);

      assertEquals("'' is empty.", InvoiceInboxMessages.emptyFile(null));
    }
  }

  @Test
  void anAtSignInAValueIsNeutralizedAndNeverReadAsAMessageToken() {
    try (MockedStatic<OBMessageUtils> utils = mockStatic(OBMessageUtils.class)) {
      utils.when(() -> OBMessageUtils.messageBD(InvoiceInboxMessages.UNSUPPORTED_TYPE))
          .thenReturn("");

      String text = InvoiceInboxMessages.unsupportedType("@ETGO_X@.exe");

      assertTrue(text.contains("＠ETGO_X＠.exe"), text);
      assertFalse(text.contains("@ETGO_X@"), text);
    }
  }

  @Test
  void aValueThatLooksLikeAnotherPlaceholderIsNotSubstitutedAgain() {
    try (MockedStatic<OBMessageUtils> utils = mockStatic(OBMessageUtils.class)) {
      utils.when(() -> OBMessageUtils.messageBD(InvoiceInboxMessages.TOO_MANY_FILES))
          .thenReturn("Sent @count@, maximum @max@.");

      // count is replaced first: its value carries "@max@" and must not become "20".
      assertEquals("Sent ＠max＠, maximum 20.",
          InvoiceInboxMessages.text(InvoiceInboxMessages.TOO_MANY_FILES, "count", "@max@", "max", "20"));
      // And the other order: max is replaced first, then count's value is only neutralized.
      assertEquals("Sent ＠max＠, maximum 20.",
          InvoiceInboxMessages.text(InvoiceInboxMessages.TOO_MANY_FILES, "max", "20", "count", "@max@"));
    }
  }

  @Test
  void neutralizeReplacesEveryAtSignWithTheFullWidthOneAndToleratesNull() {
    assertEquals("＠a＠b＠", InvoiceInboxMessages.neutralize("@a@b@"));
    assertEquals("", InvoiceInboxMessages.neutralize(null));
    assertEquals("plain.pdf", InvoiceInboxMessages.neutralize("plain.pdf"));
  }
}
