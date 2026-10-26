/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.ocr.ReceiverTaxIdCheck.Status;

/**
 * The receiver tax-id check, a port of the application's {@code receiverTaxIdCheck.js}: every case of
 * the application's own test is repeated here, so the two cannot disagree.
 *
 * @covers com.etendoerp.go.schemaforge.ocr.ReceiverTaxIdCheck
 */
class ReceiverTaxIdCheckTest {

  private static Status status(String receiver, String org, JSONObject extra) throws JSONException {
    JSONObject extraction = extra == null ? new JSONObject() : extra;
    extraction.put("receiver", new JSONObject().put("tax_id_raw", receiver == null
        ? JSONObject.NULL : receiver));
    return ReceiverTaxIdCheck.check(extraction, org).getStatus();
  }

  private static Status status(String receiver, String org) throws JSONException {
    return status(receiver, org, null);
  }

  @Test
  @DisplayName("cleans to uppercase letters and digits, keeping leading zeros")
  void cleans() {
    assertEquals("ESB12345678", ReceiverTaxIdCheck.clean("es b-12.345 678"));
    assertEquals("", ReceiverTaxIdCheck.clean("?"));
    assertEquals("000123", ReceiverTaxIdCheck.clean("000123"));
    assertEquals("", ReceiverTaxIdCheck.clean(null));
  }

  @Test
  @DisplayName("matches with the ES prefix on either side or on both")
  void matchesWithPrefix() throws JSONException {
    assertEquals(Status.MATCH, status("ESB12345678", "B12345678"));
    assertEquals(Status.MATCH, status("B12345678", "ESB12345678"));
    assertEquals(Status.MATCH, status("ES B-12345678", "ESB12345678"));
  }

  @Test
  @DisplayName("EL and GR are the same prefix")
  void greekAlias() {
    assertEquals(true, ReceiverTaxIdCheck.idsMatch("EL123456789", "GR123456789"));
    assertEquals(true, ReceiverTaxIdCheck.idsMatch("EL123456789", "123456789"));
  }

  @Test
  @DisplayName("never strips a leading letter pair that is not a VAT prefix (NIE, CIF)")
  void doesNotStripNieOrCif() throws JSONException {
    assertEquals(false, ReceiverTaxIdCheck.idsMatch("X1234567L", "1234567L"));
    assertEquals(Status.MATCH, status("X1234567L", "X1234567L"));
    assertEquals(Status.MISMATCH, status("B12345678", "12345678"));
  }

  @Test
  @DisplayName("a CUIT with dashes matches the plain digits")
  void cuit() throws JSONException {
    assertEquals(Status.MATCH, status("30-12345678-9", "30123456789"));
  }

  @Test
  @DisplayName("a different id is a mismatch")
  void mismatch() throws JSONException {
    assertEquals(Status.MISMATCH, status("A99999999", "B12345678"));
    assertEquals(Status.MISMATCH, status("43132390", "B81639719"));
  }

  @Test
  @DisplayName("a missing or blank receiver id is absent")
  void absent() throws JSONException {
    assertEquals(Status.ABSENT, ReceiverTaxIdCheck.check(new JSONObject(), "B12345678").getStatus());
    assertEquals(Status.ABSENT, status(null, "B12345678"));
    assertEquals(Status.ABSENT, status("  ", "B12345678"));
  }

  @Test
  @DisplayName("a blank, null or ? organization id is no-org-tax-id")
  void noOrgTaxId() throws JSONException {
    assertEquals(Status.NO_ORG_TAX_ID, status("B12345678", "?"));
    assertEquals(Status.NO_ORG_TAX_ID, status("B12345678", ""));
    assertEquals(Status.NO_ORG_TAX_ID, status("B12345678", null));
  }

  @Test
  @DisplayName("same-as-issuer, from issuer.tax_id_raw or from the legacy tax_id")
  void sameAsIssuer() throws JSONException {
    assertEquals(Status.SAME_AS_ISSUER, status("ESA999", "B1", new JSONObject()
        .put("issuer", new JSONObject().put("tax_id_raw", "A999"))));
    assertEquals(Status.SAME_AS_ISSUER, status("A999", "B1", new JSONObject().put("tax_id", "A999")));
  }

  @Test
  @DisplayName("returns both ids, trimmed, for the message")
  void returnsBothIds() throws JSONException {
    ReceiverTaxIdCheck.Result result = ReceiverTaxIdCheck.check(
        new JSONObject().put("receiver", new JSONObject().put("tax_id_raw", " A1 ")), " B2 ");
    assertEquals(Status.MISMATCH, result.getStatus());
    assertEquals("A1", result.getReceiverTaxId());
    assertEquals("B2", result.getOrgTaxId());
    assertEquals("mismatch", result.getStatus().code());
    assertEquals("no-org-tax-id", Status.NO_ORG_TAX_ID.code());
  }
}
