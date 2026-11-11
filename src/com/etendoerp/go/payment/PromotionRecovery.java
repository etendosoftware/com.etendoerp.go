/* Etendo License. */
package com.etendoerp.go.payment;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.openbravo.dal.service.OBDal;
import com.etendoerp.go.schemaforge.data.BillingEvent;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;

/** Bounded replay of durable promotion intents after lost webhook delivery or a process crash. */
public class PromotionRecovery {
  StripeApiClient stripe = new HttpUrlConnectionStripeApiClient();
  MonthlySecondMonthPromotionService promotions = new MonthlySecondMonthPromotionService();
  BillingEventStore events = new BillingEventStore();

  /** A dry run performs neither provider mutations nor ledger writes. */
  public void recover(boolean dryRun) {
    List<BillingEvent> pending = SystemContext.call("pending checkout promotions", () ->
        OBDal.getInstance().createQuery(BillingEvent.class,
            "as be where be.provider = :provider and be.eventType = :intent"
                + " and be.eventResult = :applied"
                + " and be.payloadSummary like :version"
                + " and not exists (select 1 from " + BillingEvent.ENTITY_NAME + " backoff"
                + " where backoff.provider = :provider and backoff.eventType = 'promotion.retry'"
                + " and backoff.requestID = be.requestID"
                + " and coalesce(backoff.lastDuplicateAt, backoff.receivedAt) > :retryAfter)"
                + " and exists (select 1 from " + CheckoutRequest.ENTITY_NAME + " cr"
                + " where cr.request = be.requestID"
                + " and cr.checkoutRequestStatus in ('PAID','PROVISIONING','PROVISIONED'))"
                + " and not exists (select 1 from " + BillingEvent.ENTITY_NAME + " done"
                + " where done.provider = :provider and done.eventType = :done"
                + " and done.requestID = be.requestID and done.eventResult = :applied)"
                + " order by be.receivedAt")
            .setNamedParameter("provider", BillingEventStore.PROVIDER_RECONCILER)
            .setNamedParameter("intent", CheckoutPromotionTerms.INTENT_TYPE)
            .setNamedParameter("done", MonthlySecondMonthPromotionService.APPLIED_TYPE)
            .setNamedParameter("applied", BillingEventStore.RESULT_APPLIED)
            .setNamedParameter("version", "%" + CheckoutPromotionTerms.VERSION + "%")
            .setNamedParameter("retryAfter", new java.util.Date(System.currentTimeMillis() - 3600000L))
            .setFilterOnReadableClients(false).setFilterOnReadableOrganization(false)
            .setMaxResult(100).list());
    replay(pending, dryRun);
  }

  void replay(List<BillingEvent> pending, boolean dryRun) {
    if (dryRun) return;
    int failed = 0;
    for (BillingEvent intent : pending) {
      try {
        recoverOne(intent);
      } catch (IOException | org.codehaus.jettison.json.JSONException | RuntimeException e) {
        failed++;
        events.touchReconciliation("promotion-retry:" + intent.getRequestID(), "promotion.retry",
            intent.getRequestID(), "Promotion recovery requires retry or operator review");
      }
    }
    if (failed > 0) throw new IllegalStateException("Checkout promotion recovery failures: " + failed);
  }

  void recoverOne(BillingEvent intent) throws IOException, org.codehaus.jettison.json.JSONException {
    if (!new org.codehaus.jettison.json.JSONObject(intent.getPayloadSummary())
        .optBoolean("secondMonthFree")) return;
    CheckoutRequest purchase = SystemContext.call("promotion checkout request", () ->
        CheckoutRequestStore.findByRequestId(intent.getRequestID()));
    String session = purchase == null ? null : purchase.getStripeSession();
    if (session == null) throw new IllegalStateException("Paid promotion has no checkout session");
    StripeResponse response = stripe.get("/v1/checkout/sessions/"
        + URLEncoder.encode(session, StandardCharsets.UTF_8));
    if (!response.isSuccess()) throw new IllegalStateException("Cannot recover paid checkout promotion");
    promotions.applyPaidCheckout(response.json());
  }
}
