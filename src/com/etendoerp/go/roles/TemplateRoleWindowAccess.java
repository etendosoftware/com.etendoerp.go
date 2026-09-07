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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.roles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ETP-4878 — the real window-access permission matrix for the four system-level template roles
 * ({@link SystemRoleTemplates}), replacing the old 2-window-per-role smoke test that only proved
 * propagation end-to-end. One entry per template role id, each holding the FULL list of {@link
 * WindowGrant}s from the ticket's Ventas/Compras/Financiero/Almacén matrix ("Admin" stays
 * client-level, out of scope — see {@link SystemRoleTemplates}'s own javadoc).
 *
 * <p><b>Why this matrix lives here as a plain {@code src/} class instead of only inside {@code
 * EnsureSystemRoleTemplatesScript}</b> (the {@code ModuleScript} that actually applies it, under
 * {@code src-util/modulescript}): keeping it here, as plain data with zero SQL/DAL/{@code
 * ConnectionProvider} dependencies, makes it reachable by a normal {@code src-test} unit test with
 * no DB and no Gradle classpath workaround — see {@code TemplateRoleWindowAccessTest}. {@code
 * EnsureSystemRoleTemplatesScript} does NOT import this class, though: that source root
 * ({@code src-util/modulescript/}) is self-contained by design (no dependency on this module's own
 * {@code src/} tree, full stop — see that class's javadoc for why, including a build-order
 * pitfall an earlier revision of this comment got wrong). The script keeps its own inlined,
 * literal copy of this same matrix instead; this class stays the canonical, testable source of
 * truth for everything else ({@code UserRoleCompositionService}, the webhooks, and this class's
 * own tests).</p>
 *
 * <p><b>Ten matrix rows are intentionally NOT represented here — known gap.</b> Every excluded
 * row has NO {@code AD_Window_ID} at all backing it (either a pure custom/aggregate Schema Forge
 * page with zero classic-AD entity, or a report-type spec whose access is resolved via a
 * different, non-window mechanism): Inicio (Dashboard), Favoritos, Copilot (Asistente IA),
 * Documentos no contabilizados, Informes de inventario, Informes financieros, Informe Antigüedad
 * de Cobros, Informe Antigüedad de Pagos, Escaneo inteligente, Configuración fiscal. See
 * {@code EnsureSystemRoleTemplatesScript}'s class javadoc for the full per-row resolution detail,
 * and {@code docs/neo-headless.md} in this module for the research dispatch's complete mapping
 * table.</p>
 *
 * <p><b>"Monitor fiscal" and "Modelos fiscales" were originally in that windowless-gap list too,
 * but ETP-5116 resolved both for Finance via a proxy grant</b> onto a different, real classic
 * window that serves as their closest access-control stand-in: {@code
 * FEF76C3E0F104F06A89AAD15A4A4A35C} (SII Monitor) for "Monitor fiscal", and {@code
 * 3E8FEA1EA7404D979306C9EE7FD2E7E8} (Tax Report) for "Modelos fiscales". Neither Schema-Forge-only
 * page has a window of its own — the grant is a deliberate proxy, not a literal match — mirroring
 * the same pattern {@code SFRolesOverview} already uses for its own read-side resolution (see
 * that class's {@code FISCAL_MONITOR_PROXY_WINDOW_ID}/{@code TAX_MODELS_PROXY_WINDOW_ID}, the
 * same two ids).</p>
 *
 * <p><b>"Documentos no contabilizados" remains unresolved here — ETP-5116 investigation, not a new
 * gap.</b> Financiero needs FULL access to process {@code D6AB95CE52D34E1599590526115E26C6} via
 * {@code OBUIAPP_Process_Access} (proxying "Not Posted Documents"), but that is a standalone
 * process grant, not a window grant — this class only models {@link WindowGrant}s, and {@code
 * EnsureSystemRoleTemplatesScript#reconcileProcessAccess} only ever DERIVES process access from a
 * role's FULL window grants (button-linked processes on that window's tabs); it has no mechanism
 * to reconcile a standalone process id with no backing window. Populating this row requires
 * designing that mechanism first — deliberately left out of this matrix until that design lands.
 * "Informe Antigüedad de Cobros"/"Informe Antigüedad de Pagos" hit this exact same gap under a
 * fresh ETP-5116 investigation: both {@code AgingReportHandler} OBUIAPP process ids
 * (Receivables {@code 0D37A9F6109549DEB058373EF2DAEB6A}, Payables
 * {@code EB4C4053F3B94A17A08D1DD7E89CEB7E}) are real, confirmed via the same
 * {@code AD_Menu.em_obuiapp_process_id} FK chain, but neither {@code AD_Menu} row has an
 * {@code ad_window_id} either — so the target Ventas/Compras/Financiero grants from that
 * investigation are equally blocked on the same missing standalone-process-reconciliation
 * mechanism, not on any missing id.</p>
 *
 * <p><b>"Roles", "Usuario", and "Conectar asistente de IA" resolve to real {@code AD_Window_ID}s
 * but are deliberately absent from every role's grant list below</b> — the ticket's matrix shows
 * "—" (no access) for all four non-Admin templates on all three; they stay Admin-only.</p>
 *
 * <p><b>"Asientos manuales" resolves to Simple G/L Journal</b> ({@code
 * B917E8A7B0864ACEA9D941E3B7494E53}), NOT the classic {@code G/L Journal} window ({@code 132}),
 * which literally carries the matching ES label but has no Schema Forge spec — an explicit human
 * decision on an otherwise genuinely ambiguous resolution (ETP-4878 decision 2).</p>
 */
public final class TemplateRoleWindowAccess {

  private TemplateRoleWindowAccess() {
    // constants holder
  }

  /** One window grant: the window id and whether it is read-only ("R") vs. full ("✓") access. */
  public static final class WindowGrant {
    private final String windowId;
    private final boolean readOnly;

    /**
     * Creates a grant for {@code windowId} with the given {@code readOnly} access level.
     *
     * @param windowId the {@code AD_Window_ID} being granted
     * @param readOnly {@code true} for read-only ("R") access, {@code false} for full ("✓") access
     */
    public WindowGrant(String windowId, boolean readOnly) {
      this.windowId = windowId;
      this.readOnly = readOnly;
    }

    public String getWindowId() {
      return windowId;
    }

    public boolean isReadOnly() {
      return readOnly;
    }
  }

  private static WindowGrant full(String windowId) {
    return new WindowGrant(windowId, false);
  }

  private static WindowGrant readOnly(String windowId) {
    return new WindowGrant(windowId, true);
  }

  /**
   * Sales ("Ventas") column of the ETP-4878 matrix — 12 grants (13 in the original ticket matrix,
   * minus the {@code full("168")} over-grant on Inventario físico / Physical Inventory removed by
   * ETP-5116: Ventas should have NO access to that window). Comments name the matrix row in
   * Spanish (matching the ticket) followed by the AD_Window's own English name.
   */
  private static List<WindowGrant> salesGrants() {
    return list(
        full("123"),                                          // Contactos — Business Partner
        full("6CB5B67ED33F47DFA334079D3EA2340E"),              // Presupuesto — Sales Quotation
        full("143"),                                           // Pedido de venta — Sales Order
        full("169"),                                           // Albarán de venta — Goods Shipment
        full("167"),                                           // Factura de venta — Sales Invoice
        full("FF808081330213E60133021822E40007"),              // Albarán de devolución — Return from Customer
        full("140"),                                           // Producto — Product
        readOnly("144"),                                       // Categoría del producto — Product Category
        full("E547CE89D4C04429B6340FFA44E70716"),              // Cobro — Payment In
        full("146"),                                           // Tarifa — Price List
        readOnly("141"),                                       // Condiciones de pago — Payment Term
        readOnly("192"));                                      // Categoría de contacto — Business Partner Category
  }

  /**
   * Purchasing ("Compras") column of the ETP-4878 matrix — 11 grants, plus {@code 107}
   * (Receipt-Invoice Link, added after the original matrix by ETP-5075).
   *
   * <p>Window 107 is granted FULL even though the window's DATA is read-only: its accounting
   * posting action is invoked as a {@code POST} on the action sub-endpoint, and
   * {@code NeoRequestRouter} gates every window request through
   * {@code NeoAccessHelper#hasWindowAccess}, which for a write method requires
   * {@code AD_Window_Access.IsReadWrite='Y'} — a read-only grant makes the post fail with 403
   * before process access is even evaluated. The data stays read-only through a separate,
   * independent gate ({@code ETGO_SF_ENTITY.ISPOST/ISPUT/ISPATCH/ISDELETE='N'}), so FULL here
   * opens the action channel only, never a CRUD write path.
   */
  private static List<WindowGrant> purchasingGrants() {
    return list(
        full("123"),                                          // Contactos — Business Partner
        full("181"),                                          // Pedido de compra — Purchase Order
        full("184"),                                          // Albarán de compra — Goods Receipt
        full("183"),                                          // Factura de compra — Purchase Invoice
        full("107"),                                          // Relación albarán-factura — Receipt-Invoice Link (ETP-5075)
        full("C50A8AEE6F044825B5EF54FAAE76826F"),              // Devolución a proveedor — Return to Vendor
        full("140"),                                          // Producto — Product
        readOnly("144"),                                       // Categoría del producto — Product Category
        full("6F8F913FA60F4CBD93DC1D3AA696E76E"),              // Pago — Payment Out
        full("146"),                                          // Tarifa — Price List
        readOnly("141"),                                       // Condiciones de pago — Payment Term
        readOnly("192"));                                      // Categoría de contacto — Business Partner Category
  }

  /**
   * Finance ("Financiero") column of the ETP-4878 matrix — 28 grants: 25 from the original ticket
   * matrix (27 minus the two ETP-5116 over-grants removed below — Categoría del producto /
   * Product Category and Inventario físico / Physical Inventory, neither of which Financiero
   * should have access to), plus {@code 107} (Receipt-Invoice Link, ETP-5075 — see {@link
   * #purchasingGrants()}) and 2 new ETP-5116 proxy grants (SII Monitor and Tax Report — see the
   * class javadoc's "Monitor fiscal"/"Modelos fiscales" note).
   */
  private static List<WindowGrant> financeGrants() {
    return list(
        full("123"),                                          // Contactos — Business Partner
        readOnly("6CB5B67ED33F47DFA334079D3EA2340E"),          // Presupuesto — Sales Quotation
        readOnly("143"),                                       // Pedido de venta — Sales Order
        full("167"),                                          // Factura de venta — Sales Invoice
        readOnly("181"),                                       // Pedido de compra — Purchase Order
        full("183"),                                          // Factura de compra — Purchase Invoice
        full("107"),                                          // Relación albarán-factura — Receipt-Invoice Link (ETP-5075)
        full("140"),                                          // Producto — Product
        full("139"),                                          // Almacén — Warehouse and Storage Bins
        full("E547CE89D4C04429B6340FFA44E70716"),              // Cobro — Payment In
        full("6F8F913FA60F4CBD93DC1D3AA696E76E"),              // Pago — Payment Out
        full("94EAA455D2644E04AB25D93BE5157B6D"),              // Cuentas — Financial Account
        full("118"),                                          // Plan de cuentas — Account Tree
        full("125"),                                          // Esquema contable — General Ledger Configuration
        full("117"),                                          // Calendario — Fiscal Calendar
        full("800027"),                                       // Activos — Assets
        full("252"),                                          // Grupo de activos — Asset Group
        full("800026"),                                       // Amortización — Amortization
        full("B917E8A7B0864ACEA9D941E3B7494E53"),              // Asientos manuales — Simple G/L Journal (decision 2)
        full("116"),                                          // Rangos de conversión — Conversion Rates
        full("146"),                                          // Tarifa — Price List
        full("141"),                                          // Condiciones de pago — Payment Term
        full("137"),                                          // Impuesto — Tax Rate
        full("138"),                                          // Categoría de impuesto — Tax Category
        full("192"),                                          // Categoría de contacto — Business Partner Category
        full("6FEBA130CDE24CC09041FFA6117ADFA9"),             // Registro descarga tipos de cambio — Conversion Rate Downloader Log
        full("FEF76C3E0F104F06A89AAD15A4A4A35C"),              // SII Monitor — proxies "Monitor Fiscal" (ETP-5116)
        full("3E8FEA1EA7404D979306C9EE7FD2E7E8"));             // Tax Report — proxies "Modelos Fiscales" (ETP-5116)
  }

  /** Inventory ("Almacén") column of the ETP-4878 matrix — 13 grants. */
  private static List<WindowGrant> inventoryGrants() {
    return list(
        readOnly("123"),                                      // Contactos — Business Partner
        readOnly("143"),                                       // Pedido de venta — Sales Order
        full("169"),                                          // Albarán de venta — Goods Shipment
        full("FF808081330213E60133021822E40007"),              // Albarán de devolución — Return from Customer
        readOnly("181"),                                       // Pedido de compra — Purchase Order
        full("184"),                                          // Albarán de compra — Goods Receipt
        full("C50A8AEE6F044825B5EF54FAAE76826F"),              // Devolución a proveedor — Return to Vendor
        full("140"),                                          // Producto — Product
        full("144"),                                          // Categoría del producto — Product Category
        full("168"),                                          // Inventario físico — Physical Inventory
        full("170"),                                          // Movimiento entre almacenes — Goods Movements
        full("800076"),                                       // Consumo interno — Internal Consumption
        full("139"));                                          // Almacén — Warehouse and Storage Bins
  }

  private static List<WindowGrant> list(WindowGrant... grants) {
    List<WindowGrant> mutable = new ArrayList<>();
    Collections.addAll(mutable, grants);
    return Collections.unmodifiableList(mutable);
  }

  /**
   * The full role→grant-list matrix, keyed by {@code AD_Role_ID} — one entry per template role,
   * in Finance/Sales/Purchasing/Inventory order (mirrors {@link SystemRoleTemplates#byName()}).
   *
   * @return a fresh, mutable {@link LinkedHashMap} from template role id to its (immutable) list
   *     of {@link WindowGrant}s
   */
  public static Map<String, List<WindowGrant>> byRoleId() {
    Map<String, List<WindowGrant>> map = new LinkedHashMap<>();
    map.put(SystemRoleTemplates.FINANCE_ROLE_ID, financeGrants());
    map.put(SystemRoleTemplates.SALES_ROLE_ID, salesGrants());
    map.put(SystemRoleTemplates.PURCHASING_ROLE_ID, purchasingGrants());
    map.put(SystemRoleTemplates.INVENTORY_ROLE_ID, inventoryGrants());
    return map;
  }
}
