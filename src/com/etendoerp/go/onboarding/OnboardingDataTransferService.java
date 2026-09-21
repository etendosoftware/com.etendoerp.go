/* Etendo License. */
package com.etendoerp.go.onboarding;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.plm.Product;

/**
 * Copies the explicitly selected demo master data into a paid productive tenant.
 *
 * <p>The operation runs after the new tenant has been provisioned. It is deliberately
 * best-effort: payment, tenant readiness and the subscription must not be rolled back because
 * one source row cannot be copied. Repeating the operation is safe because stable business keys
 * ({@code value}) are checked in the destination before a copy is created.</p>
 */
public class OnboardingDataTransferService {
  private static final Logger log = LogManager.getLogger(OnboardingDataTransferService.class);

  public TransferResult transfer(String sourceClientId, String targetClientId, String targetOrgId,
      boolean products, boolean contacts) {
    if (StringUtils.isBlank(sourceClientId) || (!products && !contacts)) {
      return TransferResult.empty();
    }
    OBContext.setOBContext("0", "0", "0", "0");
    OBContext.setAdminMode(true);
    try {
      Client targetClient = OBDal.getInstance().get(Client.class, targetClientId);
      Organization targetOrg = OBDal.getInstance().get(Organization.class, targetOrgId);
      if (targetClient == null || targetOrg == null) {
        return new TransferResult(0, 0, 0, "target tenant not found");
      }
      int copiedProducts = products ? copyProducts(sourceClientId, targetClient, targetOrg) : 0;
      int copiedContacts = contacts ? copyContacts(sourceClientId, targetClient, targetOrg) : 0;
      OBDal.getInstance().flush();
      return new TransferResult(copiedProducts, copiedContacts, 0, null);
    } catch (RuntimeException e) {
      log.error("Selected demo data transfer failed from client {} to {}", sourceClientId,
          targetClientId, e);
      return new TransferResult(0, 0, 1, e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  protected int copyProducts(String sourceClientId, Client targetClient, Organization targetOrg) {
    int copied = 0;
    for (Product source : list(Product.class,
        "as p where p.client.id = :clientId and p.active = true order by p.value, p.id",
        sourceClientId)) {
      if (exists(Product.class, "as p where p.client.id = :clientId and p.value = :value",
          targetClient.getId(), valueOf(source))) {
        continue;
      }
      try {
        Product clone = (Product) DalUtil.copy(source, true);
        clone.setClient(targetClient);
        clone.setOrganization(targetOrg);
        OBDal.getInstance().save(clone);
        copied++;
      } catch (RuntimeException e) {
        log.warn("Could not copy product {} ({})", valueOf(source), source.getId(), e);
      }
    }
    return copied;
  }

  protected int copyContacts(String sourceClientId, Client targetClient, Organization targetOrg) {
    int copied = 0;
    for (BusinessPartner source : list(BusinessPartner.class,
        "as bp where bp.client.id = :clientId and bp.active = true order by bp.value, bp.id",
        sourceClientId)) {
      if (exists(BusinessPartner.class,
          "as bp where bp.client.id = :clientId and bp.value = :value",
          targetClient.getId(), valueOf(source))) {
        continue;
      }
      try {
        BusinessPartner clone = (BusinessPartner) DalUtil.copy(source, true);
        clone.setClient(targetClient);
        clone.setOrganization(targetOrg);
        OBDal.getInstance().save(clone);
        copied++;
      } catch (RuntimeException e) {
        log.warn("Could not copy contact {} ({})", valueOf(source), source.getId(), e);
      }
    }
    return copied;
  }

  private <T extends BaseOBObject> List<T> list(Class<T> type, String where, String clientId) {
    OBQuery<T> query = OBDal.getInstance().createQuery(type, where);
    query.setNamedParameter("clientId", clientId);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.list();
  }

  private <T extends BaseOBObject> boolean exists(Class<T> type, String where, String clientId, String value) {
    OBQuery<T> query = OBDal.getInstance().createQuery(type, where);
    query.setNamedParameter("clientId", clientId);
    query.setNamedParameter("value", StringUtils.trimToEmpty(value));
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    return query.uniqueResult() != null;
  }

  private String valueOf(BaseOBObject object) {
    Object value = object.get("value");
    return value == null ? "" : String.valueOf(value);
  }

  public record TransferResult(int productsCopied, int contactsCopied, int failures,
      String failureReason) {
    static TransferResult empty() {
      return new TransferResult(0, 0, 0, null);
    }
  }
}
