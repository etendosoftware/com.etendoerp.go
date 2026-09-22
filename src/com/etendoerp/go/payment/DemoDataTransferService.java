/* Etendo License. */
package com.etendoerp.go.payment;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Category;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.plm.ProductCategory;
import org.openbravo.model.pricing.pricelist.PriceListVersion;
import org.openbravo.model.pricing.pricelist.ProductPrice;
import org.openbravo.model.materialmgmt.cost.Costing;
import org.openbravo.model.common.uom.UOM;

import com.etendoerp.go.schemaforge.PriceListVersionResolver;

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
  private static final String SELECTION_PREFIX = "ETGO_DemoDataTransferSelection.";
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

  private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "etgo-demo-data-transfer");
    thread.setDaemon(true);
    return thread;
  });
  private final Set<String> activeClients = ConcurrentHashMap.newKeySet();

  /** Stores user intent before redirecting to the payment provider.
   * @param requestId payment request identifier
   * @param products whether products should be transferred
   * @param contacts whether business partners should be transferred
   */
  public void recordSelection(String requestId, boolean products, boolean contacts) {
    if (StringUtils.isBlank(requestId)) return;
    withSystemContext(() -> {
      setSystemPreference(SELECTION_PREFIX + requestId,
          (products ? "Y" : "N") + (contacts ? "Y" : "N"));
      flush();
    });
  }

  /** Creates the durable tenant projection and starts work after provisioning commits.
   * @param requestId payment request identifier
   * @param demoClientId source demo client identifier
   * @param productiveClientId provisioned client identifier
   */
  public void start(String requestId, String demoClientId, String productiveClientId) {
    if (StringUtils.isBlank(requestId) || StringUtils.isBlank(demoClientId)
        || StringUtils.isBlank(productiveClientId)) return;
    withSystemContext(() -> {
      String selection = readSystemPreference(SELECTION_PREFIX + requestId);
      if (selection == null) return;
      Client target = OBDal.getInstance().get(Client.class, productiveClientId);
      if (target == null) return;
      setClientPreference(SOURCE, demoClientId, target);
      setClientPreference(PRODUCTS, selected(selection, 0) ? "Y" : "N", target);
      setClientPreference(CONTACTS, selected(selection, 1) ? "Y" : "N", target);
      if (!selected(selection, 0) && !selected(selection, 1)) {
        setClientPreference(STATUS, STATUS_SKIPPED, target);
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
    JSONObject result = withSystemResult(() -> statusJson(productiveClientId));
    if (STATUS_RUNNING.equals(result.optString("status"))) submitIfRunning(productiveClientId);
    return result;
  }

  /** Only a failed transfer can be retried. Completed and skipped work is immutable.
   * @param productiveClientId provisioned client identifier
   * @return current transfer status and progress
   * @throws JSONException if the status projection cannot be serialized
   */
  public JSONObject retry(String productiveClientId) throws JSONException {
    withSystemContext(() -> {
      if (!STATUS_FAILED.equals(readClientPreference(STATUS, productiveClientId))) return;
      Client target = OBDal.getInstance().get(Client.class, productiveClientId);
      if (target == null) return;
      setClientPreference(STATUS, STATUS_RUNNING, target);
      setClientPreference(FAILURE, "", target);
      flush();
    });
    submitIfRunning(productiveClientId);
    return status(productiveClientId);
  }

  private void submitIfRunning(String productiveClientId) {
    if (!activeClients.add(productiveClientId)) return;
    executor.submit(() -> {
      try {
        run(productiveClientId);
      } finally {
        activeClients.remove(productiveClientId);
      }
    });
  }

  private void run(String productiveClientId) {
    try {
      withSystemContext(() -> {
        if (!STATUS_RUNNING.equals(readClientPreference(STATUS, productiveClientId))) return;
        String sourceId = readClientPreference(SOURCE, productiveClientId);
        Client target = OBDal.getInstance().get(Client.class, productiveClientId);
        Client source = OBDal.getInstance().get(Client.class, sourceId);
        if (target == null || source == null) throw new IllegalStateException("Transfer environment is unavailable");
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
    if (source == null) return null;
    UOM existing = unique(UOM.class, "as u where u.client.id = :clientId and u.name = :key",
        target.getId(), source.getName());
    if (existing != null) return existing;
    UOM copy = OBProvider.getInstance().get(UOM.class);
    copy.setClient(target); copy.setOrganization(targetOrg);
    DemoDataTransferReflection.copy(source, copy, "Name", "Symbol", "X", "StandardPrecision", "CostingPrecision", "UOMType");
    OBDal.getInstance().save(copy);
    return copy;
  }

  private ProductCategory targetProductCategory(ProductCategory source, Client target,
      Organization targetOrg) {
    if (source == null) return null;
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
      if (salesPriceList.isEmpty()) continue;
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
    if (version == null) return false;
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

  /** Uses the same configured-default resolver as the product form/import path. */
  private PriceListVersion targetVersion(String clientId, String orgId, boolean sales) {
    OBContext.setOBContext(ZERO_ID, ZERO_ID, clientId, orgId);
    try {
      String versionId = PriceListVersionResolver.resolveDefaultVersionId(OBContext.getOBContext(), sales);
      return StringUtils.isBlank(versionId) ? null : OBDal.getInstance().get(PriceListVersion.class, versionId);
    } finally {
      // The worker entered under system context. Restore it before copying the next independent row.
      OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
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
    DemoDataTransferReflection.copy(source, target, SEARCH_KEY_PROPERTY, "Name", "TaxID", "Customer", "Vendor", "Employee",
        "EMEtgoIsperson", "EMEtgoFirstname", "EMEtgoLastname", "EMEtgoEmail", "EMEtgoPhone", "EMEtgoWeb");
  }

  private Category targetBusinessPartnerCategory(Category source,
      Client target, Organization targetOrg) {
    if (source == null) return null;
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
        "as p where p.attribute = :attribute and p.visibleAtClient.id = :clientId and p.active = true");
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
