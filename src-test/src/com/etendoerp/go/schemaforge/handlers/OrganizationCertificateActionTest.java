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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * ETP-5852 (M-170): the certificate status an agent reads — the expiry as a date and the days left,
 * negative once expired; no certificate answers exists:false and nothing else.
 *
 * @covers com.etendoerp.go.schemaforge.handlers.OrganizationCertificateAction
 */
class OrganizationCertificateActionTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

  @Test
  void aValidCertificateSaysWhenItExpiresAndHowManyDaysAreLeft() throws Exception {
    JSONObject status = OrganizationCertificateAction.status(
        java.sql.Date.valueOf("2026-11-28"), TODAY);

    assertTrue(status.getBoolean("exists"));
    assertEquals("2026-11-28", status.getString("validTo"));
    assertEquals(30, status.getLong("daysLeft"));
  }

  @Test
  void anExpiredCertificateHasNegativeDaysLeft() throws Exception {
    JSONObject status = OrganizationCertificateAction.status(
        java.sql.Date.valueOf("2026-10-19"), TODAY);

    assertEquals(-10, status.getLong("daysLeft"));
  }

  @Test
  void noCertificateIsReportedAsMissingOnly() throws Exception {
    JSONObject status = OrganizationCertificateAction.status(null, TODAY);

    assertFalse(status.getBoolean("exists"));
    assertFalse(status.has("validTo"));
    assertFalse(status.has("daysLeft"));
  }
}
