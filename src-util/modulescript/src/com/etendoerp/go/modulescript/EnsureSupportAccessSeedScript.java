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
package com.etendoerp.go.modulescript;

import java.sql.PreparedStatement;

import org.openbravo.database.ConnectionProvider;
import org.openbravo.modulescript.ModuleScript;

/**
 * ETP-5351 — seeds the two System-level ({@code AD_Client_ID = '0'}) records that Etendo GO
 * support access needs and that sourcedata cannot carry:
 * <ul>
 *   <li>the <b>"Soporte Etendo GO"</b> role ({@code UserLevel 'S'}, manual), plus its
 *       {@code AD_Role_OrgAccess} on organization {@code 0} so an operator can log into Classic
 *       with it. {@code AD_Role} is not part of the Application Dictionary dataset, which is why
 *       this is a module script and not an {@code AD_ROLE.xml} (same reason as
 *       {@link EnsureSystemRoleTemplatesScript});</li>
 *   <li>the <b>technical support account</b> in {@code ETGO_ACCOUNT}: every support session
 *       ({@code ETGO_GO_SESSION.ETGO_ACCOUNT_ID} is mandatory) hangs from it. It is active, so
 *       session resolution finds it, but it has no password, an undeliverable {@code .invalid}
 *       email and {@code STATUS = 'pending'}, so it cannot log in.</li>
 * </ul>
 *
 * <p>It also grants the role the Classic "Support · Companies" window and its two processes
 * ("Access as Support", "Close Support Session"), and seeds their es_ES labels and messages.
 * Grants and labels are INSERTs/UPDATEs, so they travel in the data delta as well.</p>
 *
 * <p>Other System roles (System Administrator) may still get the window through core's access
 * propagation; that is accepted. The processes require explicit access, granted here to the
 * support role only, so no other role can run them.</p>
 *
 * <p><b>es_ES labels.</b> The module's base language is en_US and it ships no translation module,
 * so the Spanish texts of the window, tab, menu, fields, processes, parameters, list values and
 * messages are written into the {@code _TRL} tables here, only when {@code es_ES} is a system
 * language. A translation edited by a user (updated by someone other than System and marked as
 * translated) is never overwritten.</p>
 *
 * <p><b>Resolve these records by id or by {@code IS_SUPPORT_ACCOUNT = 'Y'}, never by email or
 * name.</b> Every insert is {@code ON CONFLICT DO NOTHING}: if an environment already has a
 * same-named role or an account with the support email (created by hand, or registered before
 * this script ran), the seed is skipped instead of aborting {@code update.database}, and code
 * that looks the record up by its fixed id fails closed.</p>
 *
 * <p>Runs on every {@code update.database}; idempotent. It also re-asserts, on every run, that
 * the technical account carries no credential (password, session token, reset or verification
 * token, SSO identity): an UPDATE, so it travels in the data delta the deploy pipeline ships.
 * The {@code ETGO_ACCOUNT_IDENTITY} child rows are left to the runtime hardening (ETP-5351 T5),
 * because DELETEs never reach production through that delta.</p>
 *
 * <p>Self-contained on purpose: no import from this module's {@code src/} tree, for the deploy
 * ordering reason explained in {@link EnsureSystemRoleTemplatesScript}. Keep the ids below in
 * sync with whatever {@code src/} constant T2–T5 introduce for them.</p>
 */
public class EnsureSupportAccessSeedScript extends ModuleScript {

  private static final String SYSTEM_CLIENT_ID = "0";
  private static final String STAR_ORG_ID = "0";
  private static final String SYSTEM_USER_ID = "0";

  /** {@code AD_Role_ID} of the "Soporte Etendo GO" System role. */
  static final String SUPPORT_ROLE_ID = "E70BA0AAD9964AC4909099444C334BD2";
  static final String SUPPORT_ROLE_NAME = "Soporte Etendo GO";
  private static final String SUPPORT_ROLE_ORG_ACCESS_ID = "ADE0BDACDB174B7D8E27C1C9CF266124";
  /** {@code AD_Role.UserLevel} for a System-only role. */
  private static final String SYSTEM_USER_LEVEL = "S";

  /** {@code ETGO_Account_ID} of the technical account every support session hangs from. */
  static final String SUPPORT_ACCOUNT_ID = "76EE310B5C1F481499BFEEF69A7569C8";
  static final String SUPPORT_ACCOUNT_EMAIL = "soporte@etendo-go.invalid";
  private static final String SUPPORT_ACCOUNT_NAME = "Soporte Etendo";
  /** No login is possible on a pending account without a password hash. */
  private static final String SUPPORT_ACCOUNT_STATUS = "pending";

  private static final String INSERT_ROLE_SQL = "INSERT INTO AD_Role (AD_Role_ID, AD_Client_ID, "
      + "AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy, Name, Description, "
      + "UserLevel, IsManual, Is_Client_Admin, IsAdvanced, IsRestrictBackend, IsPortal, "
      + "IsPortalAdmin, IsWebServiceEnabled, IsTemplate, EM_ETGO_Show_Acct_Fields) "
      + "VALUES (?, ?, ?, 'Y', now(), ?, now(), ?, ?, ?, ?, 'Y', 'N', 'N', 'N', 'N', 'N', 'N', "
      + "'N', 'N') ON CONFLICT DO NOTHING";

  private static final String INSERT_ROLE_ORG_ACCESS_SQL = "INSERT INTO AD_Role_OrgAccess "
      + "(AD_Role_OrgAccess_ID, AD_Role_ID, AD_Org_ID, AD_Client_ID, IsActive, Created, "
      + "CreatedBy, Updated, UpdatedBy, Is_Org_Admin) "
      + "SELECT ?, r.AD_Role_ID, ?, ?, 'Y', now(), ?, now(), ?, 'N' FROM AD_Role r "
      + "WHERE r.AD_Role_ID = ? ON CONFLICT DO NOTHING";

  private static final String INSERT_ACCOUNT_SQL = "INSERT INTO etgo_account (etgo_account_id, "
      + "ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby, email, "
      + "password_hash, name, status, is_support_account) "
      + "VALUES (?, ?, ?, 'Y', now(), ?, now(), ?, ?, NULL, ?, ?, 'Y') ON CONFLICT DO NOTHING";

  private static final String SCRUB_ACCOUNT_SQL = "UPDATE etgo_account SET password_hash = NULL, "
      + "session_token = NULL, reset_token_hash = NULL, reset_token_expires = NULL, "
      + "verify_token_hash = NULL, verify_token_expires = NULL, auth_provider = NULL, "
      + "external_subject = NULL, external_email = NULL, status = ?, is_support_account = 'Y', "
      + "updated = now(), updatedby = ? "
      + "WHERE etgo_account_id = ? AND (password_hash IS NOT NULL OR session_token IS NOT NULL "
      + "OR reset_token_hash IS NOT NULL OR verify_token_hash IS NOT NULL "
      + "OR auth_provider IS NOT NULL OR external_subject IS NOT NULL OR status <> ? "
      + "OR is_support_account <> 'Y')";

  /** {@code AD_Window_ID} of "Support · Companies" (keep in sync with the window sourcedata). */
  static final String SUPPORT_WINDOW_ID = "9010058657404BEDA7795C3AAA712FC8";
  /** {@code OBUIAPP_Process_ID} of "Access as Support". */
  static final String ISSUE_PROCESS_ID = "417F4143D1EB4AE39A5F89E33675001E";
  /** {@code OBUIAPP_Process_ID} of "Close Support Session". */
  static final String REVOKE_PROCESS_ID = "1081A92E729541DEBDB6BE46157A93CA";
  private static final String SUPPORT_WINDOW_ACCESS_ID = "3111177B7717427CAE3B09C3C6EB3987";
  private static final String ISSUE_PROCESS_ACCESS_ID = "E3739808C04747F8BEAE0F4E1F71734D";
  private static final String REVOKE_PROCESS_ACCESS_ID = "81ED2D7D6DA846D3B1F2E2E114093DD7";

  /** Language of the seeded translations. */
  static final String SPANISH = "es_ES";

  private static final String INSERT_WINDOW_ACCESS_SQL = "INSERT INTO ad_window_access "
      + "(ad_window_access_id, ad_window_id, ad_role_id, ad_client_id, ad_org_id, isactive, "
      + "created, createdby, updated, updatedby, isreadwrite) "
      + "SELECT ?, w.ad_window_id, r.ad_role_id, ?, ?, 'Y', now(), ?, now(), ?, 'Y' "
      + "FROM ad_window w, ad_role r WHERE w.ad_window_id = ? AND r.ad_role_id = ? "
      + "ON CONFLICT DO NOTHING";

  private static final String INSERT_PROCESS_ACCESS_SQL = "INSERT INTO obuiapp_process_access "
      + "(obuiapp_process_access_id, obuiapp_process_id, ad_role_id, ad_client_id, ad_org_id, "
      + "isactive, created, createdby, updated, updatedby, isreadwrite) "
      + "SELECT ?, p.obuiapp_process_id, r.ad_role_id, ?, ?, 'Y', now(), ?, now(), ?, 'Y' "
      + "FROM obuiapp_process p, ad_role r WHERE p.obuiapp_process_id = ? AND r.ad_role_id = ? "
      + "AND NOT EXISTS (SELECT 1 FROM obuiapp_process_access a "
      + "WHERE a.obuiapp_process_id = p.obuiapp_process_id AND a.ad_role_id = r.ad_role_id) "
      + "ON CONFLICT DO NOTHING";

  /** The {@code _TRL} tables this script writes, with the column holding the translated text. */
  enum TrlTable {
    WINDOW("ad_window", TrlTable.NAME),
    TAB("ad_tab", TrlTable.NAME),
    MENU("ad_menu", TrlTable.NAME),
    FIELD("ad_field", TrlTable.NAME),
    PROCESS("obuiapp_process", TrlTable.NAME),
    PARAMETER("obuiapp_parameter", TrlTable.NAME),
    REF_LIST("ad_ref_list", TrlTable.NAME),
    MESSAGE("ad_message", "msgtext");

    private static final String NAME = "name";

    /** Parameters: text, language, parent id. Inserts only when the language is a system one. */
    final String insertSql;
    /** Parameters: text, parent id, language, text. Skips translations edited by a user. */
    final String updateSql;

    TrlTable(String table, String textColumn) {
      String trl = table + "_trl";
      String idColumn = table + "_id";
      this.insertSql = "INSERT INTO " + trl + " (" + trl + "_id, " + idColumn + ", ad_language, "
          + "ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby, "
          + textColumn + ", istranslated) "
          + "SELECT get_uuid(), p." + idColumn + ", l.ad_language, '0', '0', 'Y', now(), '0', "
          + "now(), '0', ?, 'Y' FROM " + table + " p JOIN ad_language l "
          + "ON l.ad_language = ? AND l.issystemlanguage = 'Y' "
          + "WHERE p." + idColumn + " = ? ON CONFLICT DO NOTHING";
      this.updateSql = "UPDATE " + trl + " SET " + textColumn + " = ?, istranslated = 'Y', "
          + "updated = now(), updatedby = '0' WHERE " + idColumn + " = ? AND ad_language = ? "
          + "AND (istranslated = 'N' OR updatedby = '0') AND " + textColumn
          + " IS DISTINCT FROM ?";
    }
  }

  /** One es_ES text. */
  static final class Translation {
    final TrlTable table;
    final String id;
    final String text;

    Translation(TrlTable table, String id, String text) {
      this.table = table;
      this.id = id;
      this.text = text;
    }
  }

  private static final String ACCESS_AS_SUPPORT_ES = "Acceder como soporte";
  private static final String CLOSE_SUPPORT_SESSION_ES = "Cerrar sesión de soporte";
  private static final String SUPPORT_COMPANIES_ES = "Soporte · Empresas";
  private static final String CLOSE_OR_WAIT_ES = "Cierra esa sesión de soporte o espera a que "
      + "termine.";

  /** The es_ES labels and messages of the support window and processes. */
  static final Translation[] SPANISH_TEXTS = {
      new Translation(TrlTable.WINDOW, SUPPORT_WINDOW_ID, SUPPORT_COMPANIES_ES),
      new Translation(TrlTable.TAB, "D72AC83A291D4D2099B9B9AAC1041622", "Empresas"),
      new Translation(TrlTable.MENU, "BF8BAAA512404025AB903FEE18CF1C5D", SUPPORT_COMPANIES_ES),
      new Translation(TrlTable.FIELD, "47F053A246134D4E91EEBC7C2C3FA932",
          "Email del propietario"),
      new Translation(TrlTable.FIELD, "F6B522D9DDF54FEBAA0C591D3105929E", "Empresa"),
      new Translation(TrlTable.FIELD, "5A47125A9331456CBBC12391BF1D25BB", "Propietario"),
      new Translation(TrlTable.FIELD, "F42BF8CB46C74B3E92287AF9E9161543", "Fecha de alta"),
      new Translation(TrlTable.FIELD, "FF25C875E51E4324A22D6B98BC2D9F77", "Último acceso"),
      new Translation(TrlTable.FIELD, "24189569F3CB470D9C0015669E8E49F8", "Tipo de entorno"),
      new Translation(TrlTable.FIELD, "B8DE1C7DC89340EF9E2491721309E0F8",
          "Estado de la suscripción"),
      new Translation(TrlTable.FIELD, "2ED83718D16644E4A5B2C96BE0DC409F", "Inicio del trial"),
      new Translation(TrlTable.FIELD, "108BC56C934F431492BD26A3F6F224CC", "Operador de soporte"),
      new Translation(TrlTable.FIELD, "10BD2BB2C6B84EC5943413BB648E986B",
          "Fin de la sesión de soporte"),
      new Translation(TrlTable.FIELD, "19806B277AE244F7B88EF53E921C6DC3", ACCESS_AS_SUPPORT_ES),
      new Translation(TrlTable.FIELD, "BD326B341D05405A9E6F0E0A9D8628DC",
          CLOSE_SUPPORT_SESSION_ES),
      new Translation(TrlTable.PROCESS, ISSUE_PROCESS_ID, ACCESS_AS_SUPPORT_ES),
      new Translation(TrlTable.PROCESS, REVOKE_PROCESS_ID, CLOSE_SUPPORT_SESSION_ES),
      new Translation(TrlTable.PARAMETER, "03EB1D0A05464F22BBA4BE464BFC7081", "Motivo"),
      new Translation(TrlTable.PARAMETER, "307481481032458AAB69905D78712D46", "Duración"),
      new Translation(TrlTable.REF_LIST, "7BEA5166A5CE4F7DBC22CC9267987346", "30 minutos"),
      new Translation(TrlTable.REF_LIST, "4F8AEE7FDEBD496AB5434709438B612C", "1 hora"),
      new Translation(TrlTable.REF_LIST, "279C57AF88844BEB984BB7C6184D2735", "2 horas"),
      new Translation(TrlTable.REF_LIST, "907F58E897094D6A9A07F8D2F42C9516", "4 horas"),
      new Translation(TrlTable.REF_LIST, "DFB224C7E30E4095A4CEA6BF7B81642C", "8 horas"),
      // ETGO_SupportReasonRequired
      new Translation(TrlTable.MESSAGE, "604FC49937714596A1CDE56865D82C9E",
          "Indica un motivo de al menos %0 caracteres."),
      // ETGO_SupportDurationInvalid
      new Translation(TrlTable.MESSAGE, "6871945D31534182953926AC77049D8C",
          "La duración debe estar entre 1 y %0 minutos."),
      // ETGO_SupportTargetNotEligible
      new Translation(TrlTable.MESSAGE, "081165EBD2A54F9582414B0F52EE87F3",
          "Soporte no puede entrar a esta empresa: está inactiva, es una plantilla o es un "
              + "tenant del pool sin reclamar."),
      // ETGO_SupportOperatorNotAllowed
      new Translation(TrlTable.MESSAGE, "16E0BFFB62534D31BAA563DDA6238238",
          "Solo un usuario que entró con el rol de soporte de Etendo GO puede hacer esto."),
      // ETGO_SupportNoAdminRole
      new Translation(TrlTable.MESSAGE, "4B5B1497953A4A76A7BB9FF557E64589",
          "La empresa no tiene un rol de administrador activo con el que pueda actuar el "
              + "usuario de soporte."),
      // ETGO_SupportAccountMissing
      new Translation(TrlTable.MESSAGE, "A5B615AF58B340389E1DE354998EEE79",
          "Falta la cuenta técnica de soporte o está inactiva. Ejecuta update.database."),
      // ETGO_SupportAccessNotOpen
      new Translation(TrlTable.MESSAGE, "47BA084ABF264692902C04E475D0F123",
          "Esta empresa no tiene una sesión de soporte abierta."),
      // ETGO_SupportTenantBusy
      new Translation(TrlTable.MESSAGE, "0E4717A40D4E424CBD3DB384CBC4D399",
          "%0 está dentro hasta las %1 (quedan %2 min). " + CLOSE_OR_WAIT_ES),
      // ETGO_SupportTenantBusyNoExpiry
      new Translation(TrlTable.MESSAGE, "304CD1FAA2C54AB9A2FB0410EF985F66",
          "%0 está dentro de esta empresa. " + CLOSE_OR_WAIT_ES),
      // ETGO_SupportUnknownOperator
      new Translation(TrlTable.MESSAGE, "AC4DDB8CFC5A4E2788042830FF20870F", "Otro operador"),
      // ETGO_SupportAppUrlMissing
      new Translation(TrlTable.MESSAGE, "E63BABF8F4454E9B81CB61467D7A8F43",
          "La URL de la app Etendo GO no está configurada y no se puede generar el enlace de "
              + "acceso. Configura etendo.go.app.baseUrl (o ETGO_APP_BASE_URL)."),
      // ETGO_SupportAccessIssued
      new Translation(TrlTable.MESSAGE, "EB23BCCF2A934BB8897A35C84A4ABE6B",
          "Acceso de soporte concedido por %0 minutos. Etendo GO se abre en una pestaña nueva; "
              + "si no se abrió, %1."),
      // ETGO_SupportAccessLink
      new Translation(TrlTable.MESSAGE, "0DCDD2A3DBBC4C288CA40E2B225FF633",
          "usa este enlace (de un solo uso, válido por %0 segundos)"),
      // ETGO_SupportAccessRevoked
      new Translation(TrlTable.MESSAGE, "7D59E1DC6BFE428E8B4E60BB08F1A0D8",
          "Sesión de soporte cerrada. La empresa vuelve a estar libre."),
      // ETGO_SupportAccessFailed
      new Translation(TrlTable.MESSAGE, "EEF3F3E144B44A0CAC3FCF94FD4CC6E5",
          "No se pudo completar la acción de soporte. Revisa el log de la aplicación."),
  };

  @Override
  public void execute() {
    try {
      ConnectionProvider cp = getConnectionProvider();
      ensureSupportRole(cp);
      ensureSupportAccount(cp);
      grantSupportWindowAndProcesses(cp);
      ensureSpanishTexts(cp);
    } catch (Exception e) {
      handleError(e);
    }
  }

  private void grantSupportWindowAndProcesses(ConnectionProvider cp) throws Exception {
    // Both inserts are no-ops when the role was not seeded with its fixed id (see above).
    try (PreparedStatement ps = cp.getPreparedStatement(INSERT_WINDOW_ACCESS_SQL)) {
      ps.setString(1, SUPPORT_WINDOW_ACCESS_ID);
      ps.setString(2, SYSTEM_CLIENT_ID);
      ps.setString(3, STAR_ORG_ID);
      ps.setString(4, SYSTEM_USER_ID);
      ps.setString(5, SYSTEM_USER_ID);
      ps.setString(6, SUPPORT_WINDOW_ID);
      ps.setString(7, SUPPORT_ROLE_ID);
      ps.executeUpdate();
    }
    grantProcess(cp, ISSUE_PROCESS_ACCESS_ID, ISSUE_PROCESS_ID);
    grantProcess(cp, REVOKE_PROCESS_ACCESS_ID, REVOKE_PROCESS_ID);
  }

  private void grantProcess(ConnectionProvider cp, String accessId, String processId)
      throws Exception {
    try (PreparedStatement ps = cp.getPreparedStatement(INSERT_PROCESS_ACCESS_SQL)) {
      ps.setString(1, accessId);
      ps.setString(2, SYSTEM_CLIENT_ID);
      ps.setString(3, STAR_ORG_ID);
      ps.setString(4, SYSTEM_USER_ID);
      ps.setString(5, SYSTEM_USER_ID);
      ps.setString(6, processId);
      ps.setString(7, SUPPORT_ROLE_ID);
      ps.executeUpdate();
    }
  }

  private void ensureSpanishTexts(ConnectionProvider cp) throws Exception {
    for (Translation translation : SPANISH_TEXTS) {
      try (PreparedStatement ps = cp.getPreparedStatement(translation.table.insertSql)) {
        ps.setString(1, translation.text);
        ps.setString(2, SPANISH);
        ps.setString(3, translation.id);
        ps.executeUpdate();
      }
      try (PreparedStatement ps = cp.getPreparedStatement(translation.table.updateSql)) {
        ps.setString(1, translation.text);
        ps.setString(2, translation.id);
        ps.setString(3, SPANISH);
        ps.setString(4, translation.text);
        ps.executeUpdate();
      }
    }
  }

  private void ensureSupportRole(ConnectionProvider cp) throws Exception {
    try (PreparedStatement ps = cp.getPreparedStatement(INSERT_ROLE_SQL)) {
      ps.setString(1, SUPPORT_ROLE_ID);
      ps.setString(2, SYSTEM_CLIENT_ID);
      ps.setString(3, STAR_ORG_ID);
      ps.setString(4, SYSTEM_USER_ID);
      ps.setString(5, SYSTEM_USER_ID);
      ps.setString(6, SUPPORT_ROLE_NAME);
      ps.setString(7, "System role for Etendo GO support operators (ETP-5351): grants the "
          + "support clients window and the support access processes only.");
      ps.setString(8, SYSTEM_USER_LEVEL);
      ps.executeUpdate();
    }
    // Inserted only when the role row with this exact id exists (it may not, if a same-named
    // role made the insert above a no-op).
    try (PreparedStatement ps = cp.getPreparedStatement(INSERT_ROLE_ORG_ACCESS_SQL)) {
      ps.setString(1, SUPPORT_ROLE_ORG_ACCESS_ID);
      ps.setString(2, STAR_ORG_ID);
      ps.setString(3, SYSTEM_CLIENT_ID);
      ps.setString(4, SYSTEM_USER_ID);
      ps.setString(5, SYSTEM_USER_ID);
      ps.setString(6, SUPPORT_ROLE_ID);
      ps.executeUpdate();
    }
  }

  private void ensureSupportAccount(ConnectionProvider cp) throws Exception {
    try (PreparedStatement ps = cp.getPreparedStatement(INSERT_ACCOUNT_SQL)) {
      ps.setString(1, SUPPORT_ACCOUNT_ID);
      ps.setString(2, SYSTEM_CLIENT_ID);
      ps.setString(3, STAR_ORG_ID);
      ps.setString(4, SYSTEM_USER_ID);
      ps.setString(5, SYSTEM_USER_ID);
      ps.setString(6, SUPPORT_ACCOUNT_EMAIL);
      ps.setString(7, SUPPORT_ACCOUNT_NAME);
      ps.setString(8, SUPPORT_ACCOUNT_STATUS);
      ps.executeUpdate();
    }
    try (PreparedStatement ps = cp.getPreparedStatement(SCRUB_ACCOUNT_SQL)) {
      ps.setString(1, SUPPORT_ACCOUNT_STATUS);
      ps.setString(2, SYSTEM_USER_ID);
      ps.setString(3, SUPPORT_ACCOUNT_ID);
      ps.setString(4, SUPPORT_ACCOUNT_STATUS);
      ps.executeUpdate();
    }
  }
}
