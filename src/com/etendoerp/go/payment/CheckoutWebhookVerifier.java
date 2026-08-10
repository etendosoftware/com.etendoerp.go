/* Etendo License. */
package com.etendoerp.go.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Verifies the timestamped signature format used by hosted checkout webhooks. */
public final class CheckoutWebhookVerifier {
  private CheckoutWebhookVerifier() {
  }

  /**
   * Verifies a timestamped webhook signature within the configured tolerance.
   * @param payload raw provider webhook payload
   * @param signatureHeader provider signature header
   * @param secret webhook signing secret
   * @param nowSeconds current epoch time in seconds
   * @param toleranceSeconds maximum accepted signature age
   * @return true when the signature is valid and fresh
   */
  public static boolean verify(String payload, String signatureHeader, String secret, long nowSeconds,
      long toleranceSeconds) {
    if (payload == null || signatureHeader == null || secret == null || secret.isEmpty()) return false;
    String timestamp = null;
    String expected = null;
    for (String part : signatureHeader.split(",")) {
      String[] pair = part.trim().split("=", 2);
      if (pair.length == 2 && "t".equals(pair[0])) timestamp = pair[1];
      if (pair.length == 2 && "v1".equals(pair[0])) expected = pair[1];
    }
    if (timestamp == null || expected == null) return false;
    try {
      long eventTime = Long.parseLong(timestamp);
      if (Math.abs(nowSeconds - eventTime) > toleranceSeconds) return false;
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : digest) hex.append(String.format("%02x", b));
      return MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.US_ASCII),
          expected.getBytes(StandardCharsets.US_ASCII));
    } catch (Exception e) {
      return false;
    }
  }
}
