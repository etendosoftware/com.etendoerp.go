/* Etendo License. */
package com.etendoerp.go.payment;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.data.CheckoutRequest;
import com.etendoerp.go.schemaforge.data.Plan;

/** Small provider adapter for Stripe Checkout Sessions. Pricing is always selected server-side. */
public class HostedCheckoutService {

  private static final Logger log = LogManager.getLogger(HostedCheckoutService.class);

  CheckoutRequestStore checkoutRequestStore = new CheckoutRequestStore();
  PlanCatalogService planCatalogService = new PlanCatalogService();
  /**
   * The provider gateway, package-visible so a test can swap in a recording double. Same shape as
   * {@code PlanPriceDerivationHandler}'s: the base URL, the credentials and the timeouts belong to
   * the implementation, not to this class.
   */
  StripeApiClient stripeApiClient = new HttpUrlConnectionStripeApiClient();

  /**
   * Creates a provider-hosted Checkout Session bound to the authenticated account.
   *
   * <p>The price is never an argument. The caller names a <em>plan key</em>, this method resolves
   * it against the catalog, and the provider price id comes off the resolved row. There is
   * deliberately no configured fallback price: a fallback is a price nobody reviewed, selected
   * exactly when the intended configuration is missing.
   *
   * @param accountId authenticated account id, correlated on the durable request row
   * @param accountEmail authenticated account email
   * @param clientName requested client name
   * @param origin public application origin for return URLs
   * @param planKey catalog key of the plan being bought; required, with no default
   * @return checkout request id, URL, and mode
   * @throws PlanNotAvailableException when the key names no active catalog row
   * @throws IllegalStateException when checkout has no credentials, or the plan carries no
   *     provider price id and therefore cannot be charged for
   * @throws IOException when the provider cannot be reached or rejects the request
   * @throws JSONException when the provider response is not valid JSON
   */
  public JSONObject createSession(String accountId, String accountEmail, String clientName,
      String origin, String planKey) throws IOException, JSONException {
    if (!CheckoutConfiguration.isConfigured()) {
      throw new IllegalStateException("Checkout is not configured");
    }
    Plan plan = planCatalogService.findPurchasablePlan(planKey)
        .orElseThrow(() -> new PlanNotAvailableException(planKey));
    if (!planCatalogService.hasProviderPrice(plan)) {
      // The catalog is fine; the deployment is not. A real price id is environment-specific (test
      // vs live provider account) and cannot ship as sourcedata, so this is the state of a plan
      // nobody has attached one to yet — and of the grandfathered legacy plan, which is not sold.
      throw new IllegalStateException(
          "Plan '" + plan.getSearchKey() + "' has no provider price id and cannot be charged for");
    }
    String requestId = UUID.randomUUID().toString();
    // Recorded and committed BEFORE the provider is contacted. A crash during the call below would
    // otherwise leave a session at Stripe that nothing on this side can name, and therefore that no
    // reconciliation could ever find. The row is deliberately not rolled back when the call fails:
    // it is the evidence that someone tried to buy something, and it is always safe to expire
    // because the checkoutUrl only reaches the browser once this method returns.
    checkoutRequestStore.recordRequested(requestId, accountId, accountEmail, clientName, plan);
    return createProviderSession(requestId, accountEmail, clientName, origin, plan);
  }

  /**
   * Reopens the provider checkout for an existing unpaid request.
   *
   * <p>The durable request is the idempotency boundary for the purchase. A browser refresh or a
   * provider redirect can leave that request in CREATED while the checkout URL is no longer in
   * the browser. Reusing the request id lets the buyer continue without creating a second purchase
   * row or a second webhook correlation key.
   *
   * <p>The plan is read back off the request rather than resolved again, so a reopened checkout
   * charges what the buyer was originally shown even if the catalog has been re-priced since. A
   * request carrying no plan cannot be reopened: it predates the plan catalog, and choosing a plan
   * on the buyer's behalf here would charge for something nobody selected.
   *
   * @param requestId existing checkout request id
   * @param accountEmail authenticated account email
   * @param clientName requested environment name
   * @param origin public application origin for return URLs
   * @return checkout request id, URL, and mode
   * @throws IllegalStateException when checkout has no credentials, when the request names no
   *     plan, or when that plan carries no provider price id
   * @throws IOException when the provider cannot be reached or rejects the request
   * @throws JSONException when the provider response is not valid JSON
   */
  public JSONObject reopenSession(String requestId, String accountEmail, String clientName,
      String origin) throws IOException, JSONException {
    if (!CheckoutConfiguration.isConfigured()) {
      throw new IllegalStateException("Checkout is not configured");
    }
    CheckoutRequest request = checkoutRequestStore.find(requestId, accountEmail);
    Plan plan = request == null ? null : request.getPlan();
    if (plan == null) {
      throw new IllegalStateException(
          "Checkout request '" + requestId + "' names no plan and cannot be reopened");
    }
    if (!planCatalogService.hasProviderPrice(plan)) {
      throw new IllegalStateException(
          "Plan '" + plan.getSearchKey() + "' has no provider price id and cannot be charged for");
    }
    return createProviderSession(requestId, accountEmail, clientName, origin, plan);
  }

  private JSONObject createProviderSession(String requestId, String accountEmail, String clientName,
      String origin, Plan plan) throws IOException, JSONException {
    String form = buildSessionForm(requestId, accountEmail, clientName, origin,
        plan.getProviderPriceID(), plan.getSearchKey());
    StripeResponse response = stripeApiClient.postForm("/v1/checkout/sessions", form);
    if (!response.isSuccess()) {
      log.error("Checkout provider refused a session for plan '{}' with status {} and code '{}'",
          plan.getSearchKey(), response.status(), response.errorCode());
      throw new IOException("Checkout provider rejected session");
    }
    JSONObject provider = response.json();
    // The provider session id is the reconciliation anchor for an abandoned or lost checkout, and
    // this response is the only place it appears. Recorded before the URL is handed back.
    checkoutRequestStore.recordSessionCreated(requestId, provider.optString("id", ""));
    JSONObject result = new JSONObject();
    result.put("requestId", requestId);
    result.put("checkoutUrl", provider.optString("url", ""));
    result.put("mode", CheckoutConfiguration.mode());
    return result;
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
   * @param planKey catalog key of the resolved plan, echoed as metadata for ETP-5047
   * @return the form-encoded request body
   * @throws UnsupportedEncodingException never in practice; UTF-8 is always available
   */
  static String buildSessionForm(String requestId, String accountEmail, String clientName,
      String origin, String priceId, String planKey) throws UnsupportedEncodingException {
    String success = origin + "/upgrade?checkout=success&requestId=" + requestId;
    String cancel = origin + "/upgrade?checkout=cancelled&requestId=" + requestId;
    StringBuilder form = new StringBuilder();
    add(form, "mode", CheckoutConfiguration.mode());
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
    add(form, "metadata[plan_key]", planKey);
    // The sandbox product is not configured for Stripe Managed Payments. Keep the
    // Checkout contract explicit until Product selects an eligible tax code.
    add(form, "managed_payments[enabled]", "false");
    if ("subscription".equals(CheckoutConfiguration.mode())) {
      add(form, "subscription_data[metadata][request_id]", requestId);
      add(form, "subscription_data[metadata][plan_key]", planKey);
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
