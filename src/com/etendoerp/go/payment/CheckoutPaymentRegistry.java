/* Etendo License. */
package com.etendoerp.go.payment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.StringUtils;

/**
 * Process-local correlation store for Checkout sessions received by the webhook.
 *
 * <p>The production persistence adapter can replace this store without changing the servlet
 * contract. Keeping the correlation keyed by the server-generated request id prevents a browser
 * from turning a successful return URL into payment authorization.</p>
 *
 * Stores server-side correlations between checkout requests and paid events.
 */
public final class CheckoutPaymentRegistry {
  private static final Map<String, Payment> PAYMENTS = new ConcurrentHashMap<>();
  private static final Map<String, Boolean> EVENTS = new ConcurrentHashMap<>();

  private CheckoutPaymentRegistry() {}

  /**
   * Claims an event id atomically and returns false when it was already claimed.
   * @param eventId provider event identifier
   * @return true when this call claimed the event
   */
  public static boolean claimEvent(String eventId) {
    String id = StringUtils.trimToNull(eventId);
    return id != null && EVENTS.putIfAbsent(id, Boolean.TRUE) == null;
  }

  /**
   * Records a successful payment for a server-generated request id.
   * @param requestId server-generated checkout request identifier
   * @param accountEmail authenticated account email
   * @param clientName requested client name
   */
  public static void recordPaid(String requestId, String accountEmail, String clientName) {
    String id = StringUtils.trimToNull(requestId);
    if (id == null) return;
    PAYMENTS.put(id, new Payment(StringUtils.trimToEmpty(accountEmail),
        StringUtils.trimToEmpty(clientName)));
  }

  /**
   * Finds a payment only when its account email matches the authenticated account.
   * @param requestId server-generated checkout request identifier
   * @param accountEmail authenticated account email
   * @return matching payment, or null
   */
  public static Payment find(String requestId, String accountEmail) {
    Payment payment = PAYMENTS.get(StringUtils.trimToEmpty(requestId));
    if (payment == null || !StringUtils.equalsIgnoreCase(payment.accountEmail,
        StringUtils.trimToEmpty(accountEmail))) return null;
    return payment;
  }

  /**
   * Returns whether a payment matches the request, account, and optional client name.
   * @param requestId server-generated checkout request identifier
   * @param accountEmail authenticated account email
   * @param clientName requested client name, when available
   * @return true when a matching paid event exists
   */
  public static boolean isPaidFor(String requestId, String accountEmail, String clientName) {
    Payment payment = find(requestId, accountEmail);
    return payment != null && (StringUtils.isBlank(clientName)
        || StringUtils.equalsIgnoreCase(payment.clientName, StringUtils.trimToEmpty(clientName)));
  }

  /** Immutable payment correlation data exposed to the authenticated checkout flow. */
  public static final class Payment {
    /** Account email bound to the checkout request. */
    public final String accountEmail;
    /** Client name bound to the checkout request. */
    public final String clientName;

    private Payment(String accountEmail, String clientName) {
      this.accountEmail = accountEmail;
      this.clientName = clientName;
    }
  }
}
