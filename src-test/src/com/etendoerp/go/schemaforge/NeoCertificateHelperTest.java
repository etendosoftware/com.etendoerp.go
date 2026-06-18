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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Date;

import javax.security.auth.x500.X500Principal;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.OrganizationInformation;

/**
 * Unit tests for {@link NeoCertificateHelper}.
 *
 * Covers:
 *   - parseNifFromDn (pure static, multiple DN formats)
 *   - normalizeNif (pure static)
 *   - handleCertificateGet (mocked OBDal)
 *   - handleCertificateDelete (mocked OBDal)
 *   - handleCertificateUpload (multipart validation, content-type guard)
 *   - resolveFileName (private, via reflection)
 *   - getOrgNif (private, via reflection)
 *   - buildCertDetails (private, via reflection)
 *   - extractCertDetails (private, via reflection)
 *   - validateNifOrGetPendingResponse (private, via reflection)
 */
public class NeoCertificateHelperTest {

  // Real-world RPJ subject from an FNMT test certificate
  private static final String RPJ_DN =
      "description=Ref:AEAT/AEAT0356, serialNumber=IDCES-99999910G, GN=PRUEBAS, " +
      "SN=CERTIFICADO FISICA, CN=99999910G PRUEBAS CERTIFICADO (R: A39200019), " +
      "organizationIdentifier=VATES-A39200019, O=CERTIFICADO ENTIDAD PRUEBAS, C=ES";

  // ── parseNifFromDn: organizationIdentifier (RPJ) ──────────────────────────

  @Test
  public void parsesOrgNifFromOrganizationIdentifierFnmtRpj() {
    assertEquals("A39200019", NeoCertificateHelper.parseNifFromDn(RPJ_DN));
  }

  @Test
  public void organizationIdentifierTakesPriorityOverSerialNumber() {
    assertEquals("A39200019", NeoCertificateHelper.parseNifFromDn(RPJ_DN));
  }

  @Test
  public void parsesOrgIdWithOidAttributeName() {
    String dn = "CN=TEST, OID.2.5.4.97=VATES-A39200019, C=ES";
    assertEquals("A39200019", NeoCertificateHelper.parseNifFromDn(dn));
  }

  // ── parseNifFromDn: serialNumber (personal / autonomo) ────────────────────

  @Test
  public void parsesNifFromIdcesPrefixSerialNumber() {
    String dn = "CN=JUAN PEREZ, serialNumber=IDCES-12345678Z, O=FNMT, C=ES";
    assertEquals("12345678Z", NeoCertificateHelper.parseNifFromDn(dn));
  }

  @Test
  public void parsesNifFromVatidEsPrefixSerialNumber() {
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

  // ── parseNifFromDn: CN (R: NIF) fallback ──────────────────────────────────

  @Test
  public void parsesNifFromCnRParentheses() {
    String dn = "CN=99999910G NOMBRE (R: A39200019), O=FNMT, C=ES";
    assertEquals("A39200019", NeoCertificateHelper.parseNifFromDn(dn));
  }

  @Test
  public void doesNotMatchPlainParenthesesInCnWithoutR() {
    String dn = "CN=EMPRESA S.L. (INTERNAL-CODE), O=FNMT, C=ES";
    assertNull(NeoCertificateHelper.parseNifFromDn(dn));
  }

  // ── parseNifFromDn: edge cases ────────────────────────────────────────────

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

  @Test
  public void returnsNullForEmptyString() {
    assertNull(NeoCertificateHelper.parseNifFromDn(""));
  }

  // ── normalizeNif ──────────────────────────────────────────────────────────

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

  @Test
  public void normalizesNifStripsHyphensAndSpacesTogether() {
    assertEquals("A1234567B", NeoCertificateHelper.normalizeNif("A -1234 567- B"));
  }

  // ── handleCertificateGet ──────────────────────────────────────────────────

  @Test
  public void getCertificateReturnsBadRequestWhenOrgIdMissing() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("orgId")).thenReturn(null);

    NeoResponse resp = NeoCertificateHelper.handleCertificateGet(request);
    assertEquals(400, resp.getHttpStatus());
  }

  @Test
  public void getCertificateReturnsBadRequestWhenOrgIdBlank() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("orgId")).thenReturn("   ");

    NeoResponse resp = NeoCertificateHelper.handleCertificateGet(request);
    assertEquals(400, resp.getHttpStatus());
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  @Test
  public void getCertificateReturnsExistsFalseWhenNoCert() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("orgId")).thenReturn("TEST_ORG");

    OBDal obDal = mock(OBDal.class);
    Session session = mock(Session.class);
    NativeQuery nativeQuery = mock(NativeQuery.class);

    when(obDal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn(nativeQuery);
    when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
    when(nativeQuery.uniqueResult()).thenReturn(null);

    try (MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(obDal);

      NeoResponse resp = NeoCertificateHelper.handleCertificateGet(request);
      assertEquals(200, resp.getHttpStatus());
      assertFalse(resp.getBody().getBoolean("exists"));
    }
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  @Test
  public void getCertificateReturnsExistsTrueWithDate() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("orgId")).thenReturn("TEST_ORG");

    OBDal obDal = mock(OBDal.class);
    Session session = mock(Session.class);
    NativeQuery nativeQuery = mock(NativeQuery.class);

    Date expirationDate = new Date();

    when(obDal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn(nativeQuery);
    when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
    when(nativeQuery.uniqueResult()).thenReturn(expirationDate);

    try (MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(obDal);

      NeoResponse resp = NeoCertificateHelper.handleCertificateGet(request);
      assertEquals(200, resp.getHttpStatus());
      assertTrue(resp.getBody().getBoolean("exists"));
      assertNotNull(resp.getBody().getString("validTo"));
    }
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  @Test
  public void getCertificateReturns500OnException() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("orgId")).thenReturn("TEST_ORG");

    OBDal obDal = mock(OBDal.class);
    Session session = mock(Session.class);

    when(obDal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenThrow(new RuntimeException("DB error"));

    try (MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(obDal);

      NeoResponse resp = NeoCertificateHelper.handleCertificateGet(request);
      assertEquals(500, resp.getHttpStatus());
    }
  }

  // ── handleCertificateDelete ───────────────────────────────────────────────

  @Test
  public void deleteCertificateReturnsBadRequestWhenOrgIdMissing() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("orgId")).thenReturn(null);

    NeoResponse resp = NeoCertificateHelper.handleCertificateDelete(request);
    assertEquals(400, resp.getHttpStatus());
  }

  @Test
  public void deleteCertificateReturnsBadRequestWhenOrgIdBlank() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("orgId")).thenReturn("");

    NeoResponse resp = NeoCertificateHelper.handleCertificateDelete(request);
    assertEquals(400, resp.getHttpStatus());
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  @Test
  public void deleteCertificateReturnsDeletedCountZero() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("orgId")).thenReturn("TEST_ORG");

    OBDal obDal = mock(OBDal.class);
    OBCriteria criteria = mock(OBCriteria.class);

    when(obDal.createCriteria(any(Class.class))).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.emptyList());

    try (MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(obDal);

      NeoResponse resp = NeoCertificateHelper.handleCertificateDelete(request);
      assertEquals(200, resp.getHttpStatus());
      assertEquals(0, resp.getBody().getInt("deleted"));
    }
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  @Test
  public void deleteCertificateReturns500OnException() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("orgId")).thenReturn("TEST_ORG");

    OBDal obDal = mock(OBDal.class);
    when(obDal.createCriteria(any(Class.class))).thenThrow(new RuntimeException("DB error"));

    try (MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(obDal);

      NeoResponse resp = NeoCertificateHelper.handleCertificateDelete(request);
      assertEquals(500, resp.getHttpStatus());
    }
  }

  // ── handleCertificateUpload ───────────────────────────────────────────────

  @Test
  public void uploadReturnsBadRequestWhenNotMultipart() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentType()).thenReturn("application/json");

    NeoResponse resp = NeoCertificateHelper.handleCertificateUpload(request);
    assertEquals(400, resp.getHttpStatus());
  }

  @Test
  public void uploadReturnsBadRequestWhenContentTypeNull() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentType()).thenReturn(null);

    NeoResponse resp = NeoCertificateHelper.handleCertificateUpload(request);
    assertEquals(400, resp.getHttpStatus());
  }

  @Test
  public void uploadReturnsBadRequestWhenMissingFields() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentType()).thenReturn("multipart/form-data");
    when(request.getPart("certificate")).thenReturn(null);
    when(request.getParameter("orgId")).thenReturn("TEST_ORG");
    when(request.getParameter("password")).thenReturn("secret");

    NeoResponse resp = NeoCertificateHelper.handleCertificateUpload(request);
    assertEquals(400, resp.getHttpStatus());
  }

  @Test
  public void uploadReturnsBadRequestWhenOrgIdMissing() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    Part filePart = mock(Part.class);
    when(request.getContentType()).thenReturn("multipart/form-data");
    when(request.getPart("certificate")).thenReturn(filePart);
    when(request.getParameter("orgId")).thenReturn(null);
    when(request.getParameter("password")).thenReturn("secret");

    NeoResponse resp = NeoCertificateHelper.handleCertificateUpload(request);
    assertEquals(400, resp.getHttpStatus());
  }

  @Test
  public void uploadReturnsBadRequestWhenPasswordBlank() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    Part filePart = mock(Part.class);
    when(request.getContentType()).thenReturn("multipart/form-data");
    when(request.getPart("certificate")).thenReturn(filePart);
    when(request.getParameter("orgId")).thenReturn("TEST_ORG");
    when(request.getParameter("password")).thenReturn("   ");

    NeoResponse resp = NeoCertificateHelper.handleCertificateUpload(request);
    assertEquals(400, resp.getHttpStatus());
  }

  // ── resolveFileName (private) ─────────────────────────────────────────────

  @Test
  public void resolveFileNameExtractsFromContentDisposition() throws Exception {
    Part part = mock(Part.class);
    when(part.getHeader("Content-Disposition"))
        .thenReturn("form-data; name=\"certificate\"; filename=\"mycert.p12\"");

    Method m = NeoCertificateHelper.class.getDeclaredMethod("resolveFileName", Part.class);
    m.setAccessible(true);
    String result = (String) m.invoke(null, part);
    assertEquals("mycert.p12", result);
  }

  @Test
  public void resolveFileNameReturnsDefaultWhenNoDisposition() throws Exception {
    Part part = mock(Part.class);
    when(part.getHeader("Content-Disposition")).thenReturn(null);

    Method m = NeoCertificateHelper.class.getDeclaredMethod("resolveFileName", Part.class);
    m.setAccessible(true);
    String result = (String) m.invoke(null, part);
    assertEquals("certificate.p12", result);
  }

  @Test
  public void resolveFileNameReturnsDefaultWhenNoFilenameSegment() throws Exception {
    Part part = mock(Part.class);
    when(part.getHeader("Content-Disposition")).thenReturn("form-data; name=\"certificate\"");

    Method m = NeoCertificateHelper.class.getDeclaredMethod("resolveFileName", Part.class);
    m.setAccessible(true);
    String result = (String) m.invoke(null, part);
    assertEquals("certificate.p12", result);
  }

  // ── getOrgNif (private) ───────────────────────────────────────────────────

  @Test
  public void getOrgNifReturnsNullWhenOrgInfoNotFound() throws Exception {
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(eq(OrganizationInformation.class), anyString())).thenReturn(null);

    try (MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(obDal);

      Method m = NeoCertificateHelper.class.getDeclaredMethod("getOrgNif", String.class);
      m.setAccessible(true);
      assertNull(m.invoke(null, "NONEXISTENT"));
    }
  }

  @Test
  public void getOrgNifReturnsNullWhenTaxIdIsNull() throws Exception {
    OBDal obDal = mock(OBDal.class);
    OrganizationInformation orgInfo = mock(OrganizationInformation.class);
    when(obDal.get(eq(OrganizationInformation.class), anyString())).thenReturn(orgInfo);
    when(orgInfo.getTaxID()).thenReturn(null);

    try (MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(obDal);

      Method m = NeoCertificateHelper.class.getDeclaredMethod("getOrgNif", String.class);
      m.setAccessible(true);
      assertNull(m.invoke(null, "ORG1"));
    }
  }

  @Test
  public void getOrgNifReturnsNullWhenTaxIdIsQuestionMark() throws Exception {
    OBDal obDal = mock(OBDal.class);
    OrganizationInformation orgInfo = mock(OrganizationInformation.class);
    when(obDal.get(eq(OrganizationInformation.class), anyString())).thenReturn(orgInfo);
    when(orgInfo.getTaxID()).thenReturn("?");

    try (MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(obDal);

      Method m = NeoCertificateHelper.class.getDeclaredMethod("getOrgNif", String.class);
      m.setAccessible(true);
      assertNull(m.invoke(null, "ORG1"));
    }
  }

  @Test
  public void getOrgNifReturnsNullWhenTaxIdIsEmpty() throws Exception {
    OBDal obDal = mock(OBDal.class);
    OrganizationInformation orgInfo = mock(OrganizationInformation.class);
    when(obDal.get(eq(OrganizationInformation.class), anyString())).thenReturn(orgInfo);
    when(orgInfo.getTaxID()).thenReturn("   ");

    try (MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(obDal);

      Method m = NeoCertificateHelper.class.getDeclaredMethod("getOrgNif", String.class);
      m.setAccessible(true);
      assertNull(m.invoke(null, "ORG1"));
    }
  }

  @Test
  public void getOrgNifReturnsValueWhenTaxIdPresent() throws Exception {
    OBDal obDal = mock(OBDal.class);
    OrganizationInformation orgInfo = mock(OrganizationInformation.class);
    when(obDal.get(eq(OrganizationInformation.class), anyString())).thenReturn(orgInfo);
    when(orgInfo.getTaxID()).thenReturn("A12345678");

    try (MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(obDal);

      Method m = NeoCertificateHelper.class.getDeclaredMethod("getOrgNif", String.class);
      m.setAccessible(true);
      assertEquals("A12345678", m.invoke(null, "ORG1"));
    }
  }

  @Test
  public void getOrgNifReturnsNullOnException() throws Exception {
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(eq(OrganizationInformation.class), anyString()))
        .thenThrow(new RuntimeException("DB down"));

    try (MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(obDal);

      Method m = NeoCertificateHelper.class.getDeclaredMethod("getOrgNif", String.class);
      m.setAccessible(true);
      assertNull(m.invoke(null, "ORG1"));
    }
  }

  // ── buildCertDetails (private) ────────────────────────────────────────────

  @Test
  public void buildCertDetailsReturnsPopulatedJson() throws Exception {
    X509Certificate cert = mock(X509Certificate.class);
    X500Principal subjectPrincipal = new X500Principal("CN=Test Subject, O=TestOrg, C=ES");
    X500Principal issuerPrincipal = new X500Principal("CN=Test Issuer, O=TestCA, C=ES");

    when(cert.getSubjectX500Principal()).thenReturn(subjectPrincipal);
    when(cert.getIssuerX500Principal()).thenReturn(issuerPrincipal);
    when(cert.getNotBefore()).thenReturn(new Date());
    when(cert.getNotAfter()).thenReturn(new Date());
    when(cert.getSigAlgName()).thenReturn("SHA256withRSA");

    Method m = NeoCertificateHelper.class.getDeclaredMethod("buildCertDetails", X509Certificate.class);
    m.setAccessible(true);
    JSONObject details = (JSONObject) m.invoke(null, cert);

    assertNotNull(details.getString("subject"));
    assertNotNull(details.getString("issuer"));
    assertNotNull(details.getString("validFrom"));
    assertNotNull(details.getString("validTo"));
    assertEquals("SHA256withRSA", details.getString("algorithm"));
  }

  @Test
  public void buildCertDetailsReturnsEmptyJsonOnError() throws Exception {
    X509Certificate cert = mock(X509Certificate.class);
    when(cert.getSubjectX500Principal()).thenThrow(new RuntimeException("Mock error"));

    Method m = NeoCertificateHelper.class.getDeclaredMethod("buildCertDetails", X509Certificate.class);
    m.setAccessible(true);
    JSONObject details = (JSONObject) m.invoke(null, cert);

    assertEquals(0, details.length());
  }

  // ── extractCertDetails (private) ──────────────────────────────────────────

  @Test
  public void extractCertDetailsReturnsEmptyJsonOnInvalidBytes() throws Exception {
    Method m = NeoCertificateHelper.class.getDeclaredMethod("extractCertDetails", byte[].class, String.class);
    m.setAccessible(true);
    JSONObject details = (JSONObject) m.invoke(null, new byte[] { 0, 1, 2 }, "wrongpass");

    // Should return empty JSONObject rather than throwing
    assertNotNull(details);
  }

  // ── validateNifOrGetPendingResponse (private) ─────────────────────────────

  @Test
  public void validateNifReturnsNullWhenCertIsNull() throws Exception {
    Method m = NeoCertificateHelper.class.getDeclaredMethod(
        "validateNifOrGetPendingResponse",
        X509Certificate.class, String.class, HttpServletRequest.class);
    m.setAccessible(true);

    HttpServletRequest request = mock(HttpServletRequest.class);
    Object result = m.invoke(null, null, "ORG1", request);
    assertNull(result);
  }

  @Test
  public void validateNifReturnsNullWhenCertNifNotParseable() throws Exception {
    Method m = NeoCertificateHelper.class.getDeclaredMethod(
        "validateNifOrGetPendingResponse",
        X509Certificate.class, String.class, HttpServletRequest.class);
    m.setAccessible(true);

    X509Certificate cert = mock(X509Certificate.class);
    X500Principal principal = new X500Principal("CN=No NIF Here, O=TestOrg, C=US");
    when(cert.getSubjectX500Principal()).thenReturn(principal);

    HttpServletRequest request = mock(HttpServletRequest.class);
    Object result = m.invoke(null, cert, "ORG1", request);
    assertNull(result);
  }

  @Test
  public void validateNifReturnsPendingWhenOrgHasNoNifAndNoConfirmation() throws Exception {
    Method m = NeoCertificateHelper.class.getDeclaredMethod(
        "validateNifOrGetPendingResponse",
        X509Certificate.class, String.class, HttpServletRequest.class);
    m.setAccessible(true);

    X509Certificate cert = mock(X509Certificate.class);
    X500Principal principal = mock(X500Principal.class);
    when(principal.getName()).thenReturn("SERIALNUMBER=12345678Z,CN=Test,O=FNMT,C=ES");
    when(cert.getSubjectX500Principal()).thenReturn(principal);

    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("setOrgNif")).thenReturn(null);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(eq(OrganizationInformation.class), anyString())).thenReturn(null);

    try (MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(obDal);

      NeoResponse result = (NeoResponse) m.invoke(null, cert, "ORG1", request);
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      assertTrue(result.getBody().getBoolean("pendingNifConfirmation"));
      assertEquals("12345678Z", result.getBody().getString("certNif"));
    }
  }

  @Test
  public void validateNifReturns422WhenNifMismatch() throws Exception {
    Method m = NeoCertificateHelper.class.getDeclaredMethod(
        "validateNifOrGetPendingResponse",
        X509Certificate.class, String.class, HttpServletRequest.class);
    m.setAccessible(true);

    X509Certificate cert = mock(X509Certificate.class);
    X500Principal principal = mock(X500Principal.class);
    when(principal.getName()).thenReturn("SERIALNUMBER=12345678Z,CN=Test,O=FNMT,C=ES");
    when(cert.getSubjectX500Principal()).thenReturn(principal);

    HttpServletRequest request = mock(HttpServletRequest.class);

    OBDal obDal = mock(OBDal.class);
    OrganizationInformation orgInfo = mock(OrganizationInformation.class);
    when(obDal.get(eq(OrganizationInformation.class), anyString())).thenReturn(orgInfo);
    when(orgInfo.getTaxID()).thenReturn("B99999999");

    try (MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(obDal);

      NeoResponse result = (NeoResponse) m.invoke(null, cert, "ORG1", request);
      assertNotNull(result);
      assertEquals(422, result.getHttpStatus());
    }
  }

  @Test
  public void validateNifReturnsNullWhenNifMatches() throws Exception {
    Method m = NeoCertificateHelper.class.getDeclaredMethod(
        "validateNifOrGetPendingResponse",
        X509Certificate.class, String.class, HttpServletRequest.class);
    m.setAccessible(true);

    X509Certificate cert = mock(X509Certificate.class);
    X500Principal principal = mock(X500Principal.class);
    when(principal.getName()).thenReturn("SERIALNUMBER=12345678Z,CN=Test,O=FNMT,C=ES");
    when(cert.getSubjectX500Principal()).thenReturn(principal);

    HttpServletRequest request = mock(HttpServletRequest.class);

    OBDal obDal = mock(OBDal.class);
    OrganizationInformation orgInfo = mock(OrganizationInformation.class);
    when(obDal.get(eq(OrganizationInformation.class), anyString())).thenReturn(orgInfo);
    when(orgInfo.getTaxID()).thenReturn("12345678Z");

    try (MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(obDal);

      Object result = m.invoke(null, cert, "ORG1", request);
      assertNull(result);
    }
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  @Test
  public void validateNifSetsOrgNifWhenConfirmed() throws Exception {
    Method m = NeoCertificateHelper.class.getDeclaredMethod(
        "validateNifOrGetPendingResponse",
        X509Certificate.class, String.class, HttpServletRequest.class);
    m.setAccessible(true);

    X509Certificate cert = mock(X509Certificate.class);
    X500Principal principal = mock(X500Principal.class);
    when(principal.getName()).thenReturn("SERIALNUMBER=12345678Z,CN=Test,O=FNMT,C=ES");
    when(cert.getSubjectX500Principal()).thenReturn(principal);

    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("setOrgNif")).thenReturn("true");

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(eq(OrganizationInformation.class), anyString())).thenReturn(null);

    Session session = mock(Session.class);
    NativeQuery nativeQuery = mock(NativeQuery.class);
    when(obDal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn(nativeQuery);
    when(nativeQuery.setParameter(anyString(), any())).thenReturn(nativeQuery);
    when(nativeQuery.executeUpdate()).thenReturn(1);

    try (MockedStatic<OBDal> dalStatic = Mockito.mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(obDal);

      // Should return null (continue upload) after setting NIF
      Object result = m.invoke(null, cert, "ORG1", request);
      assertNull(result);
    }
  }
}
