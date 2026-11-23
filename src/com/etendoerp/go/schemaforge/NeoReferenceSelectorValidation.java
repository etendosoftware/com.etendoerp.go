/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.json.DataResolvingMode;
import org.openbravo.service.json.DataToJsonConverter;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/**
 * Refuses a NEO write that SETS a reference to a record the app would not offer for it (ETP-6176).
 *
 * <p><b>The rule.</b> If the app allows it, the API allows it; if the app does not, the API does
 * not. A reference the request sets is accepted only when the selector the UI shows for that field
 * — validation rule, selector policies and organization scoping, all of it, through
 * {@link NeoSelectorService#offersValue} — lists it. When the entity serves the field's selector
 * from its own SELECTOR customization, that customization is the selector the UI shows, so it is
 * the one asked ({@link NeoCustomizedSelectorCheck}, ETP-6377). Ownership
 * ({@link NeoReferenceOwnership}) and activeness ({@link NeoReferenceActiveness}) are checked before and stay as they are.
 *
 * <p><b>Context.</b> The UI resolves a selector against the whole form. Here the form is the
 * <i>merged record</i>: the parent record (child tabs; found through {@code parentId} or the
 * link-to-parent value the request sets), overlaid by the stored record (updates), overlaid by the
 * incoming values (on update, the callout outputs are already in them), completed with the tab's
 * literal auxiliary inputs (ETP-6381). Every property is published under its DAL name and under its
 * column name, which is how validation rules ({@code @C_BPartner_ID@}) and selector policies
 * ({@code businessPartner}) read it. So an address must belong to the partner, a price list must
 * match the document direction, a tax must be valid for the line, and a payment method must suit
 * the direction, with no entity named here.
 *
 * <p><b>Changed, or dependent on a change.</b> A reference is re-validated when its own value
 * changes, or when its selector reads a field the update changes (a new business partner re-checks
 * the stored address). Untouched references whose context did not change are skipped: an update
 * must not start failing on legacy data because the world moved on.
 *
 * <p><b>Subquery rules (ETP-6165).</b> {@code SelectorValidationResolver} skips a validation clause
 * with a nested subquery rather than translate it to HQL. Decision: this check does not add its own
 * translation. Selectors whose rule is raw SQL are routed through core's ComboTableData, which runs
 * the whole rule, so those are enforced; where a clause is skipped the write is accepted — the
 * clause is never applied half-way, and a valid record is never refused on a guess.
 *
 * <p><b>Fail closed.</b> A selector that cannot answer (error, unverifiable) refuses the write, like
 * one that answered and did not list the record.
 */
public final class NeoReferenceSelectorValidation {

  private static final Logger log = LogManager.getLogger(NeoReferenceSelectorValidation.class);
  private static final DateTimeFormatter CLASSIC_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
  private static final String YES = "Y";
  private static final String NO = "N";
  private static final String ID = "id";
  private static final Pattern CONTEXT_VARIABLE = Pattern.compile("@(\\w+)@");

  private NeoReferenceSelectorValidation() {
  }

  /**
   * Finds the first reference the request sets that the app's selector would not offer.
   *
   * @param sfEntity      the Schema Forge entity written; may be {@code null}
   * @param adTab         the tab being written
   * @param recordId      the id of the record being updated, {@code null} on create
   * @param parentId      the parent record id of a child create, or {@code null}
   * @param requestFields the body keyed by DAL property name, holding ONLY what the request set
   * @return the offending field and target entity, or {@code null} when every reference is offered
   */
  public static RefusedReference findRefusedReference(SFEntity sfEntity, Tab adTab,
      String recordId, String parentId, JSONObject requestFields) {
    if (adTab == null || adTab.getTable() == null || requestFields == null
        || OBContext.getOBContext() == null) {
      return null;
    }
    Entity written = ModelProvider.getInstance().getEntityByTableId(adTab.getTable().getId());
    if (written == null) {
      return null;
    }
    OBContext.setAdminMode(true);
    String[] current = new String[1];
    try {
      return firstRefused(sfEntity, adTab, written, recordId, parentId, requestFields, current);
    } catch (RuntimeException e) {
      // Fail closed: a selector check that errors cannot vouch for the value.
      log.warn("Selector validation of a NEO write failed, refusing it: {}", e.getMessage());
      return new RefusedReference(current[0] == null ? "record" : current[0], "unknown", null,
          false);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static RefusedReference firstRefused(SFEntity sfEntity, Tab adTab, Entity written,
      String recordId, String parentId, JSONObject requestFields, String[] current) {
    BaseOBObject stored = StringUtils.isBlank(recordId) ? null : load(written, recordId);
    Set<String> changed = changedKeys(written, stored, requestFields);
    Map<String, String> context = null;
    for (Property property : written.getProperties()) {
      String key = property.getName();
      boolean sent = requestFields.has(key);
      String id = referenceId(written, key, sent ? requestFields.opt(key) : storedId(stored, key));
      Column column = id == null ? null : columnOf(adTab, property);
      if (column == null || !mustValidate(column, key, sent, changed, stored)) {
        continue;
      }
      current[0] = key;
      if (context == null) {
        context = mergedContext(adTab, written, stored, parentId, requestFields);
      }
      RefusedReference refused = check(sfEntity, column, property, id, context);
      if (refused != null) {
        return refused;
      }
    }
    return null;
  }

  private static RefusedReference check(SFEntity sfEntity, Column column, Property property,
      String id, Map<String, String> context) {
    String key = property.getName();
    Optional<Boolean> offered = offeredByTheAppSelector(sfEntity, column, key, id, context);
    if (Boolean.TRUE.equals(offered.orElse(null))) {
      return null;
    }
    log.warn("Refused a NEO write: '{}' is {} by its selector for this record", key,
        offered.isPresent() ? "not offered" : "not verifiable");
    return new RefusedReference(key, property.getTargetEntity().getName(), id,
        offered.isPresent());
  }

  /**
   * The selector the app shows for the field: the entity's own SELECTOR customization when it
   * serves the field (ETP-6377), otherwise the generic selector.
   */
  private static Optional<Boolean> offeredByTheAppSelector(SFEntity sfEntity, Column column,
      String key, String id, Map<String, String> context) {
    switch (NeoCustomizedSelectorCheck.offers(sfEntity, key, id, context)) {
      case OFFERED:
        return Optional.of(Boolean.TRUE);
      case NOT_OFFERED:
        return Optional.of(Boolean.FALSE);
      case UNVERIFIABLE:
        return Optional.empty();
      default:
        return NeoSelectorService.offersValue(sfEntity, column, key, id, context);
    }
  }

  /**
   * A reference is validated when the request changes its own value, or — on an update — when its
   * selector reads a field the request changed (so a new business partner re-checks the stored
   * address). A selector whose dependencies cannot be read is validated whenever anything changed.
   */
  private static boolean mustValidate(Column column, String key, boolean sent,
      Set<String> changed, BaseOBObject stored) {
    if (stored == null) {
      return sent;
    }
    if (sent && changed.contains(key.toLowerCase(Locale.ROOT))) {
      return true;
    }
    if (changed.isEmpty()) {
      return false;
    }
    Optional<Set<String>> dependencies = dependenciesOf(column);
    return dependencies.isEmpty() || dependencies.get().stream().anyMatch(changed::contains);
  }

  /**
   * The context names (lower-cased) the selector of the column reads through {@code @Name@}
   * variables in its validation rule and selector query; empty when it cannot be read.
   */
  private static Optional<Set<String>> dependenciesOf(Column column) {
    try {
      StringBuilder text = new StringBuilder();
      if (column.getValidation() != null && column.getValidation().getValidationCode() != null) {
        text.append(column.getValidation().getValidationCode());
      }
      if (NeoSelectorService.hasObuiselSelector(column)) {
        SelectorMeta meta = NeoSelectorService.resolveTarget(column,
            NeoSelectorService.getBaseReferenceId(column));
        if (meta == null) {
          return Optional.empty();
        }
        text.append(' ').append(StringUtils.defaultString(meta.whereClause)).append(' ')
            .append(StringUtils.defaultString(meta.customHql));
      }
      Set<String> names = new HashSet<>();
      Matcher matcher = CONTEXT_VARIABLE.matcher(text);
      while (matcher.find()) {
        names.add(matcher.group(1).toLowerCase(Locale.ROOT));
      }
      return Optional.of(names);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** Lower-cased DAL and column names of every property whose value the request changes. */
  private static Set<String> changedKeys(Entity written, BaseOBObject stored,
      JSONObject requestFields) {
    Set<String> changed = new HashSet<>();
    Iterator<?> keys = requestFields.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      Property property = written.getProperty(key, false);
      if (property == null) {
        continue;
      }
      Object value = requestFields.opt(key);
      Object raw = value instanceof JSONObject ? ((JSONObject) value).opt(ID) : value;
      String newText = raw == null || JSONObject.NULL.equals(raw) ? null : String.valueOf(raw);
      if (!Objects.equals(textOf(stored == null ? null : stored.get(key)), newText)) {
        changed.add(key.toLowerCase(Locale.ROOT));
        if (property.getColumnName() != null) {
          changed.add(property.getColumnName().toLowerCase(Locale.ROOT));
        }
      }
    }
    return changed;
  }

  private static String textOf(Object old) {
    if (old == null) {
      return null;
    }
    return old instanceof BaseOBObject ? String.valueOf(((BaseOBObject) old).getId())
        : String.valueOf(old);
  }

  private static String storedId(BaseOBObject stored, String key) {
    Object value = stored == null ? null : stored.get(key);
    return value instanceof BaseOBObject ? String.valueOf(((BaseOBObject) value).getId()) : null;
  }

  /** The id a request key sets when it is a persisted reference, else {@code null}. */
  private static String referenceId(Entity entity, String key, Object value) {
    Property property = entity.getProperty(key, false);
    if (property == null || property.isId() || property.isPrimitive() || property.isOneToMany()
        || property.isInactive() || property.isProxy() || property.getTargetEntity() == null) {
      return null;
    }
    Object raw = value instanceof JSONObject ? ((JSONObject) value).opt(ID) : value;
    if (!(raw instanceof String || raw instanceof Number)) {
      return null;
    }
    String id = String.valueOf(raw);
    return NeoReferenceActiveness.isNotAReference(id) ? null : id;
  }

  private static Column columnOf(Tab adTab, Property property) {
    if (property == null || property.getColumnName() == null) {
      return null;
    }
    for (Column column : adTab.getTable().getADColumnList()) {
      if (Boolean.TRUE.equals(column.isActive())
          && property.getColumnName().equalsIgnoreCase(column.getDBColumnName())) {
        return column;
      }
    }
    return null;
  }

  private static BaseOBObject load(Entity entity, String id) {
    try {
      return OBDal.getInstance().get(entity.getName(), id);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Parent record, overlaid by the stored record, overlaid by the incoming values, then the tab's
   * literal auxiliary inputs and the window direction for what the record does not say.
   *
   * <p>The parent is the explicit {@code parentId}, else the one the request sets in the link-to-
   * parent column (a line created with {@code shipmentReceipt} and no {@code parentId}), else the
   * stored record's. Without it a line selector that reads a header field — a storage bin reading
   * the receipt's warehouse — offers nothing.
   */
  private static Map<String, String> mergedContext(Tab adTab, Entity written,
      BaseOBObject stored, String parentId, JSONObject requestFields) {
    Map<String, String> context = new HashMap<>();
    Entity parentEntity = parentEntity(adTab);
    Property parentLink = parentLink(adTab, written);
    String effectiveParent = effectiveParent(written, parentLink, parentId, stored,
        requestFields);
    if (parentEntity != null && StringUtils.isNotBlank(effectiveParent)) {
      BaseOBObject parent = load(parentEntity, effectiveParent);
      if (parent != null) {
        publish(context, parent.getEntity(), flat(parent));
      }
      if (parentLink != null) {
        publishValue(context, parentLink, effectiveParent);
      }
    }
    if (stored != null) {
      publish(context, written, flat(stored));
    }
    publish(context, written, requestFields);
    addAuxiliaryLiterals(adTab, context);
    addWindowDirection(adTab, context);
    return context;
  }

  private static String effectiveParent(Entity written, Property parentLink, String parentId,
      BaseOBObject stored, JSONObject requestFields) {
    if (StringUtils.isNotBlank(parentId) || parentLink == null) {
      return parentId;
    }
    String linkName = parentLink.getName();
    if (requestFields.has(linkName)) {
      String sent = referenceId(written, linkName, requestFields.opt(linkName));
      if (sent != null) {
        return sent;
      }
    }
    return storedId(stored, linkName);
  }

  /**
   * The tab's literal auxiliary inputs (Sales Quotation's {@code isQuotation=Y}, a return window's
   * {@code isReturnMaterial=Y}), as Classic puts them in the form context. A value the record
   * already carries under any casing of the name wins: the record, not the window, says.
   */
  private static void addAuxiliaryLiterals(Tab adTab, Map<String, String> context) {
    Set<String> present = new HashSet<>();
    for (String key : context.keySet()) {
      present.add(key.toLowerCase(Locale.ROOT));
    }
    for (Map.Entry<String, String> literal : NeoAuxiliaryInputResolver.literalValues(adTab)
        .entrySet()) {
      String name = literal.getKey();
      if (present.add(name.toLowerCase(Locale.ROOT))) {
        context.put(name, literal.getValue());
        context.put(name.toUpperCase(Locale.ROOT), literal.getValue());
        context.put(name.toLowerCase(Locale.ROOT), literal.getValue());
      }
    }
  }

  private static Entity parentEntity(Tab adTab) {
    Tab parentTab = adTab.getTabLevel() == null || adTab.getTabLevel() == 0 ? null
        : KernelUtils.getInstance().getParentTab(adTab);
    return parentTab == null || parentTab.getTable() == null ? null
        : ModelProvider.getInstance().getEntityByTableId(parentTab.getTable().getId());
  }

  private static Property parentLink(Tab adTab, Entity written) {
    for (Column column : adTab.getTable().getADColumnList()) {
      if (Boolean.TRUE.equals(column.isActive())
          && Boolean.TRUE.equals(column.isLinkToParentColumn())) {
        return written.getPropertyByColumnName(column.getDBColumnName());
      }
    }
    return null;
  }

  private static JSONObject flat(BaseOBObject entityRecord) {
    try {
      return OBProvider.getInstance().get(DataToJsonConverter.class)
          .toJsonObject(entityRecord, DataResolvingMode.FULL);
    } catch (RuntimeException e) {
      return new JSONObject();
    }
  }

  private static void publish(Map<String, String> context, Entity entity, JSONObject values) {
    Iterator<?> keys = values.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      Property property = entity.getProperty(key, false);
      Object value = values.opt(key);
      if (value instanceof JSONObject) {
        value = ((JSONObject) value).opt(ID);
      }
      if (property != null && value != null && !JSONObject.NULL.equals(value)
          && (value instanceof String || value instanceof Number || value instanceof Boolean)) {
        publishValue(context, property, String.valueOf(value));
      }
    }
  }

  private static void publishValue(Map<String, String> context, Property property, String value) {
    String text = value;
    if (property.isBoolean()) {
      text = Boolean.parseBoolean(value) || YES.equals(value) ? YES : NO;
    }
    context.put(property.getName(), text);
    if (property.getColumnName() != null) {
      context.put(property.getColumnName(), classicDate(property, text));
    }
  }

  private static String classicDate(Property property, String value) {
    if (!property.isDate() && !property.isDatetime() || value.length() < 10) {
      return value;
    }
    try {
      return LocalDate.parse(value.substring(0, 10)).format(CLASSIC_DATE);
    } catch (DateTimeParseException e) {
      return value;
    }
  }

  /** The window's sales flag is the document direction when the record does not say. */
  private static void addWindowDirection(Tab adTab, Map<String, String> context) {
    if (context.containsKey("IsSOTrx") || adTab.getWindow() == null
        || adTab.getWindow().isSalesTransaction() == null) {
      return;
    }
    String flag = adTab.getWindow().isSalesTransaction() ? YES : NO;
    context.put("IsSOTrx", flag);
    context.put("isSOTrx", flag);
    context.putIfAbsent("IsReceipt", flag);
    context.putIfAbsent("Isreceipt", flag);
    context.putIfAbsent(YES.equals(flag) ? "isCustomer" : "isVendor", YES);
  }

  /** The field (DAL property name) refused, its target entity, the refused value and the cause. */
  public static final class RefusedReference {
    private final String fieldName;
    private final String targetEntityName;
    private final String value;
    private final boolean verified;

    RefusedReference(String fieldName, String targetEntityName, String value, boolean verified) {
      this.fieldName = fieldName;
      this.targetEntityName = targetEntityName;
      this.value = value;
      this.verified = verified;
    }

    /** @return the DAL property name refused */
    public String fieldName() {
      return fieldName;
    }

    /** @return the DAL entity name the field references */
    public String targetEntityName() {
      return targetEntityName;
    }

    /** @return the refused candidate id, or {@code null} when the check itself failed */
    public String value() {
      return value;
    }

    /** @return {@code true} when the selector answered and did not offer the value */
    public boolean isVerified() {
      return verified;
    }

    /** @return the human detail shared by the REST and MCP 422 bodies */
    public String detail() {
      if (verified) {
        return "Field '" + fieldName + "' was set to '" + value + "', a record of '"
            + targetEntityName + "' that the app does not offer for this record; the write was"
            + " rejected exactly as the app would not have allowed the selection.";
      }
      String shown = value == null ? "" : " (value '" + value + "')";
      return "Field '" + fieldName + "'" + shown
          + " could not be verified against the app's selector; the write was rejected.";
    }
  }
}
