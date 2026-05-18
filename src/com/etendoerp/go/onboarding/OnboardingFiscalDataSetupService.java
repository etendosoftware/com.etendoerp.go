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
package com.etendoerp.go.onboarding;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.module.sii.data.AEATSIIDescription;

/**
 * Seeds SII descriptions and TBAI territory endpoint configs for a newly created client.
 *
 * <p>AEATSII_DESCRIPTION records are created via OBDal. TBAI_DESTINY_CONFIG records are inserted
 * via JDBC because no Hibernate entity is registered for that table.
 */
public class OnboardingFiscalDataSetupService {

  private static final Logger log = LogManager.getLogger(OnboardingFiscalDataSetupService.class);

  private static final String TBAI_TABLE = "TBAI_DESTINY_CONFIG";
  private static final String SII_VENTAS = "Ventas";
  private static final String SII_COMPRAS = "Compras";
  private static final String DEVELOPER_NIF = "B75117705";
  private static final String LICENSE_NAME = "Etendo ERP";

  /**
   * Canonical TicketBAI territory endpoint data. Values are fixed per territory across all clients.
   */
  private enum Territory {
    ALAVA(
        "Alava",
        "https://ticketbai.araba.eus/TicketBAI/v1/facturas/",
        "https://ticketbai.araba.eus/TicketBAI/v1/anulaciones/",
        "https://ticketbai.araba.eus/tbai/qrtbai/",
        "https://pruebas-ticketbai.araba.eus/TicketBAI/v1/facturas/",
        "https://pruebas-ticketbai.araba.eus/TicketBAI/v1/anulaciones/",
        "https://pruebas-ticketbai.araba.eus/tbai/qrtbai/",
        "TBAIGI14637E782795A7", "TBAIARCgCKlgFKC00999",
        "1.0.0", "1.0.0",
        "com.smf.ticketbai.process.alava.AlavaSync",
        DEVELOPER_NIF, LICENSE_NAME, LICENSE_NAME,
        "https://ticketbai.araba.eus/tbai/sinadura/",
        null, null, null),
    BIZKAIA(
        "Bizkaia",
        "https://sarrerak.bizkaia.eus/N3B4000M/aurkezpena",
        "https://sarrerak.bizkaia.eus/N3B4000M/aurkezpena",
        "https://batuz.eus/QRTBAI/",
        "https://pruesarrerak.bizkaia.eus/N3B4000M/aurkezpena",
        "https://pruesarrerak.bizkaia.eus/N3B4000M/aurkezpena",
        "https://batuz.eus/QRTBAI/",
        "TBAIGI14637E782795A7", "TBAIBI00000000PRUEBA",
        "1.0.4", "1.0",
        "com.smf.ticketbai.process.bizkaia.BizkaiaSync",
        "A99800005", LICENSE_NAME, "SOFTWARE GARANTE TICKETBAI PRUEBA",
        "https://www.batuz.eus/fitxategiak/batuz/ticketbai/sinadura_elektronikoaren_zehaztapenak_especificaciones_de_la_firma_electronica_v1_1.pdf",
        "https://www.batuz.eus/fitxategiak/batuz/LROE/esquemas/LROE_PJ_240_1_1_FacturasEmitidas_ConSG_AltaPeticion_V1_0_2.xsd",
        "https://www.batuz.eus/fitxategiak/batuz/LROE/esquemas/LROE_PJ_240_1_1_FacturasEmitidas_ConSG_AnulacionPeticion_V1_0_0.xsd",
        "https://www.batuz.eus/fitxategiak/batuz/LROE/esquemas/LROE_PJ_240_2_FacturasRecibidas_AltaModifPeticion_V1_0_1.xsd"),
    GIPUZKOA(
        "Gipuzkoa",
        "https://tbai-z.egoitza.gipuzkoa.eus/sarrerak/alta",
        "https://tbai-z.egoitza.gipuzkoa.eus/sarrerak/baja",
        "https://tbai.egoitza.gipuzkoa.eus/qr/",
        "https://tbai-z.prep.gipuzkoa.eus/sarrerak/alta",
        "https://tbai-z.prep.gipuzkoa.eus/sarrerak/baja",
        "https://tbai.prep.gipuzkoa.eus/qr/",
        "TBAIGI14637E782795A7", "TBAIGIPRE00000000253",
        "1.0.4", "1.0.4",
        "com.smf.ticketbai.process.gipuzkoa.GipuzkoaSync",
        DEVELOPER_NIF, LICENSE_NAME, LICENSE_NAME,
        "https://www.gipuzkoa.eus/ticketbai/sinadura",
        null, null, null);

    final String name;
    final String urlRegister;
    final String urlCancel;
    final String urlQrBase;
    final String urlRegisterTest;
    final String urlCancelTest;
    final String urlQrBaseTest;
    final String tbaiLicense;
    final String tbaiLicenseTest;
    final String version;
    final String versionTest;
    final String className;
    final String developerNifTest;
    final String licenseName;
    final String licenseNameTest;
    final String policyUrl;
    final String schemaUri;
    final String schemaUriVoid;
    final String schemaUriPurchase;

    Territory(String name,
        String urlRegister, String urlCancel, String urlQrBase,
        String urlRegisterTest, String urlCancelTest, String urlQrBaseTest,
        String tbaiLicense, String tbaiLicenseTest,
        String version, String versionTest,
        String className,
        String developerNifTest, String licenseName, String licenseNameTest,
        String policyUrl,
        String schemaUri, String schemaUriVoid, String schemaUriPurchase) {
      this.name = name;
      this.urlRegister = urlRegister;
      this.urlCancel = urlCancel;
      this.urlQrBase = urlQrBase;
      this.urlRegisterTest = urlRegisterTest;
      this.urlCancelTest = urlCancelTest;
      this.urlQrBaseTest = urlQrBaseTest;
      this.tbaiLicense = tbaiLicense;
      this.tbaiLicenseTest = tbaiLicenseTest;
      this.version = version;
      this.versionTest = versionTest;
      this.className = className;
      this.developerNifTest = developerNifTest;
      this.licenseName = licenseName;
      this.licenseNameTest = licenseNameTest;
      this.policyUrl = policyUrl;
      this.schemaUri = schemaUri;
      this.schemaUriVoid = schemaUriVoid;
      this.schemaUriPurchase = schemaUriPurchase;
    }
  }

  /**
   * Creates SII descriptions and TBAI territory configs for the given client if not already present.
   *
   * @param clientId    target client identifier
   * @param orgId       target organization identifier
   * @param adminUserId administrator user for DAL context
   * @param adminRoleId administrator role for DAL context
   */
  public void setup(String clientId, String orgId, String adminUserId, String adminRoleId) {
    validateContext(clientId, orgId, adminUserId, adminRoleId);
    Object previousContext = captureCurrentContext();
    applyExecutionContext(adminUserId, adminRoleId, clientId, orgId);
    try {
      enterAdminMode();
      try {
        Client client = resolveClient(clientId);
        Organization org = resolveOrganization(orgId);
        if (client == null) {
          throw new OBException("Client not found for fiscal data setup: " + clientId);
        }
        if (org == null) {
          throw new OBException("Organization not found for fiscal data setup: " + orgId);
        }
        createSiiDescriptionsIfAbsent(client, org);
        createTbaiDestinyConfigsIfAbsent(clientId);
        flushChanges();
      } finally {
        exitAdminMode();
      }
    } finally {
      restoreExecutionContext(previousContext);
    }
  }

  protected void createSiiDescriptionsIfAbsent(Client client, Organization org) {
    if (siiDescriptionsExist(client)) {
      log.debug("SII descriptions already exist for client {}, skipping", client.getId());
      return;
    }
    saveSiiDescription(buildSiiDescription(client, org, SII_VENTAS, true, false));
    saveSiiDescription(buildSiiDescription(client, org, SII_COMPRAS, false, true));
  }

  protected void createTbaiDestinyConfigsIfAbsent(String clientId) {
    if (tbaiDestinyConfigsExist(clientId)) {
      log.debug("TBAI destiny configs already exist for client {}, skipping", clientId);
      return;
    }
    insertTbaiDestinyConfigs(clientId);
  }

  protected void insertTbaiDestinyConfigs(String clientId) {
    Connection conn = getConnection();
    for (Territory territory : Territory.values()) {
      insertTbaiDestinyConfigRow(conn, clientId, territory);
    }
  }

  protected AEATSIIDescription buildSiiDescription(Client client, Organization org,
      String name, boolean isSales, boolean isPurchase) {
    AEATSIIDescription desc = OBProvider.getInstance().get(AEATSIIDescription.class);
    desc.setNewOBObject(true);
    desc.setClient(client);
    desc.setOrganization(org);
    desc.setActive(true);
    desc.setName(name);
    desc.setDescription(name);
    desc.setSales(isSales);
    desc.setPurchase(isPurchase);
    desc.setDefault(true);
    return desc;
  }

  private void insertTbaiDestinyConfigRow(Connection conn, String clientId, Territory t) {
    String id = UUID.randomUUID().toString().replace("-", "").toUpperCase();
    Timestamp now = Timestamp.from(Instant.now());
    String sql = "INSERT INTO TBAI_DESTINY_CONFIG ("
        + "TBAI_DESTINY_CONFIG_ID, AD_CLIENT_ID, AD_ORG_ID, ISACTIVE, CREATED, CREATEDBY, UPDATED, UPDATEDBY, "
        + "NAME, URL_REGISTER, URL_CANCEL, URL_QR_BASE, URL_REGISTER_TEST, URL_CANCEL_TEST, URL_QR_BASE_TEST, "
        + "TBAI_LICENSE, TBAI_LICENSE_TEST, DEVELOPER_NIF, VERSION, CLASSNAME, "
        + "DEVELOPER_NIF_TEST, VERSION_TEST, LICENSE_NAME, LICENSE_NAME_TEST, POLICY_URL, "
        + "SCHEMA_URI, SCHEMA_URI_VOID, SCHEMA_URI_PURCHASE) "
        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, id);
      ps.setString(2, clientId);
      ps.setString(3, "0");
      ps.setString(4, "Y");
      ps.setTimestamp(5, now);
      ps.setString(6, "0");
      ps.setTimestamp(7, now);
      ps.setString(8, "0");
      ps.setString(9, t.name);
      ps.setString(10, t.urlRegister);
      ps.setString(11, t.urlCancel);
      ps.setString(12, t.urlQrBase);
      ps.setString(13, t.urlRegisterTest);
      ps.setString(14, t.urlCancelTest);
      ps.setString(15, t.urlQrBaseTest);
      ps.setString(16, t.tbaiLicense);
      ps.setString(17, t.tbaiLicenseTest);
      ps.setString(18, DEVELOPER_NIF);
      ps.setString(19, t.version);
      ps.setString(20, t.className);
      ps.setString(21, t.developerNifTest);
      ps.setString(22, t.versionTest);
      ps.setString(23, t.licenseName);
      ps.setString(24, t.licenseNameTest);
      ps.setString(25, t.policyUrl);
      ps.setString(26, t.schemaUri);
      ps.setString(27, t.schemaUriVoid);
      ps.setString(28, t.schemaUriPurchase);
      ps.executeUpdate();
    } catch (Exception e) {
      throw new OBException("Failed to insert TBAI_DESTINY_CONFIG for territory " + t.name, e);
    }
  }

  protected boolean siiDescriptionsExist(Client client) {
    OBCriteria<AEATSIIDescription> criteria = OBDal.getInstance()
        .createCriteria(AEATSIIDescription.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(AEATSIIDescription.PROPERTY_CLIENT, client));
    criteria.setMaxResults(1);
    return criteria.uniqueResult() != null;
  }

  protected boolean tbaiDestinyConfigsExist(String clientId) {
    String sql = "SELECT COUNT(*) FROM " + TBAI_TABLE + " WHERE AD_CLIENT_ID = ?";
    try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
      ps.setString(1, clientId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getLong(1) > 0;
      }
    } catch (Exception e) {
      throw new OBException("Failed to check TBAI_DESTINY_CONFIG existence for client " + clientId, e);
    }
  }

  protected Client resolveClient(String clientId) {
    return OBDal.getInstance().get(Client.class, clientId);
  }

  protected Organization resolveOrganization(String orgId) {
    return OBDal.getInstance().get(Organization.class, orgId);
  }

  protected void saveSiiDescription(AEATSIIDescription desc) {
    OBDal.getInstance().save(desc);
  }

  protected void flushChanges() {
    OBDal.getInstance().flush();
  }

  protected Connection getConnection() {
    return OBDal.getInstance().getConnection();
  }

  protected Object captureCurrentContext() {
    return OBContext.getOBContext();
  }

  protected void applyExecutionContext(String adminUserId, String adminRoleId,
      String clientId, String orgId) {
    OBContext.setOBContext(adminUserId, adminRoleId, clientId, orgId);
  }

  protected void restoreExecutionContext(Object previousContext) {
    OBContext.setOBContext((OBContext) previousContext);
  }

  protected void enterAdminMode() {
    OBContext.setAdminMode(true);
  }

  protected void exitAdminMode() {
    OBContext.restorePreviousMode();
  }

  private void validateContext(String clientId, String orgId, String adminUserId,
      String adminRoleId) {
    if (clientId == null || clientId.isEmpty()) {
      throw new OBException("Missing client for fiscal data setup");
    }
    if (orgId == null || orgId.isEmpty()) {
      throw new OBException("Missing organization for fiscal data setup");
    }
    if (adminUserId == null || adminUserId.isEmpty()) {
      throw new OBException("Missing admin user for fiscal data setup");
    }
    if (adminRoleId == null || adminRoleId.isEmpty()) {
      throw new OBException("Missing admin role for fiscal data setup");
    }
  }
}
