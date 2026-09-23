/* Etendo License. */
package com.etendoerp.go.payment;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Category;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.plm.ProductCategory;
import org.openbravo.model.pricing.pricelist.PriceListVersion;
import org.openbravo.model.pricing.pricelist.ProductPrice;
import org.openbravo.model.materialmgmt.cost.Costing;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.financialmgmt.tax.TaxCategory;
import org.openbravo.model.pricing.pricelist.PriceList;


/**
 * Server-side, durable transfer from an owned demo to its newly provisioned productive tenant.
 *
 * <p>The browser stores only the two selections. This service snapshots them before checkout,
 * records the source/target pair after provisioning, and owns retry/progress state thereafter.
 * Preferences are deliberately used as the persistence adapter: this is one bounded migration per
 * tenant and does not justify a second payment/work table. Every copied header has a deterministic
 * target lookup, so retries never duplicate records.
 */
public class DemoDataTransferService {
  private static final Logger log = LogManager.getLogger(DemoDataTransferService.class);
  private static final String ZERO_ID = "0";
  /**
   * Kept short on purpose: the attribute is this prefix plus a 36-character checkout request UUID,
   * and {@code AD_PREFERENCE.ATTRIBUTE} holds 60 characters (the original
   * {@code ETGO_DemoDataTransferSelection.} made 67).
   */
  static final String SELECTION_PREFIX = "ETGO_DDTSelection.";
  private static final String STATUS = "ETGO_DemoDataTransferStatus";
  private static final String SOURCE = "ETGO_DemoDataTransferSource";
  private static final String PRODUCTS = "ETGO_DemoDataTransferProducts";
  private static final String CONTACTS = "ETGO_DemoDataTransferContacts";
  private static final String PRODUCTS_DONE = "ETGO_DemoDataTransferProductsDone";
  private static final String PRODUCTS_TOTAL = "ETGO_DemoDataTransferProductsTotal";
  private static final String CONTACTS_DONE = "ETGO_DemoDataTransferContactsDone";
  private static final String CONTACTS_TOTAL = "ETGO_DemoDataTransferContactsTotal";
  private static final String FAILURE = "ETGO_DemoDataTransferFailure";
  private static final String CLIENT_ID_PARAMETER = "clientId";
  private static final String SEARCH_KEY_PROPERTY = "SearchKey";
  private static final String DESCRIPTION_PROPERTY = "Description";
  public static final String STATUS_NOT_REQUESTED = "NOT_REQUESTED";
  public static final String STATUS_RUNNING = "RUNNING";
  public static final String STATUS_FAILED = "FAILED";
  public static final String STATUS_COMPLETED = "COMPLETED";
  public static final String STATUS_SKIPPED = "SKIPPED";
  /**
   * Contract owned jointly with {@code artifacts/product/contract.json}'s import fields.
   * Keep this exact ordered list under a source-reading contract test: prices and cost are child
   * records, but they remain import fields and must never silently disappear from this migration.
   */
  public static final List<String> PRODUCT_IMPORT_FIELDS = List.of("searchKey", "name",
      "description", "productType", "uOM", "salesPrice", "purchasePrice", "cost",
      "costStartingDate", "category");

  /**
   * Created on the first submission, never at construction: the servlet builds this service at
   * init, and with flag {@code demo-data-transfer} off nothing may start a worker thread.
   */
  private ExecutorService executor;
  private final Set<String> activeClients = ConcurrentHashMap.newKeySet();

  /** Stores user intent before redirecting to the payment provider.
   * @param requestId payment request identifier
   * @param products whether products should be transferred
   * @param contacts whether business partners should be transferred
   */
  public void recordSelection(String requestId, boolean products, boolean contacts) {
    if (StringUtils.isBlank(requestId)) return;
    withSystemContext(() -> {
      String value = (products ? "Y" : "N") + (contacts ? "Y" : "N");
      String existing = readSystemPreference(SELECTION_PREFIX + requestId);
      if (existing != null && !existing.equals(value)) {
        throw new IllegalStateException("Transfer selection cannot change after checkout creation");
      }
      if (existing != null) return;
      setSystemPreference(SELECTION_PREFIX + requestId, value);
      flush();
    });
  }

  /** Returns the checkout's immutable server-side choice, or null for an older purchase. */
  public JSONObject selection(String requestId) throws JSONException {
    if (StringUtils.isBlank(requestId)) return null;
    return withSystemResult(() -> {
      String value = readSystemPreference(SELECTION_PREFIX + requestId);
      if (value == null) return null;
      JSONObject result = new JSONObject();
      result.put("products", selected(value, 0));
      result.put("contacts", selected(value, 1));
      return result;
    });
  }

  /** Creates the durable tenant projection and starts work after provisioning commits.
   * @param requestId payment request identifier
   * @param demoClientId source demo client identifier
   * @param productiveClientId provisioned client identifier
   */
  public void start(String requestId, String demoClientId, String productiveClientId) {
    if (StringUtils.isBlank(requestId) || StringUtils.isBlank(productiveClientId)) return;
    withSystemContext(() -> {
      String selection = readSystemPreference(SELECTION_PREFIX + requestId);
      if (selection == null) return;
      Client target = OBDal.getInstance().get(Client.class, productiveClientId);
      if (target == null) return;
      if (StringUtils.isNotBlank(demoClientId)) {
        setClientPreference(SOURCE, demoClientId, target);
      }
      setClientPreference(PRODUCTS, selected(selection, 0) ? "Y" : "N", target);
      setClientPreference(CONTACTS, selected(selection, 1) ? "Y" : "N", target);
      if (!selected(selection, 0) && !selected(selection, 1)) {
        setClientPreference(STATUS, STATUS_SKIPPED, target);
      } else if (StringUtils.isBlank(demoClientId)) {
        log.error("Demo data transfer cannot start for productive client {}: source demo "
            + "environment is unavailable", productiveClientId);
        setClientPreference(STATUS, STATUS_FAILED, target);
        setClientPreference(FAILURE, "Source demo environment is unavailable", target);
      } else if (demoClientId.equals(productiveClientId)) {
        setClientPreference(STATUS, STATUS_FAILED, target);
        setClientPreference(FAILURE, "Source and target environments are the same", target);
      } else if (!STATUS_COMPLETED.equals(readClientPreference(STATUS, productiveClientId))) {
        setClientPreference(STATUS, STATUS_RUNNING, target);
        setClientPreference(PRODUCTS_DONE, "0", target);
        setClientPreference(CONTACTS_DONE, "0", target);
      }
      flush();
    });
    submitIfRunning(productiveClientId);
  }

  /** Returns a compact persisted projection; a stranded RUNNING job is resumed on read.
   * @param productiveClientId provisioned client identifier
   * @return persisted transfer status and progress
   * @throws JSONException if the status projection cannot be serialized
   */
  public JSONObject status(String productiveClientId) throws JSONException {
    return status(productiveClientId, () -> null);
  }

  /** Reads progress and repairs a stranded transfer whose source was not persisted at kickoff.
   * @param productiveClientId provisioned client identifier
   * @param demoClientResolver resolves the account's only free tenant, if unambiguous
   * @return persisted transfer status and progress
   * @throws JSONException if the status projection cannot be serialized
   */
  public JSONObject status(String productiveClientId, Supplier<String> demoClientResolver)
      throws JSONException {
    withSystemContext(() -> {
      if (!STATUS_RUNNING.equals(readClientPreference(STATUS, productiveClientId))) return;
      String sourceId = readClientPreference(SOURCE, productiveClientId);
      if (StringUtils.isNotBlank(sourceId)) return;
      Client target = OBDal.getInstance().get(Client.class, productiveClientId);
      if (target == null) return;
      String resolvedDemoClientId = demoClientResolver == null ? null : demoClientResolver.get();
      if (StringUtils.isBlank(resolvedDemoClientId)) {
        setClientPreference(STATUS, STATUS_FAILED, target);
        setClientPreference(FAILURE, "Source demo environment is unavailable", target);
      } else if (productiveClientId.equals(resolvedDemoClientId)) {
        setClientPreference(STATUS, STATUS_FAILED, target);
        setClientPreference(FAILURE, "Source and target environments are the same", target);
      } else {
        setClientPreference(SOURCE, resolvedDemoClientId, target);
      }
      flush();
    });
    JSONObject result = withSystemResult(() -> statusJson(productiveClientId));
    if (STATUS_RUNNING.equals(result.optString("status"))) submitIfRunning(productiveClientId);
    return result;
  }

  /** Reclaims failed or stranded work. Completed and skipped work is immutable.
   * @param productiveClientId provisioned client identifier
   * @return current transfer status and progress
   * @throws JSONException if the status projection cannot be serialized
   */
  public JSONObject retry(String productiveClientId) throws JSONException {
    return retry(productiveClientId, null);
  }

  /** Reclaims failed or stranded work, restoring the source from the authenticated account when
   * the original provisioning attempt could not persist it.
   * @param productiveClientId provisioned client identifier
   * @param resolvedDemoClientId the account's only free tenant, if unambiguous
   * @return current transfer status and progress
   * @throws JSONException if the status projection cannot be serialized
   */
  public JSONObject retry(String productiveClientId, String resolvedDemoClientId)
      throws JSONException {
    withSystemContext(() -> {
      String status = readClientPreference(STATUS, productiveClientId);
      if (!STATUS_FAILED.equals(status) && !STATUS_RUNNING.equals(status)) return;
      Client target = OBDal.getInstance().get(Client.class, productiveClientId);
      if (target == null) return;
      String sourceId = readClientPreference(SOURCE, productiveClientId);
      if (StringUtils.isBlank(sourceId) && StringUtils.isNotBlank(resolvedDemoClientId)
          && !productiveClientId.equals(resolvedDemoClientId)) {
        sourceId = resolvedDemoClientId;
        setClientPreference(SOURCE, sourceId, target);
      }
      if (StringUtils.isBlank(sourceId)) {
        setClientPreference(STATUS, STATUS_FAILED, target);
        setClientPreference(FAILURE, "Source demo environment is unavailable", target);
        flush();
        return;
      }
      if (productiveClientId.equals(sourceId)) {
        setClientPreference(STATUS, STATUS_FAILED, target);
        setClientPreference(FAILURE, "Source and target environments are the same", target);
        flush();
        return;
      }
      setClientPreference(STATUS, STATUS_RUNNING, target);
      setClientPreference(FAILURE, "", target);
      flush();
    });
    submitIfRunning(productiveClientId);
    return status(productiveClientId);
  }

  private void submitIfRunning(String productiveClientId) {
    if (!activeClients.add(productiveClientId)) return;
    try {
      executor().submit(() -> {
        try {
          run(productiveClientId);
        } finally {
          activeClients.remove(productiveClientId);
        }
      });
    } catch (RuntimeException e) {
      activeClients.remove(productiveClientId);
      throw e;
    }
  }

  private synchronized ExecutorService executor() {
    if (executor == null) {
      executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "etgo-demo-data-transfer");
        thread.setDaemon(true);
        return thread;
      });
    }
    return executor;
  }

  /** @return whether the worker executor has been created — for tests of the lazy start. */
  synchronized boolean hasExecutor() {
    return executor != null;
  }

  private void run(String productiveClientId) {
    try {
      withSystemContext(() -> {
        if (!STATUS_RUNNING.equals(readClientPreference(STATUS, productiveClientId))) return;
        String sourceId = readClientPreference(SOURCE, productiveClientId);
        Client target = OBDal.getInstance().get(Client.class, productiveClientId);
        Client source = OBDal.getInstance().get(Client.class, sourceId);
        if (target == null || source == null) throw new IllegalStateException("Transfer environment is unavailable");
        if (sourceId.equals(productiveClientId)) {
          throw new IllegalStateException("Source and target environments are the same");
        }
        Organization targetOrg = defaultOrganization(target.getId());
        if (targetOrg == null) throw new IllegalStateException("Target organization is unavailable");
        if ("Y".equals(readClientPreference(PRODUCTS, productiveClientId))) copyProducts(source, target, targetOrg);
        if ("Y".equals(readClientPreference(CONTACTS, productiveClientId))) copyContacts(source, target, targetOrg);
        setClientPreference(STATUS, STATUS_COMPLETED, target);
        flush();
      });
    } catch (RuntimeException e) {
      log.error("Demo data transfer failed for productive client {}", productiveClientId, e);
      rollbackFailedTransfer();
      withSystemContext(() -> {
        Client target = OBDal.getInstance().get(Client.class, productiveClientId);
        if (target != null) {
          setClientPreference(STATUS, STATUS_FAILED, target);
          setClientPreference(FAILURE, StringUtils.abbreviate(StringUtils.defaultString(e.getMessage()), 240), target);
          flush();
        }
      });
    }
  }

  private void copyProducts(Client source, Client target, Organization targetOrg) {
    List<Product> products = query(Product.class, "as p where p.client.id = :clientId", source.getId());
    progress(target, PRODUCTS_TOTAL, products.size());
    int completed = 0;
    progress(target, PRODUCTS_DONE, completed);
    for (Product product : products) {
      Product targetProduct = unique(Product.class, "as p where p.client.id = :clientId and p.searchKey = :key",
          target.getId(), product.getSearchKey());
      if (targetProduct == null) {
        targetProduct = OBProvider.getInstance().get(Product.class);
        targetProduct.setClient(target);
        targetProduct.setOrganization(targetOrg);
      }
      copyProductFields(product, targetProduct);
      targetProduct.setUOM(targetUom(product.getUOM(), target, targetOrg));
      targetProduct.setProductCategory(targetProductCategory(product.getProductCategory(), target, targetOrg));
      targetProduct.setTaxCategory(targetTaxCategory(product.getTaxCategory(), target));
      OBDal.getInstance().save(targetProduct);
      copyPrices(product, targetProduct, target, targetOrg);
      copyCurrentCost(product, targetProduct, target, targetOrg);
      progress(target, PRODUCTS_DONE, ++completed);
      OBDal.getInstance().flush();
    }
  }

  private void copyContacts(Client source, Client target, Organization targetOrg) {
    List<BusinessPartner> contacts = query(BusinessPartner.class,
        "as bp where bp.client.id = :clientId and bp.active = true", source.getId());
    progress(target, CONTACTS_TOTAL, contacts.size());
    int completed = 0;
    progress(target, CONTACTS_DONE, completed);
    for (BusinessPartner contact : contacts) {
      BusinessPartner targetContact = unique(BusinessPartner.class,
          "as bp where bp.client.id = :clientId and bp.searchKey = :key", target.getId(), contact.getSearchKey());
      if (targetContact == null) {
        targetContact = OBProvider.getInstance().get(BusinessPartner.class);
        targetContact.setClient(target);
        targetContact.setOrganization(targetOrg);
      }
      copyBusinessPartnerFields(contact, targetContact);
      targetContact.setBusinessPartnerCategory(targetBusinessPartnerCategory(
          contact.getBusinessPartnerCategory(), target, targetOrg));
      OBDal.getInstance().save(targetContact);
      Map<String, Location> targetLocations = copyBusinessPartnerLocations(
          contact, targetContact, target, targetOrg);
      copyBusinessPartnerPersons(contact, targetContact, target, targetOrg, targetLocations);
      progress(target, CONTACTS_DONE, ++completed);
      OBDal.getInstance().flush();
    }
  }

  /** Product fields mirror the import contract; prices/costing are added through their own records. */
  private void copyProductFields(Product source, Product target) {
    DemoDataTransferReflection.copy(source, target, SEARCH_KEY_PROPERTY, "Name", DESCRIPTION_PROPERTY, "ProductType");
  }

  /** UOM rows can be client-scoped; never attach a source tenant's entity to the new product. */
  private UOM targetUom(UOM source, Client target, Organization targetOrg) {
    if (source == null) throw new IllegalStateException("Source product has no unit of measure");
    if (ZERO_ID.equals(source.getClient().getId())) return source;
    UOM existing = unique(UOM.class, "as u where u.client.id = :clientId and u.name = :key",
        target.getId(), source.getName());
    if (existing != null) return existing;
    UOM copy = OBProvider.getInstance().get(UOM.class);
    copy.setClient(target); copy.setOrganization(targetOrg);
    copy.setName(source.getName());
    copy.setSymbol(source.getSymbol());
    copy.setEDICode(source.getEDICode());
    copy.setStandardPrecision(source.getStandardPrecision());
    copy.setCostingPrecision(source.getCostingPrecision());
    copy.setUOMType(source.getUOMType());
    OBDal.getInstance().save(copy);
    return copy;
  }

  /** Tax categories are provisioned with accounting; an absent match needs an operator fix. */
  private TaxCategory targetTaxCategory(TaxCategory source, Client target) {
    if (source == null) throw new IllegalStateException("Source product has no tax category");
    if (ZERO_ID.equals(source.getClient().getId())) return source;
    TaxCategory category = unique(TaxCategory.class,
        "as c where c.client.id = :clientId and c.name = :key", target.getId(), source.getName());
    if (category == null) {
      throw new IllegalStateException("Target tax category is missing: " + source.getName());
    }
    return category;
  }

  private ProductCategory targetProductCategory(ProductCategory source, Client target,
      Organization targetOrg) {
    if (source == null) return null;
    if (ZERO_ID.equals(source.getClient().getId())) return source;
    ProductCategory existing = unique(ProductCategory.class,
        "as c where c.client.id = :clientId and c.name = :key", target.getId(), source.getName());
    if (existing != null) return existing;
    ProductCategory copy = OBProvider.getInstance().get(ProductCategory.class);
    copy.setClient(target); copy.setOrganization(targetOrg);
    DemoDataTransferReflection.copy(source, copy, "Name", SEARCH_KEY_PROPERTY, DESCRIPTION_PROPERTY);
    OBDal.getInstance().save(copy);
    return copy;
  }

  /** Copies the source product's displayed sales and purchase prices onto the target defaults. */
  private void copyPrices(Product source, Product target, Client targetClient, Organization targetOrg) {
    List<ProductPrice> prices = query(ProductPrice.class, "as pp where pp.product.id = :clientId", source.getId());
    boolean copiedSales = false;
    boolean copiedPurchase = false;
    for (ProductPrice sourcePrice : prices) {
      Optional<Boolean> salesPriceList = DemoDataTransferReflection.salesPriceList(sourcePrice);
      if (salesPriceList.isEmpty()) {
        throw new IllegalStateException("Cannot classify source product price list");
      }
      boolean sales = salesPriceList.get();
      boolean alreadyCopied = sales ? copiedSales : copiedPurchase;
      if (copyPriceIfNeeded(sourcePrice, target, targetClient, targetOrg, sales, alreadyCopied)) {
        copiedSales |= sales;
        copiedPurchase |= !sales;
      }
    }
  }

  private boolean copyPriceIfNeeded(ProductPrice sourcePrice, Product targetProduct,
      Client targetClient, Organization targetOrg, boolean sales, boolean alreadyCopied) {
    if (alreadyCopied) return false;
    PriceListVersion version = targetVersion(targetClient.getId(), targetOrg.getId(), sales);
    if (version == null) {
      throw new IllegalStateException("Target " + (sales ? "sales" : "purchase")
          + " price list version is missing");
    }
    ProductPrice targetProductPrice = targetPrice(targetProduct.getId(), version.getId());
    if (targetProductPrice == null) {
      targetProductPrice = OBProvider.getInstance().get(ProductPrice.class);
      targetProductPrice.setClient(targetClient);
      targetProductPrice.setOrganization(targetOrg);
      targetProductPrice.setProduct(targetProduct);
      targetProductPrice.setPriceListVersion(version);
    }
    DemoDataTransferReflection.copy(sourcePrice, targetProductPrice,
        "StandardPrice", "ListPrice", "PriceLimit");
    targetProductPrice.setActive(true);
    OBDal.getInstance().save(targetProductPrice);
    return true;
  }

  /** Cost is a history row, not a product column: migrate the current row and its start date. */
  private void copyCurrentCost(Product source, Product target, Client targetClient, Organization targetOrg) {
    List<Costing> costs = query(Costing.class,
        "as c where c.product.id = :clientId and c.active = true order by c.startingDate desc", source.getId());
    if (costs.isEmpty()) return;
    Costing sourceCost = costs.get(0);
    Costing copy = targetCost(target.getId(), sourceCost.getStartingDate());
    if (copy == null) {
      copy = OBProvider.getInstance().get(Costing.class);
      copy.setClient(targetClient);
      copy.setOrganization(targetOrg);
      copy.setProduct(target);
    }
    DemoDataTransferReflection.copy(sourceCost, copy, "Cost", "StartingDate", "EndingDate", "CostType", "Manual", "Permanent", "Production", "Currency");
    OBDal.getInstance().save(copy);
  }

  /** Resolves a target tariff within the transfer's explicit tenant and organization scope. */
  private PriceListVersion targetVersion(String clientId, String orgId, boolean sales) {
    String[] orgsToTry = ZERO_ID.equals(orgId) ? new String[] { ZERO_ID }
        : new String[] { orgId, ZERO_ID };
    OBContext.setAdminMode();
    try {
      // Prefer explicitly default lists across both organization scopes, then retain the
      // resolver's historical fallback to any active list if no default is configured.
      for (boolean requireDefault : new boolean[] { true, false }) {
        for (String candidateOrgId : orgsToTry) {
          OBCriteria<PriceListVersion> criteria = OBDal.getInstance()
              .createCriteria(PriceListVersion.class);
          // This worker runs as System and resolves only the tenant/org IDs it obtained for the
          // transfer target. Avoid System's readable-org filter hiding that exact target org.
          criteria.setFilterOnReadableClients(false);
          criteria.setFilterOnReadableOrganization(false);
          criteria.add(Restrictions.eq(PriceListVersion.PROPERTY_CLIENT + ".id", clientId));
          criteria.add(Restrictions.eq(PriceListVersion.PROPERTY_ORGANIZATION + ".id",
              candidateOrgId));
          criteria.add(Restrictions.eq(PriceListVersion.PROPERTY_ACTIVE, true));
          criteria.createAlias(PriceListVersion.PROPERTY_PRICELIST, "pl");
          criteria.add(Restrictions.eq("pl." + PriceList.PROPERTY_ACTIVE, true));
          criteria.add(Restrictions.eq("pl." + PriceList.PROPERTY_SALESPRICELIST, sales));
          if (requireDefault) {
            criteria.add(Restrictions.eq("pl." + PriceList.PROPERTY_DEFAULT, true));
          }
          criteria.addOrder(Order.desc(PriceListVersion.PROPERTY_VALIDFROMDATE));
          criteria.setMaxResults(1);
          List<PriceListVersion> matches = criteria.list();
          if (!matches.isEmpty()) {
            return matches.get(0);
          }
        }
      }
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private ProductPrice targetPrice(String productId, String versionId) {
    OBQuery<ProductPrice> query = OBDal.getInstance().createQuery(ProductPrice.class,
        "as pp where pp.product.id = :productId and pp.priceListVersion.id = :versionId");
    query.setNamedParameter("productId", productId); query.setNamedParameter("versionId", versionId);
    query.setFilterOnReadableClients(false); query.setFilterOnReadableOrganization(false); query.setMaxResult(1);
    return query.uniqueResult();
  }

  private Costing targetCost(String productId, java.util.Date startingDate) {
    OBQuery<Costing> query = OBDal.getInstance().createQuery(Costing.class,
        "as c where c.product.id = :productId and c.startingDate = :startingDate");
    query.setNamedParameter("productId", productId); query.setNamedParameter("startingDate", startingDate);
    query.setFilterOnReadableClients(false); query.setFilterOnReadableOrganization(false); query.setMaxResult(1);
    return query.uniqueResult();
  }

  /** Deliberately no CIF validation: existing demo contacts are authoritative migration input. */
  private void copyBusinessPartnerFields(BusinessPartner source, BusinessPartner target) {
    DemoDataTransferReflection.copy(source, target, SEARCH_KEY_PROPERTY, "Name", "TaxID",
        "Name2", DESCRIPTION_PROPERTY, "URL", "ReferenceNo", "Customer", "Vendor", "Employee",
        "EtgoIdentifier", "EtgoIsperson", "EtgoFirstname", "EtgoLastname", "EtgoEmail",
        "EtgoPhone", "EtgoWeb");
  }

  /** Copies the address rows shown in the Contact window's Location/Address tab. */
  private Map<String, Location> copyBusinessPartnerLocations(BusinessPartner source,
      BusinessPartner target, Client targetClient, Organization targetOrg) {
    List<Location> sourceLocations = query(Location.class,
        "as l where l.businessPartner.id = :clientId", source.getId());
    List<Location> targetLocations = query(Location.class,
        "as l where l.businessPartner.id = :clientId", target.getId());
    List<org.openbravo.model.common.geography.Location> targetAddresses = query(
        org.openbravo.model.common.geography.Location.class,
        "as l where l.client.id = :clientId", targetClient.getId());
    Map<String, Location> sourceToTarget = new HashMap<>();

    for (Location sourceLocation : sourceLocations) {
      org.openbravo.model.common.geography.Location sourceAddress = sourceLocation.getLocationAddress();
      org.openbravo.model.common.geography.Location targetAddress = targetAddress(
          sourceAddress, targetClient, targetOrg, targetAddresses);
      Location targetLocation = targetLocations.stream()
          .filter(candidate -> sameBusinessPartnerLocation(sourceLocation, candidate))
          .findFirst().orElse(null);
      if (targetLocation == null) {
        targetLocation = OBProvider.getInstance().get(Location.class);
        targetLocation.setClient(targetClient);
        targetLocation.setOrganization(targetOrg);
        targetLocation.setBusinessPartner(target);
        targetLocations.add(targetLocation);
      }
      DemoDataTransferReflection.copy(sourceLocation, targetLocation, "Name", "InvoiceToAddress",
          "ShipToAddress", "PayFromAddress", "RemitToAddress", "Phone", "AlternativePhone",
          "Fax", "TaxLocation", "UPCEAN", "Active");
      targetLocation.setLocationAddress(targetAddress);
      OBDal.getInstance().save(targetLocation);
      sourceToTarget.put(sourceLocation.getId(), targetLocation);
    }
    return sourceToTarget;
  }

  private org.openbravo.model.common.geography.Location targetAddress(
      org.openbravo.model.common.geography.Location sourceAddress, Client targetClient,
      Organization targetOrg,
      List<org.openbravo.model.common.geography.Location> targetAddresses) {
    if (sourceAddress == null) return null;
    org.openbravo.model.common.geography.Location existing = targetAddresses.stream()
        .filter(candidate -> sameAddress(sourceAddress, candidate)).findFirst().orElse(null);
    if (existing != null) return existing;

    org.openbravo.model.common.geography.Location copy = OBProvider.getInstance()
        .get(org.openbravo.model.common.geography.Location.class);
    copy.setClient(targetClient);
    copy.setOrganization(targetOrg);
    DemoDataTransferReflection.copy(sourceAddress, copy, "AddressLine1", "AddressLine2",
        "CityName", "PostalCode", "PostalAdd", "RegionName");
    copy.setCountry(sharedAddressReference(sourceAddress.getCountry(), "country"));
    copy.setRegion(sharedAddressReference(sourceAddress.getRegion(), "region"));
    copy.setCity(sharedAddressReference(sourceAddress.getCity(), "city"));
    OBDal.getInstance().save(copy);
    targetAddresses.add(copy);
    return copy;
  }

  private <T extends BaseOBObject> T sharedAddressReference(T reference, String label) {
    if (reference == null) return null;
    if (!ZERO_ID.equals(((org.openbravo.base.structure.ClientEnabled) reference).getClient().getId())) {
      throw new IllegalStateException("Contact address references a tenant-specific " + label);
    }
    return reference;
  }

  private boolean sameAddress(org.openbravo.model.common.geography.Location left,
      org.openbravo.model.common.geography.Location right) {
    if (left == null || right == null) return left == right;
    return Objects.equals(left.getAddressLine1(), right.getAddressLine1())
        && Objects.equals(left.getAddressLine2(), right.getAddressLine2())
        && Objects.equals(left.getCityName(), right.getCityName())
        && Objects.equals(left.getPostalCode(), right.getPostalCode())
        && Objects.equals(left.getPostalAdd(), right.getPostalAdd())
        && Objects.equals(left.getRegionName(), right.getRegionName())
        && sameReference(left.getCountry(), right.getCountry())
        && sameReference(left.getRegion(), right.getRegion())
        && sameReference(left.getCity(), right.getCity());
  }

  private boolean sameBusinessPartnerLocation(Location left, Location right) {
    return Objects.equals(left.getName(), right.getName())
        && sameAddress(left.getLocationAddress(), right.getLocationAddress())
        && Objects.equals(left.getPhone(), right.getPhone())
        && Objects.equals(left.getAlternativePhone(), right.getAlternativePhone())
        && Objects.equals(left.getFax(), right.getFax())
        && Objects.equals(left.isInvoiceToAddress(), right.isInvoiceToAddress())
        && Objects.equals(left.isShipToAddress(), right.isShipToAddress())
        && Objects.equals(left.isPayFromAddress(), right.isPayFromAddress())
        && Objects.equals(left.isRemitToAddress(), right.isRemitToAddress())
        && Objects.equals(left.isTaxLocation(), right.isTaxLocation());
  }

  private boolean sameReference(BaseOBObject left, BaseOBObject right) {
    return Objects.equals(left == null ? null : left.getId(), right == null ? null : right.getId());
  }

  /** Copies the person/contact rows shown in the Contact window's Contact tab. */
  private void copyBusinessPartnerPersons(BusinessPartner source, BusinessPartner target,
      Client targetClient, Organization targetOrg, Map<String, Location> targetLocations) {
    List<User> sourceUsers = query(User.class,
        "as u where u.businessPartner.id = :clientId", source.getId());
    List<User> targetUsers = query(User.class,
        "as u where u.businessPartner.id = :clientId", target.getId());
    for (User sourceUser : sourceUsers) {
      User targetUser = targetUsers.stream().filter(candidate -> sameContactPerson(sourceUser, candidate))
          .findFirst().orElse(null);
      if (targetUser == null) {
        targetUser = OBProvider.getInstance().get(User.class);
        targetUser.setClient(targetClient);
        targetUser.setOrganization(targetOrg);
        targetUser.setBusinessPartner(target);
        targetUsers.add(targetUser);
      }
      DemoDataTransferReflection.copy(sourceUser, targetUser, "FirstName", "LastName", "Name",
          "Email", "Phone", "AlternativePhone", "Position", "Comments", "Active",
          "Defaultfordocs", "GrantPortalAccess", "Commercialauth", "Viasms", "Viaemail");
      Location sourcePartnerAddress = sourceUser.getPartnerAddress();
      Location targetPartnerAddress = sourcePartnerAddress == null ? null
          : targetLocations.get(sourcePartnerAddress.getId());
      if (sourcePartnerAddress != null && targetPartnerAddress == null) {
        throw new IllegalStateException("Contact person's address was not transferred");
      }
      targetUser.setPartnerAddress(targetPartnerAddress);
      OBDal.getInstance().save(targetUser);
    }
  }

  private boolean sameContactPerson(User left, User right) {
    return Objects.equals(left.getName(), right.getName())
        && Objects.equals(left.getFirstName(), right.getFirstName())
        && Objects.equals(left.getLastName(), right.getLastName())
        && Objects.equals(left.getEmail(), right.getEmail());
  }

  private Category targetBusinessPartnerCategory(Category source,
      Client target, Organization targetOrg) {
    if (source == null) return null;
    if (ZERO_ID.equals(source.getClient().getId())) return source;
    Category existing = unique(Category.class,
        "as c where c.client.id = :clientId and c.name = :key", target.getId(), source.getName());
    if (existing != null) return existing;
    Category copy = OBProvider.getInstance().get(Category.class);
    copy.setClient(target); copy.setOrganization(targetOrg);
    DemoDataTransferReflection.copy(source, copy, "Name", SEARCH_KEY_PROPERTY, DESCRIPTION_PROPERTY);
    OBDal.getInstance().save(copy);
    return copy;
  }

  private JSONObject statusJson(String clientId) throws JSONException {
    JSONObject result = new JSONObject();
    String status = StringUtils.defaultIfBlank(readClientPreference(STATUS, clientId), STATUS_NOT_REQUESTED);
    result.put("status", status);
    result.put("products", counts(clientId, PRODUCTS_DONE, PRODUCTS_TOTAL));
    result.put("contacts", counts(clientId, CONTACTS_DONE, CONTACTS_TOTAL));
    if (STATUS_FAILED.equals(status)) result.put("failureReason", readClientPreference(FAILURE, clientId));
    return result;
  }

  private JSONObject counts(String clientId, String done, String total) throws JSONException {
    JSONObject counts = new JSONObject();
    counts.put("completed", number(readClientPreference(done, clientId)));
    counts.put("total", number(readClientPreference(total, clientId)));
    return counts;
  }

  private Organization defaultOrganization(String clientId) {
    return unique(Organization.class, "as o where o.client.id = :clientId and o.active = true and o.name <> '*'", clientId, null);
  }

  private void progress(Client client, String attribute, int value) {
    setClientPreference(attribute, String.valueOf(value), client);
  }

  private boolean selected(String selection, int index) { return selection.length() > index && selection.charAt(index) == 'Y'; }
  private int number(String value) { try { return Integer.parseInt(StringUtils.defaultIfBlank(value, "0")); } catch (NumberFormatException e) { return 0; } }

  private <T extends BaseOBObject> List<T> query(Class<T> type, String where, String clientId) {
    OBQuery<T> query = OBDal.getInstance().createQuery(type, where);
    query.setNamedParameter(CLIENT_ID_PARAMETER, clientId);
    query.setFilterOnReadableClients(false); query.setFilterOnReadableOrganization(false);
    return query.list();
  }
  private <T extends BaseOBObject> T unique(Class<T> type, String where, String clientId, String key) {
    OBQuery<T> query = OBDal.getInstance().createQuery(type, where);
    query.setNamedParameter(CLIENT_ID_PARAMETER, clientId);
    if (key != null) query.setNamedParameter("key", key);
    query.setFilterOnReadableClients(false); query.setFilterOnReadableOrganization(false); query.setMaxResult(1);
    return query.uniqueResult();
  }
  private void setClientPreference(String attribute, String value, Client client) {
    Preferences.setPreferenceValue(attribute, value, false, client, null, null, null, null, null);
  }
  private void setSystemPreference(String attribute, String value) {
    Preferences.setPreferenceValue(attribute, value, false, OBDal.getInstance().get(Client.class, ZERO_ID), null, null, null, null, null);
  }
  private String readSystemPreference(String attribute) { return readClientPreference(attribute, ZERO_ID); }
  private String readClientPreference(String attribute, String clientId) {
    OBQuery<Preference> query = OBDal.getInstance().createQuery(Preference.class,
        "as p where p.attribute = :attribute and p.visibleAtClient.id = :clientId and p.active = true"
            + " order by p.updated desc, p.creationDate desc, p.id desc");
    query.setNamedParameter("attribute", attribute); query.setNamedParameter(CLIENT_ID_PARAMETER, clientId);
    query.setFilterOnReadableClients(false); query.setFilterOnReadableOrganization(false); query.setMaxResult(1);
    Preference preference = query.uniqueResult();
    return preference == null ? null : StringUtils.trimToNull(preference.getSearchKey());
  }
  private void flush() { OBDal.getInstance().flush(); OBDal.getInstance().commitAndClose(); }
  private void rollbackFailedTransfer() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } catch (RuntimeException rollbackError) {
      log.warn("Could not roll back failed demo data transfer", rollbackError);
    }
  }
  private void withSystemContext(Runnable action) { OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID); OBContext.setAdminMode(true); try { action.run(); } finally { OBContext.restorePreviousMode(); } }
  private <T> T withSystemResult(ResultSupplier<T> action) throws JSONException {
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      return action.get();
    } finally {
      OBContext.restorePreviousMode();
    }
  }
  @FunctionalInterface private interface ResultSupplier<T> { T get() throws JSONException; }
}
