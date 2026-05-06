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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.client.application.process.BaseProcessActionHandler;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.enterprise.OrganizationInformation;

import com.etendoerp.sif.general.data.DigitalCertificate;
import com.etendoerp.sif.general.process.AddCertificateToOrg;
import com.etendoerp.sif.general.utility.SifGeneralUtils;

/**
 * Handles POST /sws/neo/certificate — uploads a PKCS#12 certificate for an organization.
 *
 * Delegates to {@link AddCertificateToOrg} (the existing classic-UI process) via reflection,
 * then enriches the response with the certificate details parsed from the uploaded bytes.
 *
 * Expected multipart/form-data fields:
 *   certificate  — the .p12 / .pfx file
 *   orgId        — AD_Org_ID of the target organization
 *   password     — plaintext certificate password
 */
public class NeoCertificateHelper {

  private static final Logger log = LogManager.getLogger(NeoCertificateHelper.class);
  private static final String DATE_FORMAT = "dd/MM/yyyy";
  private static final String PARAM_ORG_ID = "orgId";

  // RPJ certs (Representante Persona Jurídica): organizationIdentifier=VATES-A39200019
  // OID 2.5.4.97 — some JDK versions return the OID string instead of the attribute name.
  private static final Pattern NIF_ORG_ID_PATTERN = Pattern.compile(
      "(?:organizationIdentifier|OID\\.2\\.5\\.4\\.97)=[A-Z\\d-]*?" +
      "([A-Z]\\d{7}[A-Z\\d]|\\d{8}[A-Z])",
      Pattern.CASE_INSENSITIVE);

  // Personal / autónomo certs: SERIALNUMBER=IDCES-12345678Z or VATID-ES12345678Z
  // Uses a lazy prefix so any alphanumeric-hyphen prefix is skipped before the 9-char NIF.
  private static final Pattern NIF_SERIAL_PATTERN = Pattern.compile(
      "(?:SERIALNUMBER|OID\\.2\\.5\\.4\\.5)=[A-Z\\d-]*?" +
      "([A-Z]\\d{7}[A-Z\\d]|\\d{8}[A-Z])",
      Pattern.CASE_INSENSITIVE);

  // Fallback: "CN=NAME (R: A39200019)" — org NIF after "R:" inside CN
  private static final Pattern NIF_CN_R_PATTERN = Pattern.compile(
      "CN=[^,]*\\(R:\\s*([A-Z]\\d{7}[A-Z\\d]|\\d{8}[A-Z])\\)",
      Pattern.CASE_INSENSITIVE);

  private NeoCertificateHelper() {
  }

  /**
   * Handles GET /sws/neo/certificate — returns the active certificate status for an organization.
   *
   * @param request HTTP request; must include {@code orgId} query parameter
   * @return NeoResponse with {@code exists} boolean and {@code validTo} date when found
   */
  public static NeoResponse handleCertificateGet(HttpServletRequest request) {
    try {
      String orgId = request.getParameter(PARAM_ORG_ID);
      if (orgId == null || orgId.isBlank()) {
        return NeoResponse.error(400, "Required parameter: orgId");
      }
      var session = OBDal.getInstance().getSession();
      @SuppressWarnings("unchecked")
      var q = session.createNativeQuery(
          "SELECT expiration_date FROM etsg_certificate" +
          " WHERE ad_org_id = :orgId AND isactive = 'Y'" +
          " ORDER BY expiration_date DESC LIMIT 1");
      q.setParameter(PARAM_ORG_ID, orgId);
      Object row = q.uniqueResult();
      JSONObject resp = new JSONObject();
      if (row != null) {
        resp.put("exists", true);
        SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT);
        resp.put("validTo", sdf.format(row));
      } else {
        resp.put("exists", false);
      }
      return NeoResponse.ok(resp);
    } catch (Exception e) {
      log.error("Certificate GET failed", e);
      return NeoResponse.error(500, "Internal error retrieving certificate status");
    }
  }

  /**
   * Handles DELETE /sws/neo/certificate?orgId=... — removes certificate records for an organization.
   *
   * Deletes the certificate attachments first, then removes the certificate rows themselves.
   *
   * @param request HTTP request; must include {@code orgId} query parameter
   * @return NeoResponse with deleted count
   */
  public static NeoResponse handleCertificateDelete(HttpServletRequest request) {
    try {
      String orgId = request.getParameter(PARAM_ORG_ID);
      if (orgId == null || orgId.isBlank()) {
        return NeoResponse.error(400, "Required parameter: orgId");
      }

      int deleted = deleteCertificatesForOrg(orgId);
      OBDal.getInstance().flush();

      JSONObject resp = new JSONObject();
      resp.put("deleted", deleted);
      return NeoResponse.ok(resp);
    } catch (Exception e) {
      log.error("Certificate DELETE failed", e);
      return NeoResponse.error(500, "Internal error deleting certificates");
    }
  }

  /**
   * Handles POST /sws/neo/certificate — uploads a PKCS#12 certificate for an organization.
   *
   * Validates the NIF embedded in the certificate against the organisation's registered NIF.
   * When the organisation has no NIF, returns a {@code pendingNifConfirmation} prompt so the
   * client can ask the user to confirm; re-post with {@code setOrgNif=true} to apply.
   *
   * @param request HTTP multipart/form-data request with fields: certificate, orgId, password
   * @return NeoResponse with cert details on success, or error with HTTP status
   */
  public static NeoResponse handleCertificateUpload(HttpServletRequest request) {
    try {
      String contentType = request.getContentType();
      if (contentType == null || !contentType.startsWith("multipart/")) {
        return NeoResponse.error(400, "Expected multipart/form-data");
      }

      Part filePart   = request.getPart("certificate");
      String orgId    = request.getParameter(PARAM_ORG_ID);
      String password = request.getParameter("password");

      if (filePart == null || orgId == null || orgId.isBlank()
          || password == null || password.isBlank()) {
        return NeoResponse.error(400, "Required fields: certificate (file), orgId, password");
      }

      String fileName = resolveFileName(filePart);
      byte[] certBytes = filePart.getInputStream().readAllBytes();

      // Pre-parse the cert to validate NIF before invoking the store process.
      // If parsing fails (wrong password, corrupt file) we skip NIF check and let
      // AddCertificateToOrg return the appropriate user-facing error.
      X509Certificate primaryCert = tryParseCert(certBytes, password);

      NeoResponse nifCheck = validateNifOrGetPendingResponse(primaryCert, orgId, request);
      if (nifCheck != null) return nifCheck;

      JSONObject result = invokeCertificateProcess(certBytes, fileName, orgId, password);

      JSONObject message = result.optJSONObject("message");
      if (message != null && "error".equals(message.optString("severity"))) {
        return NeoResponse.error(400, message.optString("text", "Certificate upload failed"));
      }

      // Enrich response with cert details (reuse pre-parsed cert when available)
      JSONObject certDetails = primaryCert != null
          ? buildCertDetails(primaryCert)
          : extractCertDetails(certBytes, password);
      result.put("cert", certDetails);
      return NeoResponse.ok(result);

    } catch (Exception e) {
      log.error("Certificate upload failed", e);
      return NeoResponse.error(500, "Internal error processing certificate upload");
    }
  }

  /**
   * Attempts to parse the primary certificate from PKCS#12 bytes.
   * Returns null if parsing fails (wrong password, corrupt file) so the caller
   * can proceed and let AddCertificateToOrg surface the appropriate error.
   */
  private static X509Certificate tryParseCert(byte[] certBytes, String password) {
    try {
      return parsePrimaryX509Cert(certBytes, password);
    } catch (Exception e) {
      log.debug("Pre-parse skipped (likely wrong password or corrupt file): {}", e.getMessage());
      return null;
    }
  }

  /**
   * Validates the NIF embedded in the certificate against the organisation's NIF.
   *
   * Returns a {@link NeoResponse} when the caller should stop and return early:
   *   - {@code pendingNifConfirmation} prompt when the org has no NIF and confirmation is pending.
   *   - 422 error when the cert NIF does not match the org NIF.
   * Returns null when validation passes and the upload should continue.
   */
  private static NeoResponse validateNifOrGetPendingResponse(X509Certificate cert,
      String orgId, HttpServletRequest request) throws Exception {
    if (cert == null) return null;
    String certNif = parseNifFromDn(cert.getSubjectX500Principal().getName());
    if (certNif == null) return null;
    String orgNif = getOrgNif(orgId);
    if (orgNif == null) {
      // Org has no NIF configured — ask the user to confirm using the cert NIF.
      // If they confirmed (setOrgNif=true), write it now and proceed.
      if (!"true".equals(request.getParameter("setOrgNif"))) {
        JSONObject pending = new JSONObject();
        pending.put("pendingNifConfirmation", true);
        pending.put("certNif", certNif);
        return NeoResponse.ok(pending);
      }
      setOrgNifInDb(orgId, certNif);
      return null;
    }
    if (!normalizeNif(certNif).equals(normalizeNif(orgNif))) {
      return NeoResponse.error(422,
          "Certificate NIF (" + certNif + ") does not match organisation NIF (" + orgNif + ").");
    }
    return null;
  }

  // ── NIF extraction ──────────────────────────────────────────────────────────

  /**
   * Extracts the organisation NIF/CIF from an X.509 subject DN string.
   *
   * Priority:
   *   1. organizationIdentifier (VATES-A39200019) — RPJ certs; this IS the org's CIF.
   *   2. serialNumber / OID 2.5.4.5 — personal / autónomo certs; the person's NIF equals the org NIF.
   *   3. CN "(R: A39200019)" — older FNMT format.
   *
   * Returns null if no recognisable NIF pattern is found.
   */
  static String parseNifFromDn(String dn) {
    if (dn == null) return null;
    Matcher m1 = NIF_ORG_ID_PATTERN.matcher(dn);
    if (m1.find()) return m1.group(1).toUpperCase(Locale.ROOT);
    Matcher m2 = NIF_SERIAL_PATTERN.matcher(dn);
    if (m2.find()) return m2.group(1).toUpperCase(Locale.ROOT);
    Matcher m3 = NIF_CN_R_PATTERN.matcher(dn);
    if (m3.find()) return m3.group(1).toUpperCase(Locale.ROOT);
    return null;
  }

  /**
   * Normalises a NIF/CIF for comparison: uppercase, strips hyphens and spaces.
   */
  static String normalizeNif(String nif) {
    return nif.toUpperCase(Locale.ROOT).replaceAll("[-\\s]", "");
  }

  // ── DB helpers ──────────────────────────────────────────────────────────────

  /**
   * Returns the tax ID configured for the given organisation, or null if not found or blank.
   */
  private static String getOrgNif(String orgId) {
    try {
      OrganizationInformation orgInfo = OBDal.getInstance().get(OrganizationInformation.class, orgId);
      if (orgInfo == null) return null;
      String taxid = orgInfo.getTaxID();
      if (taxid == null) return null;
      taxid = taxid.trim();
      return (taxid.isEmpty() || "?".equals(taxid)) ? null : taxid;
    } catch (Exception e) {
      log.warn("Could not retrieve org NIF for orgId {}: {}", orgId, e.getMessage());
      return null;
    }
  }

  private static int deleteCertificatesForOrg(String orgId) {
    OBCriteria<DigitalCertificate> criteria = OBDal.getInstance().createCriteria(DigitalCertificate.class);
    criteria.add(org.hibernate.criterion.Restrictions.eq(DigitalCertificate.PROPERTY_ORGANIZATION + ".id", orgId));

    int deleted = 0;
    for (DigitalCertificate certificate : criteria.list()) {
      deleteCertificateAttachment(certificate.getId());
      OBDal.getInstance().remove(certificate);
      deleted++;
    }
    return deleted;
  }

  private static void deleteCertificateAttachment(String certificateId) {
    OBCriteria<Attachment> attachCrit = OBDal.getInstance().createCriteria(Attachment.class);
    attachCrit.add(org.hibernate.criterion.Restrictions.eq(Attachment.PROPERTY_RECORD, certificateId));
    for (Attachment attachment : attachCrit.list()) {
      SifGeneralUtils.attachManager.delete(attachment);
    }
  }

  /**
   * Writes the given NIF into ad_orginfo.taxid for the specified organisation.
   * Called only when the user explicitly confirms using the cert NIF.
   */
  private static void setOrgNifInDb(String orgId, String nif) {
    try {
      OBDal.getInstance().getSession()
          .createNativeQuery("UPDATE ad_orginfo SET taxid = :nif WHERE ad_org_id = :orgId")
          .setParameter("nif", nif)
          .setParameter(PARAM_ORG_ID, orgId)
          .executeUpdate();
    } catch (OBException e) {
      log.warn("Could not set org NIF for orgId {}: {}", orgId, e.getMessage());
    }
  }

  // ── PKCS#12 helpers ─────────────────────────────────────────────────────────

  /**
   * Invokes {@link AddCertificateToOrg#doExecute} via reflection to store the certificate.
   */
  private static JSONObject invokeCertificateProcess(byte[] certBytes, String fileName,
      String orgId, String password) throws Exception {
    Map<String, Object> fileParams = new HashMap<>();
    fileParams.put("content", new ByteArrayInputStream(certBytes));
    fileParams.put("fileName", fileName);

    Map<String, Object> parameters = new HashMap<>();
    parameters.put(AddCertificateToOrg.PARAM_CERTIFICATE_FILE, fileParams);

    JSONObject params = new JSONObject();
    params.put(AddCertificateToOrg.PARAM_CERTIFICATE_PASSWORD, password);
    JSONObject content = new JSONObject();
    content.put(AddCertificateToOrg.PARAM_ORG_ID, orgId);
    content.put("_params", params);

    AddCertificateToOrg handler = new AddCertificateToOrg();
    Method doExecute = BaseProcessActionHandler.class
        .getDeclaredMethod("doExecute", Map.class, String.class);
    doExecute.setAccessible(true);
    return (JSONObject) doExecute.invoke(handler, parameters, content.toString());
  }

  private static X509Certificate parsePrimaryX509Cert(byte[] certBytes, String password)
      throws GeneralSecurityException, IOException {
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (ByteArrayInputStream bais = new ByteArrayInputStream(certBytes)) {
      ks.load(bais, password.toCharArray());
    }
    Enumeration<String> aliases = ks.aliases();
    while (aliases.hasMoreElements()) {
      X509Certificate cert = (X509Certificate) ks.getCertificate(aliases.nextElement());
      if (cert != null) return cert;
    }
    return null;
  }

  private static JSONObject buildCertDetails(X509Certificate cert) {
    try {
      SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT);
      JSONObject details = new JSONObject();
      details.put("subject",   cert.getSubjectX500Principal().getName());
      details.put("issuer",    cert.getIssuerX500Principal().getName());
      details.put("validFrom", sdf.format(cert.getNotBefore()));
      details.put("validTo",   sdf.format(cert.getNotAfter()));
      details.put("algorithm", cert.getSigAlgName());
      return details;
    } catch (Exception e) {
      log.warn("Could not build cert details: {}", e.getMessage());
      return new JSONObject();
    }
  }

  private static JSONObject extractCertDetails(byte[] certBytes, String password) {
    try {
      X509Certificate cert = parsePrimaryX509Cert(certBytes, password);
      return cert != null ? buildCertDetails(cert) : new JSONObject();
    } catch (Exception e) {
      log.warn("Could not extract cert details for display: {}", e.getMessage());
      return new JSONObject();
    }
  }

  private static String resolveFileName(Part part) {
    String disposition = part.getHeader("Content-Disposition");
    if (disposition != null) {
      for (String segment : disposition.split(";")) {
        segment = segment.trim();
        if (segment.startsWith("filename")) {
          return segment.substring(segment.indexOf('=') + 1).trim().replace("\"", "");
        }
      }
    }
    return "certificate.p12";
  }
}
