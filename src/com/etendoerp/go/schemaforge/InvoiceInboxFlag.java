/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.common.GoAccountResolver;
import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * ETP-6047 — the one gate of the {@value GoFeatureFlags#FLAG_INVOICE_INBOX} flag. Retiring the
 * flag is a grep for this class. It is the <b>only</b> reader of the flag: never evaluate the key
 * anywhere else.
 *
 * <p><b>Evaluate at request entry, never in the OCR worker.</b> {@link #isEnabled()} resolves the
 * account from the {@code OBContext} user, which exists only while the request runs as the account
 * owner. The asynchronous OCR worker has no such user, so there {@code currentAccountEmail()}
 * would answer {@code null} and a targeted user would look "off". The upload endpoint therefore
 * asks once, before enqueuing, and the worker must not re-evaluate the flag. A caller that does not
 * run as the account owner uses {@link #isEnabled(String, String)} with the values captured at
 * request entry.
 *
 * <p><b>Off is the default.</b> {@link GoFeatureFlags#isEnabled} answers {@code false} for an
 * unconfigured flag, an unreachable control plane and any evaluation error; neither method throws.
 *
 * <p>The flag gates behaviour, not schema: the incoming-document table and window ship
 * unconditionally.
 */
public final class InvoiceInboxFlag {

  private InvoiceInboxFlag() {
  }

  /**
   * Whether the invoice inbox is enabled for the current request user and tenant. Targeted on the
   * {@code ETGO_ACCOUNT} email resolved by {@link GoAccountResolver#currentAccountEmail()} plus
   * the tenant of the current {@link OBContext}. Call it at request entry only.
   *
   * @return {@code true} only when the flag resolves to on; {@code false} otherwise
   */
  public static boolean isEnabled() {
    return isEnabled(GoAccountResolver.currentAccountEmail(), currentClientId());
  }

  /**
   * Whether the invoice inbox is enabled for an explicit account and tenant, for callers that do
   * not run as the account owner (for example work handed to a background thread).
   *
   * @param accountEmail the account email captured at request entry; blank is omitted, so an email
   *     rule does not match and the default stays off
   * @param clientId the tenant, published as {@link FeatureFlagContext#ATTRIBUTE_CLIENT_ID};
   *     blank is omitted
   * @return {@code true} only when the flag resolves to on; {@code false} otherwise
   */
  public static boolean isEnabled(String accountEmail, String clientId) {
    return GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_INVOICE_INBOX,
        FeatureFlagContext.forAccount(accountEmail)
            .with(FeatureFlagContext.ATTRIBUTE_CLIENT_ID, clientId));
  }

  /**
   * Whether the invoice inbox is enabled for an account the caller has already resolved, on the
   * current request's tenant. Used by {@code SFWindowAccessMap}, which resolves the account once for
   * every capability it publishes and republishes this answer as {@code capabilities.invoiceInbox},
   * so the browser never evaluates the flag itself.
   *
   * @param accountEmail the {@code ETGO_ACCOUNT} email the flag is targeted on; blank means no
   *     account, which no per-account rule matches
   * @return {@code true} only when the flag resolves to on; {@code false} otherwise
   */
  public static boolean isEnabledFor(String accountEmail) {
    return isEnabled(accountEmail, currentClientId());
  }

  /** The tenant of the current {@link OBContext}, or {@code null} when there is none. */
  private static String currentClientId() {
    try {
      OBContext obContext = OBContext.getOBContext();
      return obContext == null || obContext.getCurrentClient() == null
          ? null
          : obContext.getCurrentClient().getId();
    } catch (RuntimeException e) {
      return null; // targeting enrichment must never fail the request
    }
  }
}
