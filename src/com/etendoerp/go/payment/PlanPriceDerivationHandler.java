/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;

import javax.enterprise.event.Observes;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.erpCommon.utility.OBMessageUtils;

import com.etendoerp.go.schemaforge.data.Plan;

/**
 * Derives a plan's price, currency and billing interval from the payment provider when the plan
 * is saved, so those three columns are never hand-typed and can never disagree with what the
 * buyer will actually be charged.
 *
 * <p>The observer is <b>fail-closed</b>: a price that cannot be verified does not save. That is
 * deliberate and it is the point — a plan row whose displayed price differs from the charged one
 * is a commercial problem discovered by a customer, whereas a refused save is a problem
 * discovered by the person making the mistake, immediately, with a message naming it.
 *
 * <p>Two branches call the provider <b>zero</b> times, and both are load-bearing rather than
 * optimisations; see {@link #derive}.
 *
 * <p>Registration is zero-config: {@code etendo-resources/META-INF/beans.xml} declares
 * {@code bean-discovery-mode="all"}, so this class is discovered as a CDI observer by being on
 * the classpath.
 */
public class PlanPriceDerivationHandler extends EntityPersistenceEventObserver {

  private static final Logger log = LogManager.getLogger(PlanPriceDerivationHandler.class);

  private static final String MSG_NOT_FOUND = "ETGO_PlanPriceNotFound";
  private static final String MSG_UNAUTHORIZED = "ETGO_PlanPriceUnauthorized";
  private static final String MSG_REJECTED = "ETGO_PlanPriceRejected";
  private static final String MSG_UNREACHABLE = "ETGO_PlanPriceUnreachable";
  private static final String MSG_NOT_RECURRING = "ETGO_PlanPriceNotRecurring";
  private static final String MSG_ARCHIVED = "ETGO_PlanPriceArchived";
  private static final String MSG_INTERVAL_COUNT = "ETGO_PlanIntervalCountUnsupported";
  private static final String MSG_INTERVAL_MISMATCH = "ETGO_PlanIntervalMismatch";

  private static final String RESOURCE_MISSING = "resource_missing";
  private static final String TYPE_RECURRING = "recurring";

  private static Entity[] entities;

  /**
   * The provider gateway, package-visible so a test can swap in a recording double. Defaulted
   * rather than injected through CDI because this observer is instantiated by the container per
   * event and the gateway is stateless.
   */
  StripeApiClient stripeApiClient = new HttpUrlConnectionStripeApiClient();

  private static Entity[] resolveEntities() {
    if (entities == null) {
      entities = new Entity[] { ModelProvider.getInstance().getEntity(Plan.ENTITY_NAME) };
    }
    return entities;
  }

  @Override
  protected Entity[] getObservedEntities() {
    return resolveEntities();
  }

  /**
   * Derives the price of a newly created plan.
   *
   * @param event the DAL insert event
   */
  public void onNew(@Observes EntityNewEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    derive(event, null);
  }

  /**
   * Derives the price of an edited plan.
   *
   * @param event the DAL update event, which also carries the previous state
   */
  public void onUpdate(@Observes EntityUpdateEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    derive(event, event);
  }

  /**
   * Applies the derivation to the row currently being flushed.
   *
   * <p>Three branches, in this order:
   *
   * <ol>
   *   <li><b>No provider price id</b> — the grandfathered legacy plan, which predates provider
   *       billing and is not sold through it. Price, currency and sync stamp are cleared and the
   *       provider is <b>not called at all</b>: there is nothing to ask it about, and asking
   *       would make a plan that has no relationship with the provider fail to save whenever the
   *       provider is down.</li>
   *   <li><b>An update whose price id did not change</b> — every value is re-asserted from the
   *       previous state and the provider is again <b>not called</b>. Without this branch, every
   *       unrelated edit — a rename, an ISACTIVE toggle, adding a quota row in the child tab —
   *       would call the provider, and the Plans window would become unusable for the duration of
   *       any provider outage, for edits that have nothing to do with pricing. The same branch
   *       also delivers the "never hand-typed" guarantee without a round trip: a displayPrice
   *       someone typed over in the window is silently reverted to the stored, provider-derived
   *       value.</li>
   *   <li><b>A new or changed price id</b> — the only case that costs a call.</li>
   * </ol>
   *
   * @param event the persistence event being flushed
   * @param update the same event when it is an update, null on insert
   */
  private void derive(EntityPersistenceEvent event, EntityUpdateEvent update) {
    Plan plan = (Plan) event.getTargetInstance();
    Entity entity = plan.getEntity();
    Property priceIdProperty = entity.getProperty(Plan.PROPERTY_PROVIDERPRICEID);
    Property displayPriceProperty = entity.getProperty(Plan.PROPERTY_DISPLAYPRICE);
    Property currencyProperty = entity.getProperty(Plan.PROPERTY_CURRENCYCODE);
    Property intervalProperty = entity.getProperty(Plan.PROPERTY_BILLINGINTERVAL);
    Property syncedAtProperty = entity.getProperty(Plan.PROPERTY_PRICESYNCEDAT);

    String priceId = StringUtils.trimToNull(asString(event.getCurrentState(priceIdProperty)));
    if (priceId == null) {
      event.setCurrentState(displayPriceProperty, null);
      event.setCurrentState(currencyProperty, null);
      event.setCurrentState(syncedAtProperty, null);
      return;
    }

    if (update != null) {
      String previousPriceId = StringUtils
          .trimToNull(asString(update.getPreviousState(priceIdProperty)));
      if (StringUtils.equals(priceId, previousPriceId)) {
        event.setCurrentState(displayPriceProperty, update.getPreviousState(displayPriceProperty));
        event.setCurrentState(currencyProperty, update.getPreviousState(currencyProperty));
        event.setCurrentState(intervalProperty, update.getPreviousState(intervalProperty));
        return;
      }
    }

    String planKey = StringUtils.defaultIfBlank(plan.getSearchKey(), plan.getId());
    String declaredInterval = asString(event.getCurrentState(intervalProperty));
    boolean planActive = !Boolean.FALSE.equals(event.getCurrentState(entity.getProperty(
        Plan.PROPERTY_ACTIVE)));

    DerivedPrice derived = resolvePrice(planKey, priceId, declaredInterval, planActive);

    // setCurrentState, never the entity's setters: by the time this event fires Hibernate has
    // already snapshotted the row, so a plain plan.setDisplayPrice(...) is silently dropped and
    // the column persists unchanged, with no exception and no log line.
    event.setCurrentState(displayPriceProperty, derived.getDisplayPrice());
    event.setCurrentState(currencyProperty, derived.getCurrencyCode());
    event.setCurrentState(intervalProperty, derived.getBillingInterval());
    event.setCurrentState(syncedAtProperty, new Date());
  }

  /**
   * Reads the price from the provider and validates it, or refuses the save.
   *
   * <p>Package-visible so every rejection branch is directly assertable against a fake client:
   * these branches are the whole value of the feature and each one is a different thing going
   * wrong, so they must not collapse into one another. In particular a provider that cannot be
   * reached and a price id that does not exist produce different messages on purpose — a network
   * blip must never read as "you typed the wrong id".
   *
   * @param planKey plan search key, for the log line
   * @param priceId provider price id being verified, never blank
   * @param declaredInterval the billing interval typed on the plan, may be blank
   * @param planActive whether the plan row itself is being saved active
   * @return the values to write onto the row
   */
  DerivedPrice resolvePrice(String planKey, String priceId, String declaredInterval,
      boolean planActive) {
    StripeResponse response;
    try {
      response = stripeApiClient.get("/v1/prices/" + urlEncode(priceId));
    } catch (StripeTransportException e) {
      log.error("Plan '{}': payment provider unreachable while reading price '{}'", planKey,
          priceId, e);
      throw new OBException(message(MSG_UNREACHABLE, priceId), e);
    }

    if (!response.isSuccess()) {
      throw rejectedByProvider(planKey, priceId, response);
    }

    JSONObject price = response.json();
    JSONObject recurring = price.optJSONObject(TYPE_RECURRING);
    String priceType = readableString(price, "type");
    if (recurring == null || !TYPE_RECURRING.equals(priceType)) {
      log.warn("Plan '{}': price '{}' is not recurring (type '{}')", planKey, priceId, priceType);
      throw new OBException(message(MSG_NOT_RECURRING, priceId));
    }

    // A plan that is itself inactive may point at an archived price: that is exactly the state of
    // a plan that was retired together with its price, and refusing it would make such a row
    // impossible to save at all.
    if (!price.optBoolean("active", true) && planActive) {
      log.warn("Plan '{}': price '{}' is archived in the payment provider", planKey, priceId);
      throw new OBException(message(MSG_ARCHIVED, priceId));
    }

    int intervalCount = recurring.optInt("interval_count", 1);
    String providerInterval = StringUtils.trimToEmpty(readableString(recurring, "interval"));
    if (intervalCount != 1) {
      log.warn("Plan '{}': price '{}' bills every {} {}", planKey, priceId, intervalCount,
          providerInterval);
      throw new OBException(message(MSG_INTERVAL_COUNT, priceId)
          .replace("@count@", String.valueOf(intervalCount))
          .replace("@interval@", providerInterval));
    }

    String declared = StringUtils.trimToEmpty(declaredInterval);
    if (!declared.isEmpty() && !declared.equalsIgnoreCase(providerInterval)) {
      log.warn("Plan '{}': price '{}' bills '{}' but the plan declares '{}'", planKey, priceId,
          providerInterval, declared);
      throw new OBException(message(MSG_INTERVAL_MISMATCH, priceId)
          .replace("@providerInterval@", providerInterval)
          .replace("@planInterval@", declared));
    }

    String currency = StringUtils.trimToEmpty(readableString(price, "currency"))
        .toUpperCase(Locale.ROOT);
    BigDecimal amount = readAmount(price, currency);
    if (currency.isEmpty() || amount == null) {
      // A tiered or otherwise amount-less price: the provider answered, so this is its verdict on
      // the request, not an outage.
      log.warn("Plan '{}': price '{}' carries no usable unit amount or currency", planKey,
          priceId);
      throw new OBException(message(MSG_REJECTED, priceId));
    }

    String interval = declared.isEmpty() ? providerInterval : declared;
    return new DerivedPrice(amount, currency, interval);
  }

  private OBException rejectedByProvider(String planKey, String priceId, StripeResponse response) {
    int status = response.status();
    String errorCode = response.errorCode();
    log.warn("Plan '{}': payment provider refused price '{}' with status {} and code '{}': {}",
        planKey, priceId, status, errorCode, response.errorMessage());
    if (status == 404 && RESOURCE_MISSING.equals(errorCode)) {
      return new OBException(message(MSG_NOT_FOUND, priceId));
    }
    if (status == 401 || status == 403) {
      return new OBException(message(MSG_UNAUTHORIZED, priceId));
    }
    return new OBException(message(MSG_REJECTED, priceId));
  }

  /**
   * Reads the unit amount as a real decimal.
   *
   * <p>{@code unit_amount_decimal} is preferred because it is exact: the provider supports prices
   * with fractional minor units (a per-seat price of 4999.5 cents), and {@code unit_amount} is
   * that value rounded to an integer. The shift is a {@link BigDecimal#movePointLeft} by the
   * currency's own exponent — never a division by 100, which is wrong for JPY (0 decimals) and
   * for KWD (3), and never on a {@code double}, which is wrong for money in general.
   */
  private static BigDecimal readAmount(JSONObject price, String currency) {
    int exponent = StripeCurrencyScale.exponent(currency);
    String decimal = readableString(price, "unit_amount_decimal");
    if (decimal != null) {
      try {
        return new BigDecimal(decimal).movePointLeft(exponent);
      } catch (NumberFormatException e) {
        // Returned rather than rethrown, so an unparseable amount lands on the provider-rejected
        // message instead of escaping as a raw runtime failure with no explanation for the user.
        log.warn("Unparseable unit_amount_decimal '{}'", decimal, e);
        return null;
      }
    }
    if (!price.has("unit_amount") || price.isNull("unit_amount")) {
      return null;
    }
    return BigDecimal.valueOf(price.optLong("unit_amount", 0L)).movePointLeft(exponent);
  }

  /** Reads a string field, treating an absent field and a JSON null alike. */
  private static String readableString(JSONObject json, String field) {
    if (!json.has(field) || json.isNull(field)) {
      return null;
    }
    return StringUtils.trimToNull(json.optString(field, ""));
  }

  private static String message(String messageValue, String priceId) {
    return OBMessageUtils.messageBD(messageValue).replace("@price@", priceId);
  }

  private static String asString(Object value) {
    return value == null ? null : value.toString();
  }

  private static String urlEncode(String value) {
    try {
      return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    } catch (UnsupportedEncodingException e) {
      // Never in practice; UTF-8 is always available.
      throw new OBException(e);
    }
  }

  /** The three values the provider decides on a plan's behalf. */
  static final class DerivedPrice {
    private final BigDecimal displayPrice;
    private final String currencyCode;
    private final String billingInterval;

    DerivedPrice(BigDecimal displayPrice, String currencyCode, String billingInterval) {
      this.displayPrice = displayPrice;
      this.currencyCode = currencyCode;
      this.billingInterval = billingInterval;
    }

    BigDecimal getDisplayPrice() {
      return displayPrice;
    }

    String getCurrencyCode() {
      return currencyCode;
    }

    String getBillingInterval() {
      return billingInterval;
    }
  }
}
