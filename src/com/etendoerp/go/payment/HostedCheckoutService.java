/* Etendo License. */
package com.etendoerp.go.payment;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.schemaforge.data.Plan;

/**
 * Small provider adapter for Stripe Checkout Sessions. Pricing is always selected server-side.
 *
 * <p>The browser names a Subscription Plan Catalog key, never a price. The plan's provider price
 * id is validated against Stripe through {@link StripePriceService} (active, amount, currency,
 * interval), and the checkout mode derives from that price's interval. The chosen price id is
 * stored on the checkout request together with the plan, so a reopened checkout charges the price
 * the buyer was originally shown even if the plan catalog has been re-priced since.
 *
 * <p><b>Legacy price fallback.</b> While no active plan carries a provider price and
 * {@code etendo.go.checkout.price.id} is configured
 * ({@link PlanCatalogService#isLegacyFallbackActive()}), a request that names the grandfathered
 * {@link PlanCatalogService#LEGACY_PLAN_KEY} plan — or names no plan at all — is sold at that
 * configured price and recorded under the grandfathered plan. Once the first priced plan exists the
 * same request is refused as {@code PLAN_NOT_AVAILABLE}, like any unknown key.
 */
public class HostedCheckoutService {

  private static final Logger log = LogManager.getLogger(HostedCheckoutService.class);

  private static final String REQUEST_ID_FIELD = "requestId";
  private static final String SUBSCRIPTION_FIELD = "subscription";
  private static final String SESSIONS_PATH = "/v1/checkout/sessions";

  CheckoutRequestStore checkoutRequestStore = new CheckoutRequestStore();
  PlanCatalogService planCatalogService = new PlanCatalogService();
  /** Validates the provider price before it is charged; package-visible for test doubles. */
  StripePriceService stripePriceService = new StripePriceService();
  /**
   * The provider gateway, package-visible so a test can swap in a recording double. Same shape as
   * {@code PlanPriceDerivationHandler}'s: the base URL, the credentials and the timeouts belong to
   * the implementation, not to this class.
   */
  StripeApiClient stripeApiClient = new HttpUrlConnectionStripeApiClient();

  /** A persisted purchase cannot be resumed when its original Stripe Price ID is missing. */
  public static final class OriginalPriceUnavailableException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private OriginalPriceUnavailableException() {
      super("The original checkout price is unavailable");
    }
  }

  /**
   * Checkout cannot sell anything in this deployment: the provider credentials are missing, or the
   * requested plan exists but carries no provider price id. Answered as
   * {@code 503 CHECKOUT_NOT_CONFIGURED}; distinct from other {@link IllegalStateException}s the
   * checkout path can raise (a locked transfer selection, a changed session), which are not a
   * deployment state.
   */
  public static final class CheckoutNotConfiguredException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public CheckoutNotConfiguredException(String message) {
      super(message);
    }
  }

  /** Demo source, transfer selection and pre-provider callback of a new checkout. */
  public static final class SessionOptions {
    private final String demoClientId;
    private final boolean transferProducts;
    private final boolean transferContacts;
    private final Consumer<String> beforeProvider;

    /**
     * Creates the options of one checkout.
     *
     * @param demoClientId immutable selected demo client id, or {@code null} when none is selected
     * @param transferProducts whether products should be copied from the demo
     * @param transferContacts whether contacts should be copied from the demo
     * @param beforeProvider callback invoked with the request id once the row is committed and
     *     before the provider is contacted; {@code null} for none
     */
    public SessionOptions(String demoClientId, boolean transferProducts, boolean transferContacts,
        Consumer<String> beforeProvider) {
      this.demoClientId = demoClientId;
      this.transferProducts = transferProducts;
      this.transferContacts = transferContacts;
      this.beforeProvider = beforeProvider == null ? requestId -> { } : beforeProvider;
    }

    /** @return options for a checkout with no demo source and no callback */
    public static SessionOptions none() {
      return new SessionOptions(null, false, false, null);
    }

    /** @return selected demo client id, or {@code null} when none is selected */
    public String getDemoClientId() {
      return demoClientId;
    }

    /** @return whether products should be copied from the demo */
    public boolean isTransferProducts() {
      return transferProducts;
    }

    /** @return whether contacts should be copied from the demo */
    public boolean isTransferContacts() {
      return transferContacts;
    }

    /** @return the callback run with the request id before the provider is contacted */
    public Consumer<String> getBeforeProvider() {
      return beforeProvider;
    }
  }

  /** A plan together with the validated provider price a checkout charges for it. */
  private static final class PricedPlan {
    private final Plan plan;
    private final StripePriceService.Price price;

    PricedPlan(Plan plan, StripePriceService.Price price) {
      this.plan = plan;
      this.price = price;
    }
  }

  /**
   * Creates a provider-hosted Checkout Session with no demo source.
   *
   * @param accountId authenticated account id, correlated on the durable request row
   * @param accountEmail authenticated account email
   * @param clientName requested environment name
   * @param origin public application origin for return URLs
   * @param planKey plan catalog key of the plan being bought; blank asks for the legacy fallback
   * @return checkout request id, URL, mode and price id
   * @throws PlanNotAvailableException when the key names no active plan catalog row, or asks for
   *     the legacy plan while the legacy price fallback is inactive
   * @throws CheckoutNotConfiguredException when checkout has no credentials, or the plan carries
   *     no provider price id and therefore cannot be charged for
   * @throws IOException when the provider cannot be reached or rejects the request or the price
   * @throws JSONException when the provider response is not valid JSON
   */
  public JSONObject createSession(String accountId, String accountEmail, String clientName,
      String origin, String planKey) throws IOException, JSONException {
    return createSession(accountId, accountEmail, clientName, origin, planKey,
        SessionOptions.none());
  }

  /**
   * Creates a provider-hosted Checkout Session bound to the authenticated account, an immutable
   * demo source and transfer selection, and the price of the named plan.
   *
   * <p>The price is never an argument. The caller names a <em>plan key</em>, this method resolves
   * it against the Subscription Plan Catalog, and the provider price id comes off that row — or,
   * under the legacy price fallback, off the configured legacy price — and is validated against
   * the provider before anything is recorded.
   *
   * @param accountId authenticated account id, correlated on the durable request row
   * @param accountEmail authenticated account email
   * @param clientName requested environment name
   * @param origin public application origin for return URLs
   * @param planKey plan catalog key of the plan being bought; blank asks for the legacy fallback
   * @param options demo source, transfer selection and pre-provider callback
   * @return checkout request id, URL, mode and price id
   * @throws PlanNotAvailableException when the key names no active plan catalog row, or asks for
   *     the legacy plan while the legacy price fallback is inactive
   * @throws CheckoutNotConfiguredException when checkout has no credentials, or the plan carries
   *     no provider price id and therefore cannot be charged for
   * @throws IOException when the provider cannot be reached or rejects the request or the price
   * @throws JSONException when the provider response is not valid JSON
   */
  public JSONObject createSession(String accountId, String accountEmail, String clientName,
      String origin, String planKey, SessionOptions options) throws IOException, JSONException {
    requireConfigured();
    SessionOptions sessionOptions = options == null ? SessionOptions.none() : options;
    PricedPlan offer = resolvePricedPlan(planKey);
    Plan plan = offer.plan;
    StripePriceService.Price price = offer.price;
    String requestId = UUID.randomUUID().toString();
    // Recorded and committed BEFORE the provider is contacted. A crash during the call below would
    // otherwise leave a session at Stripe that nothing on this side can name, and therefore that no
    // reconciliation could ever find. The row is deliberately not rolled back when the call fails:
    // it is the evidence that someone tried to buy something, and it is always safe to expire
    // because the checkoutUrl only reaches the browser once this method returns.
    checkoutRequestStore.recordRequested(requestId, accountId, accountEmail, clientName,
        new CheckoutRequestStore.RequestOptions(sessionOptions.demoClientId, true,
            sessionOptions.transferProducts, sessionOptions.transferContacts, price.getId()),
        plan);
    sessionOptions.beforeProvider.accept(requestId);
    return createProviderSession(requestId, accountEmail, clientName, origin, price,
        plan.getSearchKey(), initialIdempotencyKey(requestId), null);
  }

  /**
   * Resolves the plan and the validated price a checkout charges, or refuses with the answer the
   * buyer gets.
   *
   * <p>A key naming the grandfathered plan (or no key at all) is served by the legacy price
   * fallback while it is active. Otherwise it is refused as {@code PLAN_NOT_AVAILABLE} — the same
   * answer as an unknown key — unless an operator has attached a price to the legacy plan itself,
   * in which case it is sold like any other priced plan.
   *
   * @param planKey plan catalog key the browser named, may be blank
   * @return the plan to record and the price to charge
   * @throws PlanNotAvailableException when nothing may be sold under this key
   * @throws CheckoutNotConfiguredException when a non-legacy plan carries no provider price id
   * @throws IOException when the provider rejects the price or cannot be reached
   * @throws JSONException when the provider response is not valid JSON
   */
  private PricedPlan resolvePricedPlan(String planKey) throws IOException, JSONException {
    if (PlanCatalogService.namesLegacyPlan(planKey)) {
      Optional<Plan> fallback = planCatalogService.findLegacyFallbackPlan();
      if (fallback.isPresent()) {
        return new PricedPlan(fallback.get(), stripePriceService.retrieveConfiguredPrice());
      }
      Plan legacy = StringUtils.isBlank(planKey) ? null
          : planCatalogService.findPurchasablePlan(planKey).orElse(null);
      if (!planCatalogService.hasProviderPrice(legacy)) {
        // Fallback inactive and the legacy plan has no price of its own: nothing is for sale under
        // this key. Same answer as an unknown key, so the plan list the browser holds is simply
        // stale — the page tells the buyer to reload it.
        throw new PlanNotAvailableException(planKey);
      }
      return new PricedPlan(legacy, stripePriceService.retrievePrice(legacy.getProviderPriceID()));
    }
    Plan plan = resolvePurchasablePlan(planKey);
    return new PricedPlan(plan, stripePriceService.retrievePrice(plan.getProviderPriceID()));
  }

  /**
   * Resolves the plan a checkout may be started from, or refuses with the answer the buyer gets.
   *
   * @param planKey plan catalog key the browser named
   * @return the active plan carrying a provider price id
   * @throws PlanNotAvailableException when the key names no active plan catalog row
   * @throws CheckoutNotConfiguredException when the plan carries no provider price id
   */
  private Plan resolvePurchasablePlan(String planKey) {
    Plan plan = planCatalogService.findPurchasablePlan(planKey)
        .orElseThrow(() -> new PlanNotAvailableException(planKey));
    if (!planCatalogService.hasProviderPrice(plan)) {
      // The plan catalog is fine; the deployment is not. A real price id is environment-specific
      // (test vs live provider account) and cannot ship as sourcedata, so this is the state of a
      // plan nobody has attached one to yet.
      throw new CheckoutNotConfiguredException(
          "Plan '" + plan.getSearchKey() + "' has no provider price id and cannot be charged for");
    }
    return plan;
  }

  private static void requireConfigured() {
    if (!CheckoutConfiguration.isConfigured()) {
      throw new CheckoutNotConfiguredException("Checkout is not configured");
    }
  }

  /**
   * Reopens the provider checkout for an existing unpaid request.
   *
   * <p>The durable request is the idempotency boundary for the purchase. A browser refresh or a
   * provider redirect can leave that request in CREATED while the checkout URL is no longer in
   * the browser. Reusing the request id lets the buyer continue without creating a second purchase
   * row or a second webhook correlation key.
   *
   * <p>An open provider session is reused as is, a completed one is reported rather than recreated,
   * and only an expired or missing one is replaced — charging the Stripe Price id <em>stored on the
   * request</em>, never the plan's current one, so a reopened checkout charges what the buyer was
   * originally shown even if the plan catalog has been re-priced since.
   *
   * @param requestId existing checkout request id
   * @param accountEmail authenticated account email
   * @param clientName requested environment name
   * @param origin public application origin for return URLs
   * @return checkout request id, URL, and mode; or the provider payment state when the existing
   *     session is already complete
   * @throws OriginalPriceUnavailableException when the request carries no stored price id
   * @throws CheckoutNotConfiguredException when checkout has no credentials
   * @throws IOException when the provider cannot be reached or rejects the request
   * @throws JSONException when the provider response is not valid JSON
   */
  public JSONObject reopenSession(String requestId, String accountEmail, String clientName,
      String origin) throws IOException, JSONException {
    requireConfigured();
    String sessionId = checkoutRequestStore.findStripeSessionId(requestId);
    if (sessionId != null) {
      JSONObject existing = retrieveSession(sessionId);
      if (!sessionId.equals(existing.optString("id", ""))) {
        throw new IOException("Stripe returned a different existing checkout session");
      }
      String sessionStatus = existing.optString("status", "");
      if ("open".equals(sessionStatus)) {
        String originalMode = existing.optString("mode", "");
        if (originalMode.isEmpty()) {
          throw new IOException("Stripe returned an existing session without its checkout mode");
        }
        return buildResult(requestId, existing.optString("url", ""),
            checkoutRequestStore.findStripePriceId(requestId), originalMode);
      }
      if ("complete".equals(sessionStatus)) {
        return buildCompletedResult(requestId, existing);
      }
      if (!"expired".equals(sessionStatus)) {
        throw new IllegalStateException("The existing checkout session is not reopenable");
      }
    }

    String priceId = checkoutRequestStore.findStripePriceId(requestId);
    if (priceId == null) {
      throw new OriginalPriceUnavailableException();
    }
    StripePriceService.Price price = stripePriceService.retrievePrice(priceId);
    return createProviderSession(requestId, accountEmail, clientName, origin, price,
        findPlanKey(requestId, accountEmail),
        sessionId == null ? initialIdempotencyKey(requestId)
            : replacementIdempotencyKey(requestId, sessionId), sessionId);
  }

  /** The plan key recorded on the request, echoed as metadata; blank for a pre-plan request. */
  private String findPlanKey(String requestId, String accountEmail) {
    CheckoutRequest request = checkoutRequestStore.find(requestId, accountEmail);
    Plan plan = request == null ? null : request.getPlan();
    return plan == null ? null : plan.getSearchKey();
  }

  private JSONObject buildCompletedResult(String requestId, JSONObject session)
      throws JSONException {
    String paymentStatus = session.optString("payment_status", "");
    JSONObject result = new JSONObject();
    result.put(REQUEST_ID_FIELD, requestId);
    result.put("providerStatus", "complete");
    result.put("paymentStatus", paymentStatus);
    result.put("paymentComplete", "paid".equals(paymentStatus)
        || "no_payment_required".equals(paymentStatus));
    result.put("stripeCustomer", session.optString("customer", ""));
    result.put("stripeSubscription", session.optString(SUBSCRIPTION_FIELD, ""));
    return result;
  }

  private JSONObject createProviderSession(String requestId, String accountEmail, String clientName,
      String origin, StripePriceService.Price price, String planKey, String idempotencyKey,
      String expectedSessionId) throws IOException, JSONException {
    String mode = modeOf(price);
    String form = buildSessionForm(requestId, accountEmail, clientName, origin, price.getId(),
        planKey, mode);
    StripeResponse response = stripeApiClient.postForm(SESSIONS_PATH, form, idempotencyKey);
    if (!response.isSuccess()) {
      log.error("Checkout provider refused a session for plan '{}' with status {} and code '{}'",
          planKey, response.status(), response.errorCode());
      throw new IOException("Checkout provider rejected session");
    }
    JSONObject provider = response.json();
    String providerSessionId = provider.optString("id", "");
    String providerUrl = provider.optString("url", "");
    if (providerSessionId.isEmpty() || providerUrl.isEmpty()) {
      throw new IOException("Checkout provider returned an incomplete session");
    }
    // The provider session id is the reconciliation anchor for an abandoned or lost checkout, and
    // this response is the only place it appears. Recorded before the URL is handed back.
    checkoutRequestStore.recordSessionCreated(requestId, expectedSessionId, providerSessionId);
    JSONObject result = new JSONObject();
    result.put(REQUEST_ID_FIELD, requestId);
    result.put("checkoutUrl", providerUrl);
    result.put("mode", mode);
    result.put("priceId", price.getId());
    return result;
  }

  /** A one-time price is a payment checkout; every recurring price is a subscription. */
  static String modeOf(StripePriceService.Price price) {
    return "once".equals(price.getInterval()) ? "payment" : SUBSCRIPTION_FIELD;
  }

  private JSONObject retrieveSession(String sessionId) throws IOException {
    StripeResponse response = stripeApiClient.get(SESSIONS_PATH + "/"
        + URLEncoder.encode(sessionId, StandardCharsets.UTF_8.name()));
    if (!response.isSuccess()) {
      throw new IOException("Could not retrieve existing checkout session");
    }
    return response.json();
  }

  private JSONObject buildResult(String requestId, String checkoutUrl, String priceId, String mode)
      throws JSONException {
    if (checkoutUrl == null || checkoutUrl.isEmpty()) {
      throw new IllegalStateException("The checkout provider returned no session URL");
    }
    JSONObject result = new JSONObject();
    result.put(REQUEST_ID_FIELD, requestId);
    result.put("checkoutUrl", checkoutUrl);
    result.put("mode", mode);
    if (priceId != null) result.put("priceId", priceId);
    return result;
  }

  static String initialIdempotencyKey(String requestId) {
    return "checkout:" + requestId + ":initial";
  }

  static String replacementIdempotencyKey(String requestId, String expiredSessionId) {
    return "checkout:" + requestId + ":after:" + expiredSessionId;
  }

  /**
   * Builds the form-encoded Checkout Session body.
   *
   * <p>Package-visible so the contract sent to Stripe is directly assertable: this body is the whole
   * agreement with the provider, and every field in it is a product decision (what is charged, what
   * the webhook can correlate, what the buyer may do at the till). A field silently dropped here is
   * invisible until someone reaches the hosted page.
   *
   * @param requestId server-generated checkout request id, correlated by the webhook
   * @param accountEmail authenticated account email
   * @param clientName requested environment name
   * @param origin public application origin for return URLs
   * @param priceId provider price id resolved from the plan catalog, never from the browser
   * @param planKey plan catalog key of the resolved plan, echoed as metadata for ETP-5047; may be
   *     blank for a request that predates the plan catalog
   * @param mode {@code subscription} or {@code payment}, derived from the price interval
   * @return the form-encoded request body
   * @throws UnsupportedEncodingException never in practice; UTF-8 is always available
   */
  @SuppressWarnings("java:S107")
  static String buildSessionForm(String requestId, String accountEmail, String clientName,
      String origin, String priceId, String planKey, String mode)
      throws UnsupportedEncodingException {
    String success = origin + "/upgrade?checkout=success&requestId=" + requestId;
    String cancel = origin + "/upgrade?checkout=cancelled&requestId=" + requestId;
    StringBuilder form = new StringBuilder();
    add(form, "mode", mode);
    add(form, "line_items[0][price]", priceId);
    add(form, "line_items[0][quantity]", "1");
    add(form, "success_url", success);
    add(form, "cancel_url", cancel);
    add(form, "client_reference_id", requestId);
    // Show the "Add promotion code" field. Off by default in Stripe, which makes any coupon the
    // business creates unreachable from the hosted page. Mutually exclusive with a `discounts`
    // parameter — do not add one here without removing this.
    add(form, "allow_promotion_codes", "true");
    // Bind Checkout to the authenticated Etendo account; the browser cannot override this email.
    add(form, "customer_email", accountEmail);
    add(form, "metadata[account_email]", accountEmail);
    add(form, "metadata[client_name]", clientName);
    add(form, "metadata[request_id]", requestId);
    // ETP-5047 correlates subscription lifecycle events back to a plan. The event carries the
    // Stripe price id, but a price can be swapped on a plan, so the key is what stays meaningful.
    if (StringUtils.isNotBlank(planKey)) {
      add(form, "metadata[plan_key]", planKey);
    }
    // The sandbox product is not configured for Stripe Managed Payments. Keep the
    // Checkout contract explicit until Product selects an eligible tax code.
    add(form, "managed_payments[enabled]", "false");
    if (SUBSCRIPTION_FIELD.equals(mode)) {
      add(form, "subscription_data[metadata][request_id]", requestId);
      if (StringUtils.isNotBlank(planKey)) {
        add(form, "subscription_data[metadata][plan_key]", planKey);
      }
      // Skip the card when a promotion code brings the total to 0, so a 100%-off code does not make
      // the buyer enter card details for a charge that will never happen. Stripe evaluates this per
      // session against the amount due, so the ordinary paid path still collects a card.
      //
      // Two constraints, both load-bearing:
      //  - Stripe accepts this parameter in `subscription` mode ONLY, which is why it lives inside
      //    this branch. Sent in `payment` mode it fails the whole session, not just the field.
      //  - It is only safe for coupons with `duration=forever`. A `once` or `repeating` coupon
      //    leaves no payment method on file, so the first invoice after the discount ends has
      //    nothing to charge. Coupons meant to make an environment free must be created `forever`.
      add(form, "payment_method_collection", "if_required");
    }
    return form.toString();
  }

  private static void add(StringBuilder form, String key, String value)
      throws UnsupportedEncodingException {
    if (form.length() > 0) {
      form.append('&');
    }
    form.append(URLEncoder.encode(key, StandardCharsets.UTF_8.name()))
        .append('=')
        .append(URLEncoder.encode(StringUtils.defaultString(value), StandardCharsets.UTF_8.name()));
  }
}
