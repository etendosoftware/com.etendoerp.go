/* Etendo License. */
package com.etendoerp.go.payment;

import static org.junit.Assert.assertEquals;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;

public class CheckoutWebhookProcessorTest {
  @Test
  public void duplicateEventIsClaimedOnlyOnce() throws Exception {
    String payload = "{\"id\":\"evt_1\",\"type\":\"checkout.session.completed\"}";
    String secret = "whsec_test";
    long timestamp = 1700000000L;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] bytes = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : bytes) hex.append(String.format("%02x", b));
    Set<String> claimed = new HashSet<>();
    CheckoutWebhookProcessor processor = new CheckoutWebhookProcessor(claimed::add, 300);
    String signature = "t=" + timestamp + ",v1=" + hex;
    assertEquals(CheckoutWebhookProcessor.Result.ACCEPTED, processor.accept(payload, signature, secret, timestamp));
    assertEquals(CheckoutWebhookProcessor.Result.DUPLICATE, processor.accept(payload, signature, secret, timestamp));
  }
}
