/* Etendo License. */
package com.etendoerp.go.onboarding;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.pricing.pricelist.ProductPrice;

import com.etendoerp.go.schemaforge.BatchService;
import com.etendoerp.go.schemaforge.PriceListVersionResolver;

/**
 * Copies the explicitly selected demo master data into a paid productive tenant.
 *
 * <p>The operation runs after the new tenant has been provisioned. It deliberately reuses the
 * same transactional NEO batch service used by the Products and Contacts grids. This keeps the
 * transfer on the import path, including its handlers, defaults, validations and linked price
 * operations, instead of maintaining a second DAL cloning implementation.</p>
 */
public class OnboardingDataTransferService {
  private static final Logger log = LogManager.getLogger(OnboardingDataTransferService.class);

  public TransferResult transfer(String sourceClientId, String targetClientId, String targetOrgId,
      boolean products, boolean contacts) {
    if (StringUtils.isBlank(sourceClientId) || (!products && !contacts)) {
      return TransferResult.empty();
    }
    // The source is read under admin mode, but the batch must run with the destination client and
    // organization in the active context so the standard NEO import path assigns the target
    // tenancy to every created row.
    OBContext.setOBContext("0", "0", targetClientId, targetOrgId);
    OBContext.setAdminMode(true);
    try {
      Client targetClient = OBDal.getInstance().get(Client.class, targetClientId);
      Organization targetOrg = OBDal.getInstance().get(Organization.class, targetOrgId);
      if (targetClient == null || targetOrg == null) {
        return new TransferResult(0, 0, 0, "target tenant not found");
      }
      List<Product> sourceProducts = products
          ? filterNewProducts(listProducts(sourceClientId), targetClientId)
          : List.of();
      List<ProductPrice> sourcePrices = products ? listPrices(sourceClientId) : List.of();
      List<BusinessPartner> sourceContacts = contacts
          ? filterNewContacts(listContacts(sourceClientId), targetClientId)
          : List.of();
      String targetPriceListVersionId = products
          ? PriceListVersionResolver.resolveDefaultVersionId(OBContext.getOBContext(), true)
          : null;
      if (products && StringUtils.isBlank(targetPriceListVersionId)) {
        return new TransferResult(0, 0, 1, "target sales price list not found");
      }

      JSONArray operations = buildGridImportOperations(sourceProducts, sourcePrices, sourceContacts,
          targetPriceListVersionId);
      if (operations.length() == 0) {
        return TransferResult.empty();
      }
      JSONObject result = executeGridImport(operations);
      if (!result.optBoolean("committed", false)) {
        return new TransferResult(0, 0, 1, result.optString("error", "grid import failed"));
      }
      return countImportedOperations(result.optJSONArray("operations"));
    } catch (JSONException e) {
      log.error("Selected demo data transfer could not build the grid import payload", e);
      return new TransferResult(0, 0, 1, e.getMessage());
    } catch (RuntimeException e) {
      log.error("Selected demo data transfer failed from client {} to {}", sourceClientId,
          targetClientId, e);
      return new TransferResult(0, 0, 1, e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** Builds the exact operations sent by the grid import endpoint. */
  protected JSONArray buildGridImportOperations(List<Product> products, List<ProductPrice> prices,
      List<BusinessPartner> contacts, String targetPriceListVersionId) throws JSONException {
    JSONArray operations = new JSONArray();
    Map<String, ProductPrice> firstPriceByProduct = prices.stream()
        .filter(price -> price.getProduct() != null)
        .collect(Collectors.toMap(price -> price.getProduct().getId(), Function.identity(),
            (first, ignored) -> first));

    for (Product source : products) {
      String operationId = "product-" + source.getId();
      JSONObject body = new JSONObject();
      putIfPresent(body, "searchKey", source.get("searchKey"));
      putIfPresent(body, "name", source.get("name"));
      putIfPresent(body, "description", source.get("description"));
      putIfPresent(body, "productType", source.get("productType"));
      putReference(body, "uOM", source.get("uOM"));
      putReference(body, "productCategory", source.get("productCategory"));
      operations.put(operation("product", "product", operationId, body, null));

      ProductPrice price = firstPriceByProduct.get(source.getId());
      if (price != null) {
        JSONObject priceBody = new JSONObject();
        priceBody.put("priceListVersion", targetPriceListVersionId);
        putIfPresent(priceBody, "standardPrice", price.getStandardPrice());
        putIfPresent(priceBody, "listPrice", price.getListPrice());
        putIfPresent(priceBody, "priceLimit", price.getPriceLimit());
        operations.put(operation("product", "price", "price-" + source.getId(), priceBody,
            operationId));
      }
    }

    for (BusinessPartner source : contacts) {
      JSONObject body = new JSONObject();
      putIfPresent(body, "searchKey", source.get("searchKey"));
      putIfPresent(body, "name", source.get("name"));
      putIfPresent(body, "taxID", source.get("taxID"));
      putIfPresent(body, "etgoFirstname", source.get("etgoFirstname"));
      putIfPresent(body, "etgoLastname", source.get("etgoLastname"));
      putIfPresent(body, "etgoEmail", source.get("etgoEmail"));
      putIfPresent(body, "etgoPhone", source.get("etgoPhone"));
      putIfPresent(body, "etgoWeb", source.get("etgoWeb"));
      putIfPresent(body, "oBTIKTaxIDKey", source.get("oBTIKTaxIDKey"));
      putIfPresent(body, "etgoIsperson", source.get("etgoIsperson"));
      operations.put(operation("contacts", "businessPartner", "contact-" + source.getId(), body,
          null));
    }
    return operations;
  }

  /** Executes the same batch implementation used by the HTTP grid import. */
  protected JSONObject executeGridImport(JSONArray operations) throws JSONException {
    return BatchService.forBatchOnly().executeBatch(operations);
  }

  private List<Product> listProducts(String clientId) {
    return list(Product.class,
        "as p where p.client.id = :clientId and p.active = true order by p.searchKey, p.id",
        clientId);
  }

  private List<ProductPrice> listPrices(String clientId) {
    return list(ProductPrice.class,
        "as pp where pp.product.client.id = :clientId and pp.active = true "
            + "and pp.priceListVersion.priceList.salesPriceList = true "
            + "order by pp.product.id, pp.id",
        clientId);
  }

  private List<BusinessPartner> listContacts(String clientId) {
    return list(BusinessPartner.class,
        "as bp where bp.client.id = :clientId and bp.active = true order by bp.searchKey, bp.id",
        clientId);
  }

  private List<Product> filterNewProducts(List<Product> sourceProducts, String targetClientId) {
    return sourceProducts.stream()
        .filter(product -> !exists(Product.class, targetClientId,
            String.valueOf(product.get("searchKey"))))
        .toList();
  }

  private List<BusinessPartner> filterNewContacts(List<BusinessPartner> sourceContacts,
      String targetClientId) {
    return sourceContacts.stream()
        .filter(contact -> !exists(BusinessPartner.class, targetClientId,
            String.valueOf(contact.get("searchKey"))))
        .toList();
  }

  private <T extends BaseOBObject> boolean exists(Class<T> type, String clientId, String searchKey) {
    OBQuery<T> query = OBDal.getInstance().createQuery(type,
        "as row where row.client.id = :clientId and row.searchKey = :searchKey");
    query.setNamedParameter("clientId", clientId);
    query.setNamedParameter("searchKey", searchKey);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    return query.uniqueResult() != null;
  }

  private JSONObject operation(String spec, String entity, String id, JSONObject body,
      String parentRef) throws JSONException {
    JSONObject operation = new JSONObject();
    operation.put("id", id);
    operation.put("spec", spec);
    operation.put("entity", entity);
    operation.put("body", body);
    if (parentRef != null) {
      operation.put("parentRef", parentRef);
    }
    return operation;
  }

  private void putReference(JSONObject body, String field, Object value) throws JSONException {
    if (value instanceof BaseOBObject object) {
      putIfPresent(body, field, object.getId());
    }
  }

  private void putIfPresent(JSONObject body, String field, Object value) throws JSONException {
    if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
      body.put(field, value);
    }
  }

  private TransferResult countImportedOperations(JSONArray imported) {
    int products = 0;
    int contacts = 0;
    if (imported != null) {
      for (int i = 0; i < imported.length(); i++) {
        String id = imported.optJSONObject(i).optString("id", "");
        if (id.startsWith("product-")) products++;
        if (id.startsWith("contact-")) contacts++;
      }
    }
    return new TransferResult(products, contacts, 0, null);
  }

  private <T extends BaseOBObject> List<T> list(Class<T> type, String where, String clientId) {
    OBQuery<T> query = OBDal.getInstance().createQuery(type, where);
    query.setNamedParameter("clientId", clientId);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.list();
  }

  public record TransferResult(int productsCopied, int contactsCopied, int failures,
      String failureReason) {
    static TransferResult empty() {
      return new TransferResult(0, 0, 0, null);
    }
  }
}
