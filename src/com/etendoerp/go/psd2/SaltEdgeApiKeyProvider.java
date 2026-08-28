/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * All portions are Copyright © 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.psd2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import javax.enterprise.context.ApplicationScoped;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;
import org.openbravo.database.ConnectionProvider;
import org.openbravo.service.db.DalConnectionProvider;
import org.openbravo.utils.FormatUtilities;

import com.etendoerp.go.common.GoAccountResolver;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.psd2.bank.integration.spi.Psd2ApiKeyProvider;
import com.etendoerp.psd2.bank.integration.audit.Psd2ApiKeyAuditService;

/**
 * Lazily provisions and persists the PSD2 API key for an Etendo client.
 *
 * <p>The provisioning transaction is deliberately managed through a separate
 * {@link DalConnectionProvider}; it is never the DAL transaction of the PSD2 business operation.
 * The PostgreSQL transaction advisory lock serializes provisioning for one client across
 * application nodes while allowing different clients to proceed independently.</p>
 */
@ApplicationScoped
public class SaltEdgeApiKeyProvider implements Psd2ApiKeyProvider {

  private static final Logger log = LogManager.getLogger(SaltEdgeApiKeyProvider.class);
  private static final String SELECT_KEY =
      "SELECT EM_PSD2_API_KEY FROM AD_CLIENT WHERE AD_CLIENT_ID = ? FOR UPDATE";
  private static final String UPDATE_KEY =
      "UPDATE AD_CLIENT SET EM_PSD2_API_KEY = ?, UPDATED = CURRENT_TIMESTAMP, UPDATEDBY = ? "
          + "WHERE AD_CLIENT_ID = ?";
  private static final String LOCK = "SELECT pg_advisory_xact_lock(hashtext(?))";
  private final SaltEdgeProvisioningClient provisioningClient;

  public SaltEdgeApiKeyProvider() {
    this(new SaltEdgeProvisioningClient());
  }

  SaltEdgeApiKeyProvider(SaltEdgeProvisioningClient provisioningClient) {
    this.provisioningClient = provisioningClient;
  }

  @Override
  public String getPsd2ApiKey(Client client) {
    if (client == null || StringUtils.isBlank(client.getId())) {
      throw new OBException("A client is required to obtain a PSD2 API key");
    }

    DalConnectionProvider connectionProvider = new DalConnectionProvider(false);
    Connection connection = null;
    try {
      connection = connectionProvider.getTransactionConnection();
      lockClient(connection, client.getId());

      String storedValue = readStoredValue(connection, client.getId());
      if (StringUtils.isNotBlank(storedValue)) {
        Psd2ApiKeyAuditService.record(client.getId(), "PROVISION_REUSED", "SUCCESS",
            UUID.randomUUID().toString(), null, null, null, 0L, "GO");
        return decrypt(storedValue);
      }

      String correlationId = UUID.randomUUID().toString();
      long startedAt = System.nanoTime();
      String apiKey = provisioningClient.provision(client.getId(), resolveEmail());
      String encryptedKey = FormatUtilities.encryptDecrypt(apiKey, true);
      try (PreparedStatement statement = connection.prepareStatement(UPDATE_KEY)) {
        statement.setString(1, encryptedKey);
        statement.setString(2, resolveUserId());
        statement.setString(3, client.getId());
        statement.executeUpdate();
      }
      connectionProvider.releaseCommitConnection(connection);
      connection = null;
      Psd2ApiKeyAuditService.record(client.getId(), "PROVISION_SUCCEEDED", "SUCCESS", correlationId,
          200, null, null, elapsedMillis(startedAt), "GO");
      return apiKey;
    } catch (Exception e) {
      rollback(connectionProvider, connection);
      connection = null;
      Psd2ApiKeyAuditService.record(client.getId(), "PROVISION_FAILED", "ERROR",
          UUID.randomUUID().toString(), null, "PROVISIONING_ERROR", e, 0L, "GO");
      log.error("PSD2 API key provisioning failed for client {}", client.getId(), e);
      if (e instanceof OBException) {
        throw (OBException) e;
      }
      throw new OBException("Unable to provision the PSD2 API key", e);
    } finally {
      if (connection != null) {
        rollback(connectionProvider, connection);
      }
    }
  }

  private static long elapsedMillis(long startedAt) {
    return startedAt == 0L ? 0L : (System.nanoTime() - startedAt) / 1_000_000L;
  }

  private static void lockClient(Connection connection, String clientId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(LOCK)) {
      statement.setString(1, "psd2-api-key:" + clientId);
      statement.executeQuery();
    }
  }

  private static String readStoredValue(Connection connection, String clientId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(SELECT_KEY)) {
      statement.setString(1, clientId);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() ? result.getString(1) : null;
      }
    }
  }

  private static String decrypt(String storedValue) {
    try {
      return FormatUtilities.encryptDecrypt(storedValue, false);
    } catch (Exception e) {
      throw new OBException("Unable to decrypt the PSD2 API key", e);
    }
  }

  private static String resolveEmail() {
    try {
      if (OBContext.getOBContext() != null && OBContext.getOBContext().getUser() != null) {
        String username = OBContext.getOBContext().getUser().getUsername();
        Account account = GoAccountResolver.findAccountByUsername(username).orElse(null);
        if (account != null && StringUtils.isNotBlank(account.getEmail())) {
          return account.getEmail();
        }
      }
    } catch (Exception e) {
      log.debug("Could not resolve the ETGO account email for PSD2 provisioning", e);
    }
    throw new OBException("An active ETGO account email is required for PSD2 provisioning");
  }

  private static String resolveUserId() {
    try {
      if (OBContext.getOBContext() != null && OBContext.getOBContext().getUser() != null) {
        return OBContext.getOBContext().getUser().getId();
      }
    } catch (Exception e) {
      log.debug("Could not resolve the current user for PSD2 provisioning", e);
    }
    return "0";
  }

  private static void rollback(ConnectionProvider connectionProvider, Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connectionProvider.releaseRollbackConnection(connection);
    } catch (Exception e) {
      log.error("Could not rollback the PSD2 provisioning transaction", e);
    }
  }
}
