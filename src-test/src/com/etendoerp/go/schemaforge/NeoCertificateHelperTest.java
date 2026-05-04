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

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Unit tests for the pure-static NIF helpers in {@link NeoCertificateHelper}.
 * No DB or servlet context needed.
 *
 * DB-dependent behaviour (getOrgNif reads ad_orginfo.taxid via SAVEPOINT,
 * treats null/empty/? as "no NIF configured") is covered by integration tests
 * that require a live Etendo instance.
 */
public class NeoCertificateHelperTest {

  // Real-world RPJ subject from an FNMT test certificate:
  // description=..., serialNumber=IDCES-99999910G, GN=PRUEBAS, SN=CERTIFICADO FISICA,
  // CN=99999910G PRUEBAS CERTIFICADO (R: A39200019),
  // organizationIdentifier=VATES-A39200019, O=CERTIFICADO ENTIDAD PRUEBAS, C=ES
  private static final String RPJ_DN =
      "description=Ref:AEAT/AEAT0356, serialNumber=IDCES-99999910G, GN=PRUEBAS, " +
      "SN=CERTIFICADO FISICA, CN=99999910G PRUEBAS CERTIFICADO (R: A39200019), " +
      "organizationIdentifier=VATES-A39200019, O=CERTIFICADO ENTIDAD PRUEBAS, C=ES";

  // ── organizationIdentifier (RPJ) ────────────────────────────────────────────

  @Test
  public void parsesOrgNifFromOrganizationIdentifierFnmtRpj() {
    assertEquals("A39200019", NeoCertificateHelper.parseNifFromDn(RPJ_DN));
  }

  @Test
  public void organizationIdentifierTakesPriorityOverSerialNumber() {
    // serialNumber holds the representative's personal NIF (99999910G),
    // organizationIdentifier holds the org CIF (A39200019).
    // Must return the org CIF.
    assertEquals("A39200019", NeoCertificateHelper.parseNifFromDn(RPJ_DN));
  }

  @Test
  public void parsesOrgIdWithOidAttributeName() {
    // Some JDK versions return OID.2.5.4.97 instead of the attribute name
    String dn = "CN=TEST, OID.2.5.4.97=VATES-A39200019, C=ES";
    assertEquals("A39200019", NeoCertificateHelper.parseNifFromDn(dn));
  }

  // ── serialNumber (personal / autónomo) ─────────────────────────────────────

  @Test
  public void parsesNifFromIdcesPrefixSerialNumber() {
    // FNMT test cert for an individual: IDCES-<NIF>
    String dn = "CN=JUAN PEREZ, serialNumber=IDCES-12345678Z, O=FNMT, C=ES";
    assertEquals("12345678Z", NeoCertificateHelper.parseNifFromDn(dn));
  }

  @Test
  public void parsesNifFromVatidEsPrefixSerialNumber() {
    // Standard FNMT personal cert: VATID-ES<NIF>
    String dn = "CN=EMPRESA S.L., SERIALNUMBER=VATID-ESA1234567B, O=FNMT, C=ES";
    assertEquals("A1234567B", NeoCertificateHelper.parseNifFromDn(dn));
  }

  @Test
  public void parsesNifFromSerialNumberWithoutPrefix() {
    String dn = "CN=JUAN PEREZ, SERIALNUMBER=12345678Z, OU=FNMT Clase 2 CA, O=FNMT, C=ES";
    assertEquals("12345678Z", NeoCertificateHelper.parseNifFromDn(dn));
  }

  @Test
  public void parsesNifFromOidSerialNumberAttribute() {
    String dn = "CN=EMPRESA S.L., OID.2.5.4.5=VATID-ESB8765432A, O=CAMERFIRMA, C=ES";
    assertEquals("B8765432A", NeoCertificateHelper.parseNifFromDn(dn));
  }

  // ── CN (R: NIF) fallback ────────────────────────────────────────────────────

  @Test
  public void parsesNifFromCnRParentheses() {
    String dn = "CN=99999910G NOMBRE (R: A39200019), O=FNMT, C=ES";
    assertEquals("A39200019", NeoCertificateHelper.parseNifFromDn(dn));
  }

  @Test
  public void doesNotMatchPlainParenthesesInCnWithoutR() {
    // Old pattern matched any parenthesised value; new pattern requires "R: "
    String dn = "CN=EMPRESA S.L. (INTERNAL-CODE), O=FNMT, C=ES";
    assertNull(NeoCertificateHelper.parseNifFromDn(dn));
  }

  // ── Edge cases ──────────────────────────────────────────────────────────────

  @Test
  public void returnsNullWhenNoNifFound() {
    assertNull(NeoCertificateHelper.parseNifFromDn("CN=EXAMPLE ORG, O=SOME CA, C=US"));
  }

  @Test
  public void returnsNullForNullInput() {
    assertNull(NeoCertificateHelper.parseNifFromDn(null));
  }

  @Test
  public void extractedNifIsAlwaysUppercase() {
    String dn = "OID.2.5.4.97=vates-a1234567b";
    assertEquals("A1234567B", NeoCertificateHelper.parseNifFromDn(dn));
  }

  // ── normalizeNif ────────────────────────────────────────────────────────────

  @Test
  public void normalizesNifToUppercase() {
    assertEquals("A1234567B", NeoCertificateHelper.normalizeNif("a1234567b"));
  }

  @Test
  public void normalizesNifStripsHyphens() {
    assertEquals("A1234567B", NeoCertificateHelper.normalizeNif("A-1234567-B"));
  }

  @Test
  public void normalizesNifStripsSpaces() {
    assertEquals("A1234567B", NeoCertificateHelper.normalizeNif("A 1234567 B"));
  }

  @Test
  public void normalizationMakesNifsMatchable() {
    // VATES prefix stripped by parseNifFromDn; DB may have hyphens or mixed case
    assertEquals(
        NeoCertificateHelper.normalizeNif("A39200019"),
        NeoCertificateHelper.normalizeNif("a-39200019"));
  }

  @Test
  public void normalizationDistinguishesDifferentNifs() {
    assertNotEquals(
        NeoCertificateHelper.normalizeNif("A1234567B"),
        NeoCertificateHelper.normalizeNif("B7654321A"));
  }
}
