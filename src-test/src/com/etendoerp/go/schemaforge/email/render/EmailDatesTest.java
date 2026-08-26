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
import static org.junit.Assert.assertNull;

import java.util.Calendar;
import java.util.Date;

import org.junit.Test;

public class EmailDatesTest {

  private static Date august26() {
    Calendar calendar = Calendar.getInstance();
    calendar.clear();
    calendar.set(2026, Calendar.AUGUST, 26);
    return calendar.getTime();
  }

  @Test
  public void formatsTheSpanishWayForASpanishRecipient() {
    assertEquals("26/08/2026", EmailDates.format(august26(), "es_ES"));
  }

  @Test
  public void formatsTheAmericanWayForAnEnglishRecipient() {
    // The same instant, read by two customers, must not print the same digits in a different
    // order: 08/26 and 26/08 are both valid and mean different things.
    assertEquals("08/26/2026", EmailDates.format(august26(), "en_US"));
  }

  @Test
  public void fallsBackToSpanishForAnUnknownLanguage() {
    assertEquals("26/08/2026", EmailDates.format(august26(), "pt_BR"));
  }

  @Test
  public void fallsBackToSpanishWhenNoLanguageIsGiven() {
    assertEquals("26/08/2026", EmailDates.format(august26(), null));
  }

  @Test
  public void returnsNothingWhenThereIsNoDate() {
    // The caller drops the row on null rather than printing an empty one.
    assertNull(EmailDates.format(null, "es_ES"));
  }

  @Test
  public void alwaysPrintsAFourDigitYear() {
    // A two-digit year in a due date is the kind of ambiguity that generates a support ticket.
    assertEquals(10, EmailDates.format(august26(), "es_ES").length());
    assertEquals(10, EmailDates.format(august26(), "en_US").length());
  }
}
