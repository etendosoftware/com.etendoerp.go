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
package com.etendoerp.go.schemaforge.selector.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Declares the {@code C_Location} columns that the address wrapper entities expose as if they
 * were their own, and resolves them to the backing {@code AD_Column}.
 *
 * <p><b>Why a wrapper needs this at all.</b> {@code locationAddress} is backed by
 * {@code C_BPartner_Location}, which holds the business-partner link and little else: the street,
 * the city, the postal code, the country and the province all live one table over, in
 * {@code C_Location}. {@code ContactsLocationAddressHandler} hides that split — it accepts those
 * names in the payload and writes both rows in one transaction — so from a caller's point of view
 * they are fields of {@code locationAddress}. Nothing in {@code AD_Tab} or {@code ETGO_SF_FIELD}
 * records that, because they are not columns of the wrapper's table.
 *
 * <p><b>ETP-5368 — why the list is here rather than in each caller.</b> This class used to answer
 * one question, for the REST selector endpoint only: which two FK columns a selector URL may name.
 * The MCP resolved its own columns against the wrapper's table and therefore saw none of them, so
 * {@code neo_selectors} answered {@code Column not found in table: region} and {@code neo_schema}
 * never listed a single address field — an agent could only reach them by guessing names it had
 * no way to read anywhere. The fix is one declaration both front doors consult, not a second copy
 * of the list on the MCP side, which is exactly how the two drifted apart in the first place.
 *
 * <p>{@link #SELECTOR_COLUMNS} is deliberately narrower than {@link #VIRTUAL_COLUMNS}: only the
 * two FKs have a selector to query. Asking for one over a free-text column is a caller error and
 * must keep answering as one.
 */
public final class AddressVirtualSelectorPolicy {

  private static final Logger log = LogManager.getLogger(AddressVirtualSelectorPolicy.class);

  private static final String LOCATION_TABLE = "C_Location";
  private static final String BPARTNER_LOCATION_TABLE = "C_BPartner_Location";
  private static final String LOCATION_ADDRESS_ENTITY = "locationAddress";

  private static final String COLUMN_COUNTRY = "C_Country_ID";
  private static final String COLUMN_REGION = "C_Region_ID";

  /** The virtual columns that back an FK selector. A subset of {@link #VIRTUAL_COLUMNS}. */
  private static final Set<String> SELECTOR_COLUMNS = new LinkedHashSet<>(
      List.of(COLUMN_COUNTRY, COLUMN_REGION));

  /**
   * Every {@code C_Location} column the wrapper accepts, in the order a caller fills an address.
   *
   * <p>Matches what {@code ContactsLocationAddressHandler} reads from the payload, by DAL property
   * name: {@code addressLine1}, {@code addressLine2}, {@code cityName}, {@code postalCode},
   * {@code country}, {@code region}, {@code regionName}. The names are never written by hand — the
   * caller resolves each column to its property through the DAL, so the two cannot disagree.
   *
   * <p>{@code regionName} sits next to {@code region} on purpose: they are the same answer for two
   * kinds of country, and the handler keeps them mutually exclusive. See
   * {@code ContactsLocationAddressHandler#assignRegion}.
   */
  private static final Set<String> VIRTUAL_COLUMNS = new LinkedHashSet<>(List.of(
      "Address1", "Address2", "City", "Postal", COLUMN_COUNTRY, COLUMN_REGION, "RegionName"));

  /**
   * The wrapper's OWN column that the handler resolves instead of the caller (ETP-5368).
   *
   * <p>{@code C_BPartner_Location.C_Location_ID} is {@code NOT NULL}, so AD calls it mandatory and
   * {@code neo_schema} listed it as the one field an agent MUST send. That is true of the row and
   * false of the payload: {@code ContactsLocationAddressHandler} accepts an address in two shapes —
   * reuse an existing {@code C_Location} by id, or hand it the raw fields and let it build one —
   * and <b>sending both is refused</b>. So an agent that obeyed the schema picked the reuse mode,
   * which needs an id no contacts endpoint can produce, while the mode the SPA itself always uses
   * was not advertised at all.
   *
   * <p>Reporting it as server-resolved moves it to {@code optional} carrying
   * {@code serverDefaulted:true}, which is this schema's existing vocabulary for exactly this —
   * "mandatory in Etendo, but the server already has a value, so do not ask the user". The reuse
   * mode stays available to a caller that does have an id; it simply stops being an instruction.
   */
  private static final String OWN_SERVER_RESOLVED_COLUMN = "C_Location_ID";

  private AddressVirtualSelectorPolicy() {
  }

  /**
   * The wrapper's own fields that the handler resolves server-side, by published field name.
   *
   * @param entity source Schema Forge entity
   * @return the field names, or an empty set when no wrapper policy applies
   */
  public static Set<String> serverResolvedFieldNames(SFEntity entity) {
    if (!isAddressWrapper(entity)) {
      return Set.of();
    }
    org.openbravo.model.ad.ui.Tab tab = entity.getADTab();
    if (tab == null || tab.getTable() == null) {
      return Set.of();
    }
    Entity dalEntity = ModelProvider.getInstance()
        .getEntityByTableName(tab.getTable().getDBTableName());
    if (dalEntity == null) {
      return Set.of();
    }
    Property property = dalEntity.getPropertyByColumnName(OWN_SERVER_RESOLVED_COLUMN, false);
    return property == null ? Set.of() : Set.of(property.getName());
  }

  /**
   * Whether the given Schema Forge entity is an address wrapper — one whose caller-facing address
   * fields live in {@code C_Location} rather than in its own table.
   *
   * @param entity source Schema Forge entity; may be {@code null}
   * @return {@code true} when the wrapper policy applies to this entity
   */
  public static boolean isAddressWrapper(SFEntity entity) {
    if (entity == null) {
      return false;
    }
    org.openbravo.model.ad.ui.Tab tab = entity.getADTab();
    String tableName = tab != null && tab.getTable() != null
        ? tab.getTable().getDBTableName()
        : null;
    return BPARTNER_LOCATION_TABLE.equalsIgnoreCase(tableName)
        || LOCATION_ADDRESS_ENTITY.equals(entity.getName());
  }

  /**
   * Resolve the backing location column for a virtual address wrapper selector.
   *
   * @param entity source Schema Forge entity
   * @param columnName requested DB column name
   * @return the backing location column, or {@code null} when the wrapper policy does not apply
   */
  public static Column resolveVirtualSelectorColumn(SFEntity entity, String columnName) {
    if (!isAddressWrapper(entity) || StringUtils.isBlank(columnName)) {
      return null;
    }
    Column column = locationColumns().get(columnName.toUpperCase());
    // Gate on the resolved column rather than on the caller's spelling: the same column arrives
    // under two names (see locationColumns), and only one set of them is worth checking twice.
    if (column == null || !SELECTOR_COLUMNS.contains(column.getDBColumnName())) {
      return null;
    }
    log.debug("Resolved virtual selector column {} for entity {}", columnName, entity.getName());
    return column;
  }

  /**
   * Every backing {@code C_Location} column this wrapper exposes, in declaration order.
   *
   * <p>Returns an empty list — never {@code null} — when the entity is not a wrapper, so a caller
   * can append the result unconditionally.
   *
   * @param entity source Schema Forge entity
   * @return the backing location columns, in the order of {@link #VIRTUAL_COLUMNS}
   */
  public static List<Column> resolveVirtualColumns(SFEntity entity) {
    if (!isAddressWrapper(entity)) {
      return List.of();
    }
    Map<String, Column> byName = locationColumns();
    List<Column> resolved = new ArrayList<>();
    for (String columnName : VIRTUAL_COLUMNS) {
      Column column = byName.get(columnName.toUpperCase());
      if (column != null) {
        resolved.add(column);
      }
    }
    return resolved;
  }

  /**
   * Loads the declared {@code C_Location} columns in one query, keyed by upper-cased DB name.
   *
   * <p>One round trip for the whole set rather than one per column: {@link #resolveVirtualColumns}
   * asks for all seven, and it runs on the {@code neo_schema} path, which is already the heaviest
   * read an agent makes.
   */
  private static Map<String, Column> locationColumns() {
    OBCriteria<Column> criteria = OBDal.getInstance().createCriteria(Column.class);
    criteria.createAlias(Column.PROPERTY_TABLE, "tbl");
    criteria.add(Restrictions.eq("tbl.dBTableName", LOCATION_TABLE));
    criteria.add(Restrictions.in(Column.PROPERTY_DBCOLUMNNAME, VIRTUAL_COLUMNS));
    Entity locationEntity = ModelProvider.getInstance().getEntityByTableName(LOCATION_TABLE);
    Map<String, Column> byName = new LinkedHashMap<>();
    for (Column column : criteria.list()) {
      byName.put(column.getDBColumnName().toUpperCase(), column);
      // ETP-5368, found in live verification: the caller may legitimately use either spelling, and
      // the two front doors do not use the same one. The SPA's selector URL names the DB column
      // (C_Region_ID), while neo_schema publishes — and ContactsLocationAddressHandler reads — the
      // DAL property (region). Keying on the DB name alone made neo_selectors answer "Column not
      // found in table: region" for the exact name its own schema had just handed the agent, which
      // is the inconsistency this ticket exists to remove rather than relocate.
      Property property = locationEntity == null
          ? null
          : locationEntity.getPropertyByColumnName(column.getDBColumnName(), false);
      if (property != null) {
        byName.put(property.getName().toUpperCase(), column);
      }
    }
    return byName;
  }
}
