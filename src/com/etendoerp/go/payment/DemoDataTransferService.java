/* Etendo License. */
package com.etendoerp.go.payment;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.tax.TaxCategory;


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
  private static final String SOURCE_UNAVAILABLE = "Source demo environment is unavailable";
  private static final String SAME_ENVIRONMENT = "Source and target environments are the same";
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
  private final DemoDataTransferEntityCopier entityCopier =
      new DemoDataTransferEntityCopier(this);

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

  /** Returns the checkout's immutable server-side choice, or null for an older purchase.
   * @param requestId checkout request identifier
   * @return immutable product/contact selection, or {@code null} when absent
   * @throws JSONException if the selection cannot be serialized
   */
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
    withSystemContext(() -> persistStart(requestId, demoClientId, productiveClientId));
    submitIfRunning(productiveClientId);
  }

  private void persistStart(String requestId, String demoClientId, String productiveClientId) {
    String selection = readSystemPreference(SELECTION_PREFIX + requestId);
    if (selection == null) return;
    Client target = OBDal.getInstance().get(Client.class, productiveClientId);
    if (target == null) return;
    persistSelection(target, selection, demoClientId, productiveClientId);
    flush();
  }

  private void persistSelection(Client target, String selection, String demoClientId,
      String productiveClientId) {
    if (StringUtils.isNotBlank(demoClientId)) setClientPreference(SOURCE, demoClientId, target);
    boolean productsSelected = selected(selection, 0);
    boolean contactsSelected = selected(selection, 1);
    setClientPreference(PRODUCTS, productsSelected ? "Y" : "N", target);
    setClientPreference(CONTACTS, contactsSelected ? "Y" : "N", target);
    if (!productsSelected && !contactsSelected) {
      setClientPreference(STATUS, STATUS_SKIPPED, target);
    } else if (StringUtils.isBlank(demoClientId)) {
      failForMissingSource(target, productiveClientId);
    } else if (demoClientId.equals(productiveClientId)) {
      setClientPreference(STATUS, STATUS_FAILED, target);
      setClientPreference(FAILURE, SAME_ENVIRONMENT, target);
    } else if (!STATUS_COMPLETED.equals(readClientPreference(STATUS, productiveClientId))) {
      markRunning(target);
    }
  }

  private void failForMissingSource(Client target, String productiveClientId) {
    log.error("Demo data transfer cannot start for productive client {}: source demo "
        + "environment is unavailable", productiveClientId);
    setClientPreference(STATUS, STATUS_FAILED, target);
    setClientPreference(FAILURE, SOURCE_UNAVAILABLE, target);
  }

  private void markRunning(Client target) {
    setClientPreference(STATUS, STATUS_RUNNING, target);
    setClientPreference(PRODUCTS_DONE, "0", target);
    setClientPreference(CONTACTS_DONE, "0", target);
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
        setClientPreference(FAILURE, SOURCE_UNAVAILABLE, target);
      } else if (productiveClientId.equals(resolvedDemoClientId)) {
        setClientPreference(STATUS, STATUS_FAILED, target);
        setClientPreference(FAILURE, SAME_ENVIRONMENT, target);
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
        setClientPreference(FAILURE, SOURCE_UNAVAILABLE, target);
        flush();
        return;
      }
      if (productiveClientId.equals(sourceId)) {
        setClientPreference(STATUS, STATUS_FAILED, target);
        setClientPreference(FAILURE, SAME_ENVIRONMENT, target);
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
      withSystemContext(() -> runInSystemContext(productiveClientId));
    } catch (RuntimeException e) {
      log.error("Demo data transfer failed for productive client {}", productiveClientId, e);
      rollbackFailedTransfer();
      withSystemContext(() -> recordFailure(productiveClientId, e));
    }
  }

  private void runInSystemContext(String productiveClientId) {
    if (!STATUS_RUNNING.equals(readClientPreference(STATUS, productiveClientId))) return;
    String sourceId = readClientPreference(SOURCE, productiveClientId);
    Client target = OBDal.getInstance().get(Client.class, productiveClientId);
    Client source = OBDal.getInstance().get(Client.class, sourceId);
    if (target == null || source == null) {
      throw new IllegalStateException("Transfer environment is unavailable");
    }
    if (sourceId.equals(productiveClientId)) throw new IllegalStateException(SAME_ENVIRONMENT);
    Organization targetOrg = defaultOrganization(target.getId());
    if (targetOrg == null) throw new IllegalStateException("Target organization is unavailable");
    if ("Y".equals(readClientPreference(PRODUCTS, productiveClientId))) {
      copyProducts(source, target, targetOrg);
    }
    if ("Y".equals(readClientPreference(CONTACTS, productiveClientId))) {
      copyContacts(source, target, targetOrg);
    }
    setClientPreference(STATUS, STATUS_COMPLETED, target);
    flush();
  }

  private void recordFailure(String productiveClientId, RuntimeException error) {
    Client target = OBDal.getInstance().get(Client.class, productiveClientId);
    if (target == null) return;
    setClientPreference(STATUS, STATUS_FAILED, target);
    setClientPreference(FAILURE,
        StringUtils.abbreviate(StringUtils.defaultString(error.getMessage()), 240), target);
    flush();
  }

  private void copyProducts(Client source, Client target, Organization targetOrg) {
    entityCopier.copyProducts(source, target, targetOrg);
  }

  private void copyContacts(Client source, Client target, Organization targetOrg) {
    entityCopier.copyContacts(source, target, targetOrg);
  }

  private TaxCategory targetTaxCategory(TaxCategory source, Client target) {
    return entityCopier.targetTaxCategory(source, target);
  }

  private void copyPrices(Product source, Product target, Client targetClient,
      Organization targetOrg) {
    entityCopier.copyPrices(source, target, targetClient, targetOrg);
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

  void progress(Client client, String attribute, int value) {
    setClientPreference(attribute, String.valueOf(value), client);
  }

  private boolean selected(String selection, int index) { return selection.length() > index && selection.charAt(index) == 'Y'; }
  private int number(String value) { try { return Integer.parseInt(StringUtils.defaultIfBlank(value, "0")); } catch (NumberFormatException e) { return 0; } }

  <T extends BaseOBObject> List<T> query(Class<T> type, String where, String clientId) {
    OBQuery<T> query = OBDal.getInstance().createQuery(type, where);
    query.setNamedParameter(CLIENT_ID_PARAMETER, clientId);
    query.setFilterOnReadableClients(false); query.setFilterOnReadableOrganization(false);
    return query.list();
  }
  <T extends BaseOBObject> T unique(Class<T> type, String where, String clientId, String key) {
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
