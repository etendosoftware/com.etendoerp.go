package com.etendoerp.go.rest;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * Name a classic-path client carries while it is being built (ETP-5548).
 *
 * <p>The client is created under this name and renamed to the requested company name only in the
 * onboarding's final transaction. The name therefore identifies the provisioning attempt, not the
 * company: a retry of the same attempt finds its half-built client by it, and a company name can be
 * shared with any other environment (the account's demo included) without being mistaken for it.
 * It also takes the requested name out of {@code InitialClientSetup}'s global name check, which
 * would otherwise refuse a name another client already has.
 *
 * <p>Keyed by the checkout request of a paid attempt, and by the account for the free first
 * environment, the only free one an account gets. The form is {@code PEND-<32 hex>}: 37
 * characters, the length of the pool's {@code POOL-<id>} placeholder. That length matters — the
 * chain names objects after the client inside 60-character columns ("Arbol de cuentas " + name is
 * the longest), and the pool has proven 37 fits all of them.
 */
final class ProvisioningClientName {

  /** Prefix of every provisioning name. */
  static final String PREFIX = "PEND-";
  private static final Pattern HEX_32 = Pattern.compile("[0-9A-F]{32}");
  private static final Pattern NAME = Pattern.compile("PEND-[0-9A-F]{32}",
      Pattern.CASE_INSENSITIVE);

  private ProvisioningClientName() {
  }

  /**
   * @param accountId platform account id
   * @param paymentToken checkout request id of a paid attempt, blank for the free one
   * @return the provisioning name
   */
  static String of(String accountId, String paymentToken) {
    String key = StringUtils.isNotBlank(paymentToken) ? paymentToken : accountId;
    return PREFIX + toHex32(StringUtils.trimToEmpty(key));
  }

  /**
   * A UUID request id or an AD id is kept readable (hyphens dropped, upper case), so operations can
   * tell which attempt a half-built client belongs to; any other key is hashed to the same shape.
   */
  private static String toHex32(String key) {
    String compact = StringUtils.remove(key, '-').toUpperCase(Locale.ROOT);
    if (HEX_32.matcher(compact).matches()) {
      return compact;
    }
    return StringUtils.remove(
        UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString(), '-')
        .toUpperCase(Locale.ROOT);
  }

  /**
   * @return whether {@code clientName} has the exact shape of a provisioning name, i.e. names a
   *     client still being built. Case and surrounding blanks are ignored; any other name starting
   *     with the prefix is an ordinary company name.
   */
  static boolean matches(String clientName) {
    return clientName != null && NAME.matcher(clientName.trim()).matches();
  }
}
