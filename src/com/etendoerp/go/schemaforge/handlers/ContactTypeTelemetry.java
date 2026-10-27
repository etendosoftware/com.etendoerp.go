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

package com.etendoerp.go.schemaforge.handlers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoBatchScope;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoWriteChannel;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvents;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService;

/**
 * Contact-type KPI telemetry (ETP-5791): {@code backend_contact_created} and
 * {@code backend_contact_type_changed}.
 *
 * <p>Called by the customizations that write {@code C_BPartner} through NEO — the contact header
 * ({@code BusinessPartnerHandler}) and its Customer / Vendor tabs
 * ({@link ContactTypeFlagGuardHandler}). The pre-hook takes a snapshot before the write (what the
 * caller chose, the stored types, the tenant), the post-hook compares it with the stored row after
 * the write and hands the event to {@link NeoBatchScope#runAfterCommit}, so an event is only ever
 * sent for a write that is durable: a rejected write never reaches the post-hook, and a batch or
 * import row that rolls back drops what it queued. Emitted whatever the
 * {@code contact-category-type} flag says; {@code flag_enabled} carries it as the baseline
 * segment.</p>
 *
 * <p><b>No record identifier ever leaves this class</b> (PM legal rule): the only id is the tenant
 * as {@code account_id}. Sending happens on one bounded background thread, so neither a slow
 * Mixpanel nor a large import makes a save wait; when the queue is full the event is dropped
 * (telemetry is best-effort and has no retry or de-duplication — {@code NeoTelemetryService}
 * sends each event once and carries no event id).</p>
 *
 * <p>Writes that do not go through NEO's contact CRUD emit nothing: SQL data-fixes, the demo-data
 * copy, onboarding sample data.</p>
 */
public final class ContactTypeTelemetry {

  private static final Logger log = LogManager.getLogger(ContactTypeTelemetry.class);

  /** {@code types_source}: the caller sent the flag(s). */
  static final String SOURCE_EXPLICIT = "explicit";
  /** {@code types_source}: taken from the contact's category (flag on only). */
  static final String SOURCE_INHERITED = "inherited";
  /**
   * {@code types_source}: neither sent nor inherited — the AD / mandatory defaults decided
   * ({@code IsCustomer=Y}, {@code IsVendor=N}). Always the case for an omitted flag while
   * {@code contact-category-type} is off, and with it on when the category defines no type.
   */
  static final String SOURCE_DEFAULT = "default";
  /** {@code types_source}: the two flags came from different sources. */
  static final String SOURCE_MIXED = "mixed";

  /** {@code change_trigger}: the types changed without a category change in the same write. */
  static final String TRIGGER_CHECKBOX = "checkbox";
  /** {@code change_trigger}: the same write moved the contact to another category. */
  static final String TRIGGER_CATEGORY_CHANGE = "category_change";
  /** {@code change_origin}: the caller sent the new types. */
  static final String ORIGIN_USER = "user";
  /** {@code change_origin}: the backend re-inherited them from the new category. */
  static final String ORIGIN_SYSTEM = "system";

  static final String ATTR_SNAPSHOT = "etgo.contactTypeTelemetry.snapshot";

  private static final String CONTACT_STATE_SQL =
      "SELECT iscustomer, isvendor, c_bp_group_id, created FROM c_bpartner WHERE c_bpartner_id = ?";
  private static final int QUEUE_CAPACITY = 1000;

  private static final ThreadPoolExecutor SENDER = buildSender();
  private static final AtomicReference<NeoTelemetryService> TELEMETRY_SERVICE =
      new AtomicReference<>();

  private ContactTypeTelemetry() {
  }

  /** What the pre-hook knew; kept on the {@link NeoContext} until the post-hook. */
  static final class Snapshot {
    final boolean create;
    final boolean flagEnabled;
    final String accountId;
    final String channel;
    final boolean customerExplicit;
    final boolean vendorExplicit;
    final boolean previousCustomer;
    final boolean previousVendor;
    final Instant created;
    final boolean categoryChanged;

    // previous: the stored row before the write, or null on a create (no previous types, no date)
    private Snapshot(boolean create, boolean flagEnabled, String accountId, boolean customerExplicit,
        boolean vendorExplicit, ContactState previous, boolean categoryChanged) {
      this.create = create;
      this.flagEnabled = flagEnabled;
      this.accountId = accountId;
      this.channel = NeoWriteChannel.current();
      this.customerExplicit = customerExplicit;
      this.vendorExplicit = vendorExplicit;
      this.previousCustomer = previous != null && previous.customer;
      this.previousVendor = previous != null && previous.vendor;
      this.created = previous != null ? previous.created : null;
      this.categoryChanged = categoryChanged;
    }
  }

  // ── Pre-hook ─────────────────────────────────────────────────────────────────────────────────

  /**
   * Create of a contact, before the type rules run: remembers which flags the caller sent. Never
   * throws.
   *
   * @param ctx the request context the snapshot is stored on
   * @param body the request body, before any type is inherited into it
   * @param flagEnabled whether {@code contact-category-type} is on for the tenant
   */
  public static void beforeCreate(NeoContext ctx, JSONObject body, boolean flagEnabled) {
    try {
      ctx.setAttribute(ATTR_SNAPSHOT, new Snapshot(true, flagEnabled, accountId(ctx),
          ContactTypeRules.isExplicit(ctx, body, ContactTypeRules.FIELD_CUSTOMER),
          ContactTypeRules.isExplicit(ctx, body, ContactTypeRules.FIELD_VENDOR),
          null, false));
    } catch (RuntimeException e) {
      log.debug("Contact telemetry: could not snapshot a create: {}", e.getMessage());
    }
  }

  /**
   * Update of a contact, after the type rules accepted it and before the write: remembers the
   * stored types. A write that carries neither type flag (after any re-inheritance) cannot change
   * them, so it costs no query. Never throws.
   *
   * @param ctx the request context the snapshot is stored on; carries the contact's id
   * @param body the request body, after any re-inheritance from the category
   * @param flagEnabled whether {@code contact-category-type} is on for the tenant
   * @param categoryAware {@code true} on the contact header, where the write may change the
   *     category; {@code false} on the Customer / Vendor tabs
   */
  public static void beforeUpdate(NeoContext ctx, JSONObject body, boolean flagEnabled,
      boolean categoryAware) {
    if (body == null || (!body.has(ContactTypeRules.FIELD_CUSTOMER)
        && !body.has(ContactTypeRules.FIELD_VENDOR))) {
      return;
    }
    try {
      ContactState stored = queryState(ctx.getRecordId());
      if (stored == null) {
        return;
      }
      boolean categoryChanged = false;
      if (categoryAware && body.has(ContactTypeRules.FIELD_CATEGORY)) {
        String newCategory = ContactTypeRules.idOf(body.opt(ContactTypeRules.FIELD_CATEGORY));
        categoryChanged = newCategory != null && !newCategory.equals(stored.categoryId);
      }
      ctx.setAttribute(ATTR_SNAPSHOT, new Snapshot(false, flagEnabled, accountId(ctx),
          body.has(ContactTypeRules.FIELD_CUSTOMER), body.has(ContactTypeRules.FIELD_VENDOR),
          stored, categoryChanged));
    } catch (Exception e) {
      log.debug("Contact telemetry: could not snapshot an update: {}", e.getMessage());
    }
  }

  // ── Post-hook ────────────────────────────────────────────────────────────────────────────────

  /**
   * After a successful write: builds the event the snapshot calls for and schedules it for after
   * commit. A no-op without a snapshot, for a failed write, or when the types did not change.
   * Never throws.
   *
   * @param ctx the request context holding the pre-hook snapshot and the write's result
   * @param recordId the contact's id — the created record's on a create, the request's on update
   */
  public static void afterWrite(NeoContext ctx, String recordId) {
    try {
      Object value = ctx.getAttribute(ATTR_SNAPSHOT);
      NeoResponse result = ctx.getPreviousResult();
      if (!(value instanceof Snapshot) || StringUtils.isBlank(recordId) || result == null
          || result.getHttpStatus() >= 400) {
        return;
      }
      Snapshot snapshot = (Snapshot) value;
      ContactState now = queryState(recordId);
      if (now == null) {
        return;
      }
      Map<String, Object> properties = snapshot.create
          ? createdProperties(ctx, snapshot, now)
          : typeChangedProperties(ctx, snapshot, now);
      if (properties.isEmpty()) {
        return;
      }
      String event = snapshot.create ? NeoTelemetryEvents.BACKEND_CONTACT_CREATED
          : NeoTelemetryEvents.BACKEND_CONTACT_TYPE_CHANGED;
      NeoBatchScope.runAfterCommit(() -> send(event, properties, snapshot.accountId));
    } catch (Exception e) {
      log.debug("Contact telemetry: could not build the event: {}", e.getMessage());
    }
  }

  static Map<String, Object> createdProperties(NeoContext ctx, Snapshot snapshot,
      ContactState now) {
    Map<String, Object> properties = base(snapshot);
    properties.put("is_customer", now.customer);
    properties.put("is_vendor", now.vendor);
    properties.put("types_source", typesSource(snapshot, inheritedFields(ctx)));
    return properties;
  }

  /** @return the properties, or an empty map when neither type actually changed */
  static Map<String, Object> typeChangedProperties(NeoContext ctx, Snapshot snapshot,
      ContactState now) {
    if (now.customer == snapshot.previousCustomer && now.vendor == snapshot.previousVendor) {
      return Collections.emptyMap();
    }
    Map<String, Object> properties = base(snapshot);
    properties.put("previous_is_customer", snapshot.previousCustomer);
    properties.put("previous_is_vendor", snapshot.previousVendor);
    properties.put("is_customer", now.customer);
    properties.put("is_vendor", now.vendor);
    if (snapshot.created != null) {
      properties.put("days_since_created",
          Math.max(0L, Duration.between(snapshot.created, Instant.now()).toDays()));
    }
    properties.put("change_trigger",
        snapshot.categoryChanged ? TRIGGER_CATEGORY_CHANGE : TRIGGER_CHECKBOX);
    properties.put("change_origin", inheritedFields(ctx).isEmpty() ? ORIGIN_USER : ORIGIN_SYSTEM);
    return properties;
  }

  private static Map<String, Object> base(Snapshot snapshot) {
    Map<String, Object> properties = new LinkedHashMap<>();
    if (snapshot.accountId != null) {
      properties.put("account_id", snapshot.accountId);
    }
    properties.put("channel", snapshot.channel);
    properties.put("flag_enabled", snapshot.flagEnabled);
    return properties;
  }

  /**
   * One source per flag (explicit / inherited / default), collapsed to one value, or
   * {@value #SOURCE_MIXED} when the two differ.
   */
  static String typesSource(Snapshot snapshot, Collection<?> inherited) {
    String customer = sourceOf(snapshot.customerExplicit,
        inherited.contains(ContactTypeRules.FIELD_CUSTOMER));
    String vendor = sourceOf(snapshot.vendorExplicit,
        inherited.contains(ContactTypeRules.FIELD_VENDOR));
    return customer.equals(vendor) ? customer : SOURCE_MIXED;
  }

  private static String sourceOf(boolean explicit, boolean inherited) {
    if (explicit) {
      return SOURCE_EXPLICIT;
    }
    return inherited ? SOURCE_INHERITED : SOURCE_DEFAULT;
  }

  private static Collection<?> inheritedFields(NeoContext ctx) {
    Object value = ctx.getAttribute(ContactTypeRules.ATTR_INHERITED_TYPE_FIELDS);
    return value instanceof Collection ? (Collection<?>) value : java.util.Collections.emptySet();
  }

  private static String accountId(NeoContext ctx) {
    OBContext obContext = ctx.getObContext();
    if (obContext == null) {
      obContext = OBContext.getOBContext();
    }
    return obContext != null && obContext.getCurrentClient() != null
        ? obContext.getCurrentClient().getId()
        : null;
  }

  // ── State and sending ────────────────────────────────────────────────────────────────────────

  /** The stored contact columns the events are built from. */
  static final class ContactState {
    final boolean customer;
    final boolean vendor;
    final String categoryId;
    final Instant created;

    ContactState(boolean customer, boolean vendor, String categoryId, Instant created) {
      this.customer = customer;
      this.vendor = vendor;
      this.categoryId = categoryId;
      this.created = created;
    }
  }

  static ContactState queryState(String contactId) throws SQLException {
    if (StringUtils.isBlank(contactId)) {
      return null;
    }
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(CONTACT_STATE_SQL)) {
      ps.setString(1, contactId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        Timestamp created = rs.getTimestamp(4);
        return new ContactState("Y".equals(rs.getString(1)), "Y".equals(rs.getString(2)),
            StringUtils.trimToNull(rs.getString(3)), created != null ? created.toInstant() : null);
      }
    }
  }

  private static void send(String event, Map<String, Object> properties, String clientId) {
    try {
      SENDER.execute(() -> telemetry().emit(event, properties, clientId));
    } catch (RejectedExecutionException e) {
      log.debug("Contact telemetry queue full; dropping {}", event);
    }
  }

  /** Package-private seam for tests; pass {@code null} to restore the runtime service. */
  static void setTelemetryService(NeoTelemetryService service) {
    TELEMETRY_SERVICE.set(service);
  }

  private static NeoTelemetryService telemetry() {
    NeoTelemetryService local = TELEMETRY_SERVICE.get();
    if (local == null) {
      synchronized (ContactTypeTelemetry.class) {
        local = TELEMETRY_SERVICE.get();
        if (local == null) {
          local = NeoTelemetryService.runtime();
          TELEMETRY_SERVICE.set(local);
        }
      }
    }
    return local;
  }

  private static ThreadPoolExecutor buildSender() {
    ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
          Thread thread = new Thread(runnable, "contact-kpi-telemetry");
          thread.setDaemon(true);
          return thread;
        });
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }
}
