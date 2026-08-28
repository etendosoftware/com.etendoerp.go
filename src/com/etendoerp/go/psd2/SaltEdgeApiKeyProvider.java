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

import java.util.UUID;

import javax.enterprise.context.ApplicationScoped;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.access.User;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.utils.FormatUtilities;

import com.etendoerp.psd2.bank.integration.spi.Psd2ApiKeyProvider;
import com.etendoerp.psd2.bank.integration.audit.Psd2ApiKeyAuditService;
import com.etendoerp.go.schemaforge.data.Account;

/**
 * Lazily provisions and persists the PSD2 API key for an Etendo client.
 *
 * <p>The owner email is read through a dedicated read-only OBDal pool and that session is closed
 * before the external provisioning call. The API key is then persisted through the caller's normal
 * DAL lifecycle, without holding an AD_CLIENT lock while waiting for the proxy.</p>
 */
@ApplicationScoped
public class SaltEdgeApiKeyProvider implements Psd2ApiKeyProvider {

  private static final Logger log = LogManager.getLogger(SaltEdgeApiKeyProvider.class);
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

    String storedValue = client.getPsd2ApiKey();
    if (StringUtils.isNotBlank(storedValue)) {
      Psd2ApiKeyAuditService.record(client.getId(), "PROVISION_REUSED", "SUCCESS",
          UUID.randomUUID().toString(), null, null, null, 0L, "GO");
      return decrypt(storedValue);
    }

    String correlationId = UUID.randomUUID().toString();
    long startedAt = System.nanoTime();
    try {
      // The email lookup, when a dedicated read-only pool is available, is closed before this
      // call. Never hold an AD_CLIENT lock while waiting for the proxy or Salt Edge.
      String apiKey = provisioningClient.provision(client.getId(), resolveOwnerEmail(client));
      persistApiKey(client.getId(), apiKey);
      Psd2ApiKeyAuditService.record(client.getId(), "PROVISION_SUCCEEDED", "SUCCESS", correlationId,
          200, null, null, elapsedMillis(startedAt), "GO");
      return apiKey;
    } catch (Exception e) {
      Psd2ApiKeyAuditService.record(client.getId(), "PROVISION_FAILED", "ERROR",
          correlationId, null, "PROVISIONING_ERROR", e, elapsedMillis(startedAt), "GO");
      log.error("PSD2 API key provisioning failed for client {}", client.getId(), e);
      if (e instanceof OBException) {
        throw (OBException) e;
      }
      throw new OBException("Unable to provision the PSD2 API key", e);
    }
  }

  private static void persistApiKey(String clientId, String apiKey) {
    try {
      Client managedClient = OBDal.getInstance().get(Client.class, clientId);
      if (managedClient == null) {
        throw new OBException("Client not found while persisting the PSD2 API key");
      }
      if (StringUtils.isBlank(managedClient.getPsd2ApiKey())) {
        managedClient.setPsd2ApiKey(FormatUtilities.encryptDecrypt(apiKey, true));
        OBDal.getInstance().save(managedClient);
        OBDal.getInstance().flush();
      }
    } catch (OBException e) {
      throw e;
    } catch (Exception e) {
      throw new OBException("Unable to persist the PSD2 API key", e);
    }
  }

  private static long elapsedMillis(long startedAt) {
    return startedAt == 0L ? 0L : (System.nanoTime() - startedAt) / 1_000_000L;
  }

  private static String decrypt(String storedValue) {
    try {
      return FormatUtilities.encryptDecrypt(storedValue, false);
    } catch (Exception e) {
      throw new OBException("Unable to decrypt the PSD2 API key", e);
    }
  }

  private static String resolveOwnerEmail(Client client) {
    try {
      OBDal readOnlyDal = OBDal.getReadOnlyInstance();
      if (readOnlyDal == OBDal.getInstance()) {
        throw new OBException("A dedicated read-only DAL pool is required for PSD2 provisioning");
      }
      try {
        OBQuery<User> ownerQuery = readOnlyDal.createQuery(User.class,
            "as owner where owner.client.id = :clientId and owner.eTGOIsOwner = true "
                + "and owner.active = true");
        ownerQuery.setNamedParameter("clientId", client.getId());
        ownerQuery.setFilterOnReadableClients(false);
        ownerQuery.setFilterOnReadableOrganization(false);
        ownerQuery.setMaxResult(1);
        User owner = ownerQuery.uniqueResult();
        if (owner != null) {
          String ownerIdentity = StringUtils.isNotBlank(owner.getEmail()) ? owner.getEmail()
              : owner.getUsername();
          if (StringUtils.isNotBlank(ownerIdentity)) {
            OBQuery<Account> accountQuery = readOnlyDal.createQuery(Account.class,
                "as account where lower(account.email) = :email and account.active = true "
                    + "and account.status = 'active'");
            accountQuery.setNamedParameter("email", ownerIdentity.toLowerCase());
            accountQuery.setFilterOnReadableClients(false);
            accountQuery.setFilterOnReadableOrganization(false);
            accountQuery.setMaxResult(1);
            Account account = accountQuery.uniqueResult();
            if (account != null && StringUtils.isNotBlank(account.getEmail())) {
              return account.getEmail();
            }
          }
        }
      } finally {
        // The account lookup must not keep a read-only transaction open while the proxy call is
        // running. The default DAL session is never closed here.
        readOnlyDal.rollbackAndClose();
      }
    } catch (Exception e) {
      log.debug("Could not resolve the ETGO account owner email for client {}", client.getId(), e);
    }
    throw new OBException("An active ETGO account owner email is required for PSD2 provisioning");
  }

}
