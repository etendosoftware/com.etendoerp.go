/* Etendo License. */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;

public class CheckoutWebhookVerifierTest {
  @Test
  public void acceptsFreshValidSignature() throws Exception {
    String payload = "{\"id\":\"evt_1\"}";
    String secret = "whsec_test";
    long timestamp = 1_700_000_000L;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
    StringBuilder signature = new StringBuilder();
    for (byte b : digest) signature.append(String.format("%02x", b));
    assertTrue(CheckoutWebhookVerifier.verify(payload, "t=" + timestamp + ",v1=" + signature,
        secret, timestamp, 300));
    assertFalse(CheckoutWebhookVerifier.verify(payload, "t=" + timestamp + ",v1=" + signature,
        secret, timestamp + 301, 300));
  }
}
