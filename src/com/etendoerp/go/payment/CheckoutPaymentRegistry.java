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
 */
public final class CheckoutPaymentRegistry {
  private static final Map<String, Payment> PAYMENTS = new ConcurrentHashMap<>();
  private static final Map<String, Boolean> EVENTS = new ConcurrentHashMap<>();

  private CheckoutPaymentRegistry() {}

  public static boolean claimEvent(String eventId) {
    String id = StringUtils.trimToNull(eventId);
    return id != null && EVENTS.putIfAbsent(id, Boolean.TRUE) == null;
  }

  public static void recordPaid(String requestId, String accountEmail, String clientName) {
    String id = StringUtils.trimToNull(requestId);
    if (id == null) return;
    PAYMENTS.put(id, new Payment(StringUtils.trimToEmpty(accountEmail),
        StringUtils.trimToEmpty(clientName)));
  }

  public static Payment find(String requestId, String accountEmail) {
    Payment payment = PAYMENTS.get(StringUtils.trimToEmpty(requestId));
    if (payment == null || !StringUtils.equalsIgnoreCase(payment.accountEmail,
        StringUtils.trimToEmpty(accountEmail))) return null;
    return payment;
  }

  public static boolean isPaidFor(String requestId, String accountEmail, String clientName) {
    Payment payment = find(requestId, accountEmail);
    return payment != null && (StringUtils.isBlank(clientName)
        || StringUtils.equalsIgnoreCase(payment.clientName, StringUtils.trimToEmpty(clientName)));
  }

  public static final class Payment {
    public final String accountEmail;
    public final String clientName;

    private Payment(String accountEmail, String clientName) {
      this.accountEmail = accountEmail;
      this.clientName = clientName;
    }
  }
}
