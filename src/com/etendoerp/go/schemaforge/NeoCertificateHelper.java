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
import java.lang.reflect.Method;
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
import org.openbravo.client.application.process.BaseProcessActionHandler;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.sif.general.process.AddCertificateToOrg;

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

  // RPJ certs (Representante Persona Jurídica): organizationIdentifier=VATES-A39200019
  // OID 2.5.4.97 — some JDK versions return the OID string instead of the attribute name.
  private static final Pattern NIF_ORG_ID_PATTERN = Pattern.compile(
      "(?:organizationIdentifier|OID\\.2\\.5\\.4\\.97)=[A-Z0-9-]*?" +
      "([A-Z][0-9]{7}[A-Z0-9]|[0-9]{8}[A-Z])",
      Pattern.CASE_INSENSITIVE);

  // Personal / autónomo certs: SERIALNUMBER=IDCES-12345678Z or VATID-ES12345678Z
  // Uses a lazy prefix so any alphanumeric-hyphen prefix is skipped before the 9-char NIF.
  private static final Pattern NIF_SERIAL_PATTERN = Pattern.compile(
      "(?:SERIALNUMBER|OID\\.2\\.5\\.4\\.5)=[A-Z0-9-]*?" +
      "([A-Z][0-9]{7}[A-Z0-9]|[0-9]{8}[A-Z])",
      Pattern.CASE_INSENSITIVE);

  // Fallback: "CN=NAME (R: A39200019)" — org NIF after "R:" inside CN
  private static final Pattern NIF_CN_R_PATTERN = Pattern.compile(
      "CN=[^,]*\\(R:\\s*([A-Z][0-9]{7}[A-Z0-9]|[0-9]{8}[A-Z])\\)",
      Pattern.CASE_INSENSITIVE);

  private NeoCertificateHelper() {
  }

  public static NeoResponse handleCertificateGet(HttpServletRequest request) {
    try {
      String orgId = request.getParameter("orgId");
      if (orgId == null || orgId.isBlank()) {
        return NeoResponse.error(400, "Required parameter: orgId");
      }
      var session = OBDal.getInstance().getSession();
      @SuppressWarnings("unchecked")
      var q = session.createNativeQuery(
          "SELECT expiration_date FROM etsg_certificate" +
          " WHERE ad_org_id = :orgId AND isactive = 'Y'" +
          " ORDER BY expiration_date DESC LIMIT 1");
      q.setParameter("orgId", orgId);
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
      return NeoResponse.error(500, e.getMessage());
    }
  }

  public static NeoResponse handleCertificateUpload(HttpServletRequest request) {
    try {
      String contentType = request.getContentType();
      if (contentType == null || !contentType.startsWith("multipart/")) {
        return NeoResponse.error(400, "Expected multipart/form-data");
      }

      Part filePart  = request.getPart("certificate");
      String orgId   = request.getParameter("orgId");
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
      X509Certificate primaryCert = null;
      try {
        primaryCert = parsePrimaryX509Cert(certBytes, password);
      } catch (Exception e) {
        log.debug("Pre-parse skipped (likely wrong password or corrupt file): {}", e.getMessage());
      }

      if (primaryCert != null) {
        String certNif = parseNifFromDn(primaryCert.getSubjectDN().getName());
        if (certNif != null) {
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
          } else if (!normalizeNif(certNif).equals(normalizeNif(orgNif))) {
            return NeoResponse.error(422,
                "Certificate NIF (" + certNif + ") does not match organisation NIF (" + orgNif + ").");
          }
        }
      }

      // Build parameters map expected by AddCertificateToOrg.doExecute()
      Map<String, Object> fileParams = new HashMap<>();
      fileParams.put("content", new ByteArrayInputStream(certBytes));
      fileParams.put("fileName", fileName);

      Map<String, Object> parameters = new HashMap<>();
      parameters.put(AddCertificateToOrg.PARAM_CERTIFICATE_FILE, fileParams);

      // Build content JSON expected by AddCertificateToOrg.doExecute()
      JSONObject params = new JSONObject();
      params.put(AddCertificateToOrg.PARAM_CERTIFICATE_PASSWORD, password);
      JSONObject content = new JSONObject();
      content.put(AddCertificateToOrg.PARAM_ORG_ID, orgId);
      content.put("_params", params);

      // Invoke protected doExecute via reflection (same pattern used by NeoProcessService)
      AddCertificateToOrg handler = new AddCertificateToOrg();
      Method doExecute = BaseProcessActionHandler.class
          .getDeclaredMethod("doExecute", Map.class, String.class);
      doExecute.setAccessible(true);
      JSONObject result = (JSONObject) doExecute.invoke(handler, parameters, content.toString());

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
      return NeoResponse.error(500, e.getMessage());
    }
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
   * Returns the tax ID configured for the given organisation, or null if not found.
   * Reads taxid directly from ad_orginfo (PK = ad_org_id).
   *
   * Uses a SAVEPOINT so a query failure cannot abort the caller's transaction.
   */
  private static String getOrgNif(String orgId) {
    var session = OBDal.getInstance().getSession();
    String sp = "nif_lookup";
    try {
      session.doWork(conn -> conn.createStatement().execute("SAVEPOINT " + sp));

      @SuppressWarnings("unchecked")
      var q = session.createNativeQuery(
          "SELECT taxid FROM ad_orginfo WHERE ad_org_id = :orgId AND isactive = 'Y'");
      q.setParameter("orgId", orgId);
      q.setMaxResults(1);
      Object result = q.uniqueResult();

      session.doWork(conn -> conn.createStatement().execute("RELEASE SAVEPOINT " + sp));
      if (result == null) return null;
      String taxid = result.toString().trim();
      return (taxid.isEmpty() || "?".equals(taxid)) ? null : taxid;
    } catch (Exception e) {
      try {
        session.doWork(conn -> conn.createStatement().execute("ROLLBACK TO SAVEPOINT " + sp));
      } catch (Exception ignored) { /* session already unusable */ }
      log.warn("Could not retrieve org NIF for orgId {}: {}", orgId, e.getMessage());
      return null;
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
          .setParameter("orgId", orgId)
          .executeUpdate();
    } catch (Exception e) {
      log.warn("Could not set org NIF for orgId {}: {}", orgId, e.getMessage());
    }
  }

  // ── PKCS#12 helpers ─────────────────────────────────────────────────────────

  private static X509Certificate parsePrimaryX509Cert(byte[] certBytes, String password)
      throws Exception {
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
      details.put("subject",   cert.getSubjectDN().getName());
      details.put("issuer",    cert.getIssuerDN().getName());
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
