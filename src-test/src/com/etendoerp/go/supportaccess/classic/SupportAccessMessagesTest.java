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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.supportaccess.classic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.etendoerp.go.supportaccess.SupportAccessException;
import com.etendoerp.go.supportaccess.SupportAccessRecord;
import com.etendoerp.go.supportaccess.SupportAccessService;
import com.etendoerp.go.supportaccess.SupportTenantBusyException;

/**
 * Maps every support access refusal to its {@code ETGO_} message and builds the HTML and client
 * script that hand the GO link over, with no database.
 *
 * @covers com.etendoerp.go.supportaccess.classic.SupportAccessMessages
 */
class SupportAccessMessagesTest {

  private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");
  private static final ZoneId UTC = ZoneOffset.UTC;
  private static final String LABEL_PREFIX = "label:";
  private static final UnaryOperator<String> LABELS = key -> LABEL_PREFIX + key;
  private static final String GO_URL = "https://go.example.com/support-access#t=abc_DEF-123";

  private static SupportAccessMessages.Text map(SupportAccessException error) {
    return SupportAccessMessages.forError(error, 240, NOW, UTC, LABELS);
  }

  @ParameterizedTest
  @CsvSource({
      "SUPPORT_TARGET_NOT_ELIGIBLE, ETGO_SupportTargetNotEligible",
      "SUPPORT_OPERATOR_NOT_ALLOWED, ETGO_SupportOperatorNotAllowed",
      "SUPPORT_NO_ADMIN_ROLE, ETGO_SupportNoAdminRole",
      "SUPPORT_ACCOUNT_MISSING, ETGO_SupportAccountMissing",
      "SUPPORT_ACCESS_NOT_OPEN, ETGO_SupportAccessNotOpen",
      "SOMETHING_NEW, ETGO_SupportAccessFailed" })
  void mapsEachCodeWithoutParametersToItsMessage(String code, String key) {
    SupportAccessMessages.Text text = map(new SupportAccessException(code, "x"));
    assertEquals(key, text.getKey());
    assertEquals(0, text.getParams().length);
  }

  @Test
  void reasonMessageCarriesTheMinimumLength() {
    SupportAccessMessages.Text text = map(
        new SupportAccessException(SupportAccessException.CODE_REASON_REQUIRED, "x"));
    assertEquals(SupportAccessMessages.MSG_REASON_REQUIRED, text.getKey());
    assertArrayEquals(new String[] { String.valueOf(SupportAccessService.MIN_REASON_LENGTH) },
        text.getParams());
  }

  @Test
  void durationMessageCarriesTheConfiguredMaximum() {
    SupportAccessMessages.Text text = map(
        new SupportAccessException(SupportAccessException.CODE_DURATION_INVALID, "x"));
    assertEquals(SupportAccessMessages.MSG_DURATION_INVALID, text.getKey());
    assertArrayEquals(new String[] { "240" }, text.getParams());
  }

  @Test
  void nullCodeFallsBackToTheGenericFailure() {
    assertEquals(SupportAccessMessages.MSG_ACCESS_FAILED,
        map(new SupportAccessException(null, "x")).getKey());
  }

  @Test
  void busyTenantSaysWhoIsInsideUntilWhenAndMinutesLeft() {
    SupportAccessRecord holder = new SupportAccessRecord();
    holder.setOperatorName("Ana <b>Ops</b>");
    holder.setSessionExpiresAt(NOW.plusSeconds(41 * 60L + 10));

    SupportAccessMessages.Text text = map(new SupportTenantBusyException(holder));

    assertEquals(SupportAccessMessages.MSG_TENANT_BUSY, text.getKey());
    assertArrayEquals(new String[] { "Ana &lt;b&gt;Ops&lt;/b&gt;", "10:41", "42" },
        text.getParams());
  }

  @Test
  void busyTenantShowsTheTimeInTheGivenZone() {
    SupportAccessRecord holder = new SupportAccessRecord();
    holder.setOperatorName("Ana");
    holder.setSessionExpiresAt(NOW.plusSeconds(3600));

    SupportAccessMessages.Text text = SupportAccessMessages.forError(
        new SupportTenantBusyException(holder), 240, NOW, ZoneId.of("Europe/Madrid"), LABELS);

    assertEquals("13:00", text.getParams()[1]);
  }

  @Test
  void busyTenantPastItsExpiryShowsZeroMinutesLeft() {
    SupportAccessRecord holder = new SupportAccessRecord();
    holder.setOperatorName("Ana");
    holder.setSessionExpiresAt(NOW.minusSeconds(30));

    assertEquals("0", map(new SupportTenantBusyException(holder)).getParams()[2]);
  }

  @Test
  void busyTenantWithoutOperatorNameUsesTheUnknownOperatorLabel() {
    SupportAccessRecord holder = new SupportAccessRecord();
    holder.setSessionExpiresAt(NOW.plusSeconds(60));

    SupportAccessMessages.Text text = map(new SupportTenantBusyException(holder));

    assertEquals(LABEL_PREFIX + SupportAccessMessages.MSG_UNKNOWN_OPERATOR, text.getParams()[0]);
  }

  @Test
  void busyTenantWithoutExpiryUsesTheShortMessage() {
    SupportAccessMessages.Text text = map(new SupportTenantBusyException(null));

    assertEquals(SupportAccessMessages.MSG_TENANT_BUSY_NO_EXPIRY, text.getKey());
    assertArrayEquals(new String[] { LABEL_PREFIX + SupportAccessMessages.MSG_UNKNOWN_OPERATOR },
        text.getParams());
  }

  @Test
  void onlyReasonAndDurationAreFixableInThePopup() {
    assertTrue(SupportAccessMessages.isFixableInPopup(
        SupportAccessException.CODE_REASON_REQUIRED));
    assertTrue(SupportAccessMessages.isFixableInPopup(
        SupportAccessException.CODE_DURATION_INVALID));
    assertFalse(SupportAccessMessages.isFixableInPopup(
        SupportTenantBusyException.CODE_TENANT_BUSY));
    assertFalse(SupportAccessMessages.isFixableInPopup(null));
  }

  @Test
  void acceptsOnlyPlainHttpUrls() {
    assertTrue(SupportAccessMessages.isSafeHttpUrl(GO_URL));
    assertTrue(SupportAccessMessages.isSafeHttpUrl("HTTP://localhost:3000/support-access#t=x"));
    assertFalse(SupportAccessMessages.isSafeHttpUrl(null));
    assertFalse(SupportAccessMessages.isSafeHttpUrl("javascript:alert(1)//https://x"));
    assertFalse(SupportAccessMessages.isSafeHttpUrl("https://x/\"onmouseover=\"y"));
    assertFalse(SupportAccessMessages.isSafeHttpUrl("https://x/a b"));
    assertFalse(SupportAccessMessages.isSafeHttpUrl("https://x/<script>"));
    assertFalse(SupportAccessMessages.isSafeHttpUrl("/support-access#t=x"));
  }

  @Test
  void linkOpensInANewTabWithoutOpenerAndEscapesItsParts() {
    String html = SupportAccessMessages.link("https://x/a?b=1&c=2", "use <this>");
    assertEquals("<a href=\"https://x/a?b=1&amp;c=2\" target=\"_blank\" "
        + "rel=\"noopener noreferrer\">use &lt;this&gt;</a>", html);
  }

  @Test
  void openScriptQuotesTheUrlAsAJavaScriptString() {
    String prefix = "window.open(\"";
    String suffix = "\", '_blank', 'noopener,noreferrer');";
    String script = SupportAccessMessages.openInNewTabScript(GO_URL);
    assertTrue(script.startsWith(prefix), script);
    assertTrue(script.endsWith(suffix), script);
    // JSON quoting may write "/" as "\/": the same string in JavaScript.
    assertEquals(GO_URL, script.substring(prefix.length(), script.length() - suffix.length())
        .replace("\\/", "/"));
    assertTrue(SupportAccessMessages.openInNewTabScript("https://x/\"y").contains("\\\""));
  }

  @Test
  void escapesTheFiveHtmlCharacters() {
    assertEquals("&amp;&lt;&gt;&quot;&#39;x", SupportAccessMessages.escapeHtml("&<>\"'x"));
    assertEquals("", SupportAccessMessages.escapeHtml(null));
  }
}
