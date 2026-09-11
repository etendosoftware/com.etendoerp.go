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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.openbravo.model.ad.access.Role;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * ETP-5088 — Resolves which dashboard widgets (and which of their individual rows) a role may
 * see, derived from the {@code AD_Window_Access} grants the tenant already provisions rather than
 * from any new per-role widget configuration.
 *
 * <p>The requirement is the widget × role matrix attached to ETP-5088. Every row of it was
 * verified to be reproducible from the real grants, so nothing here hardcodes a role name: the
 * same declarations keep working for tenant-specific or future roles.</p>
 *
 * <p><b>This is the server-side half of the gate.</b> The frontend applies the identical
 * declarations in {@code tools/app-shell/src/lib/dashboardWidgetAccess.js} (schema_forge) so a
 * hidden widget is not even requested. The two must not drift: a widget added on one side has to
 * be declared on the other. The SLUGS below are deliberately the same strings the frontend and
 * the widget payloads use ({@code navigation.window}).</p>
 *
 * <p><b>Denied means empty, not 403.</b> Every caller returns an empty payload for a widget the
 * role may not see, following the {@code SFListMenu}/{@code SFWindowAccessMap} family's "deny
 * silently" convention — a restricted role gets a smaller dashboard, never an error toast.</p>
 *
 * <p><b>Resolve the role BEFORE {@code OBContext.setAdminMode(true)}.</b> Admin mode exists to
 * bypass row-level security on the widget queries, never to decide access; every handler captures
 * the role at the very top of {@code handle()}, the same convention documented for
 * {@code SFListMenu} and {@code SFWindowAccessMap} in {@code docs/neo-headless.md} §7.</p>
 */
public final class WidgetAccessPolicy {

  /** {@code AD_Window_ID}s. Core windows keep their legacy numeric IDs; financial-account is the
   *  Etendo GO window, distributed with the module, and is already referenced by that same
   *  literal on the frontend ({@code windows/custom/financial-account/index.jsx}). */
  public static final String WINDOW_CONTACTS = "123";
  public static final String WINDOW_PRODUCT = "140";
  public static final String WINDOW_SALES_ORDER = "143";
  public static final String WINDOW_SALES_INVOICE = "167";
  public static final String WINDOW_PHYSICAL_INVENTORY = "168";
  public static final String WINDOW_GOODS_SHIPMENT = "169";
  public static final String WINDOW_PURCHASE_ORDER = "181";
  public static final String WINDOW_PURCHASE_INVOICE = "183";
  public static final String WINDOW_GOODS_RECEIPT = "184";
  public static final String WINDOW_FINANCIAL_ACCOUNT = "94EAA455D2644E04AB25D93BE5157B6D";

  /** Window slugs as they appear in a widget payload's {@code navigation.window}. */
  public static final String SLUG_CONTACTS = "contacts";
  public static final String SLUG_PRODUCT = "product";
  public static final String SLUG_SALES_ORDER = "sales-order";
  public static final String SLUG_SALES_INVOICE = "sales-invoice";
  public static final String SLUG_PHYSICAL_INVENTORY = "physical-inventory";
  public static final String SLUG_GOODS_SHIPMENT = "goods-shipment";
  public static final String SLUG_PURCHASE_ORDER = "purchase-order";
  public static final String SLUG_PURCHASE_INVOICE = "purchase-invoice";
  public static final String SLUG_GOODS_RECEIPT = "goods-receipt";

  private static final Map<String, String> WINDOW_ID_BY_SLUG;

  static {
    Map<String, String> bySlug = new HashMap<>();
    bySlug.put(SLUG_CONTACTS, WINDOW_CONTACTS);
    bySlug.put(SLUG_PRODUCT, WINDOW_PRODUCT);
    bySlug.put(SLUG_SALES_ORDER, WINDOW_SALES_ORDER);
    bySlug.put(SLUG_SALES_INVOICE, WINDOW_SALES_INVOICE);
    bySlug.put(SLUG_PHYSICAL_INVENTORY, WINDOW_PHYSICAL_INVENTORY);
    bySlug.put(SLUG_GOODS_SHIPMENT, WINDOW_GOODS_SHIPMENT);
    bySlug.put(SLUG_PURCHASE_ORDER, WINDOW_PURCHASE_ORDER);
    bySlug.put(SLUG_PURCHASE_INVOICE, WINDOW_PURCHASE_INVOICE);
    bySlug.put(SLUG_GOODS_RECEIPT, WINDOW_GOODS_RECEIPT);
    WINDOW_ID_BY_SLUG = Collections.unmodifiableMap(bySlug);
  }

  private WidgetAccessPolicy() {
  }

  /**
   * Whether {@code role} can open the given window at all (either access tier).
   *
   * <p>Reads are the only thing a widget ever does, so this always asks with {@code GET}: a
   * read-only {@code AD_Window_Access} row is enough to see a widget, exactly as it is enough to
   * open the window itself. Fails closed on a {@code null} role.</p>
   *
   * @param role the caller's role, resolved before admin mode (may be {@code null})
   * @param windowId the {@code AD_Window_ID} to check
   * @return {@code true} if the role may read that window
   */
  public static boolean canRead(Role role, String windowId) {
    return role != null && NeoAccessHelper.hasWindowAccess(role, windowId, "GET");
  }

  /**
   * Whether {@code role} can read the window a widget row navigates to, given the row's slug.
   *
   * <p>Fails closed on a slug this policy does not know: a NEW, unmapped kind of row disappears
   * instead of leaking, which is the safe direction for a permission filter. Mirrors the
   * frontend's {@code dropUnresolved} default.</p>
   *
   * @param role the caller's role (may be {@code null})
   * @param slug the payload's {@code navigation.window} value (may be {@code null}/blank)
   * @return {@code true} if the role may read the window that slug names
   */
  public static boolean canReadSlug(Role role, String slug) {
    if (role == null || slug == null || slug.trim().isEmpty()) {
      return false;
    }
    String windowId = WINDOW_ID_BY_SLUG.get(slug.trim());
    return windowId != null && canRead(role, windowId);
  }

  /**
   * Convenience for the handlers: the current role, resolved from the ambient {@link
   * org.openbravo.dal.core.OBContext} <b>before</b> the handler enters admin mode.
   *
   * @return the caller's role, or {@code null} when none is assigned
   */
  public static Role currentRole() {
    return NeoAccessHelper.resolveCurrentRole();
  }
}
