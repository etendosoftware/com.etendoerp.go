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

package com.etendoerp.go.schemaforge.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

/**
 * Generic {@code *_Trl} name resolution for the NEO selector layer (ETP-4304).
 *
 * <p>Etendo entity identifiers ({@code BaseOBObject.getIdentifier()}) are built from the base-row
 * columns and are <em>not</em> translated — the translated text lives in a sibling {@code *_Trl}
 * table (e.g. {@code C_UOM_Trl}, {@code C_Country_Trl}, {@code AD_Ref_List_Trl}). So a selector
 * value shown via the identifier is always in the base language regardless of the request locale.
 *
 * <p>This helper resolves the translated {@code name} for a set of records in a given language,
 * discovering the translation entity from the runtime model by convention instead of hardcoding a
 * table per entity:
 * <ul>
 *   <li>the translation entity is {@code <BaseEntity>Trl} (e.g. {@code UOM} → {@code UOMTrl});</li>
 *   <li>its back-reference is the single FK property that targets the base entity
 *       ({@code UOMTrl.uOM}, {@code CountryTrl.country}, {@code ADListTrl.listReference});</li>
 *   <li>the translated text is the base entity's single identifier property when the trl exposes
 *       it, otherwise the conventional {@code name} column.</li>
 * </ul>
 *
 * <p>The lookup runs in admin mode (trl entities are not directly readable under a restricted NEO
 * role) and never throws — callers get an empty map and fall back to the base-language label. The
 * language to resolve into is the GO locale already applied to the request by {@code
 * NeoAuthenticator}, available via {@link NeoLanguage#currentCode()}.
 */
public final class NeoTrl {

  private static final Logger log = LogManager.getLogger(NeoTrl.class);
  private static final String NAME_PROPERTY = "name";

  private NeoTrl() {
  }

  /**
   * Resolve the translated {@code name} of {@code baseEntityName} records into {@code langCode}.
   *
   * @param baseEntityName the model name of the base entity (e.g. {@code UOM}, {@code Country})
   * @param ids            the base-record ids whose translations are wanted
   * @param langCode       the target Etendo language code (e.g. {@code es_ES})
   * @return a map {@code recordId -> translated name}; empty when nothing is translatable
   */
  public static Map<String, String> translatedNames(String baseEntityName, Collection<String> ids,
      String langCode) {
    if (StringUtils.isBlank(baseEntityName) || ids == null || ids.isEmpty()
        || StringUtils.isBlank(langCode)) {
      return Collections.emptyMap();
    }
    boolean adminMode = false;
    try {
      OBContext.setAdminMode(true);
      adminMode = true;

      ModelProvider model = ModelProvider.getInstance();
      Entity base = model.getEntity(baseEntityName, false);
      Entity trl = base != null ? model.getEntity(baseEntityName + "Trl", false) : null;
      if (base == null || trl == null) {
        return Collections.emptyMap();
      }
      Property backRef = findBackReference(trl, base);
      String nameProperty = resolveNameProperty(base, trl);
      if (backRef == null || nameProperty == null) {
        return Collections.emptyMap();
      }

      OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(trl.getName(),
          "as t where t." + backRef.getName() + ".id in :ids and t.language.language = :lang");
      query.setNamedParameter("ids", new ArrayList<>(ids));
      query.setNamedParameter("lang", langCode);

      Map<String, String> translations = new HashMap<>();
      for (BaseOBObject row : query.list()) {
        Object ref = row.get(backRef.getName());
        Object name = row.get(nameProperty);
        if (ref instanceof BaseOBObject && name != null && StringUtils.isNotBlank(name.toString())) {
          translations.put(((BaseOBObject) ref).getId().toString(), name.toString());
        }
      }
      return translations;
    } catch (Exception e) {
      log.debug("Trl name lookup failed for entity '{}' / language '{}': {}",
          baseEntityName, langCode, e.getMessage());
      return Collections.emptyMap();
    } finally {
      if (adminMode) {
        OBContext.restorePreviousMode();
      }
    }
  }

  /**
   * Model metadata needed to filter a base entity by its translated {@code *_Trl} name in HQL:
   * the trl entity model name, the back-reference property to the base row, and the trl property
   * holding the translated text.
   */
  public static final class TrlSearchMeta {
    /** The {@code *_Trl} entity model name (e.g. {@code UOMTrl}). */
    public final String trlEntityName;
    /** The trl property that references the base row (e.g. {@code uOM}). */
    public final String backRefProperty;
    /** The trl property holding the translated text (e.g. {@code name}). */
    public final String nameProperty;

    /**
     * Creates a descriptor of a base entity's {@code *_Trl} translation columns.
     *
     * @param trlEntityName   the {@code *_Trl} entity model name
     * @param backRefProperty the trl property referencing the base row
     * @param nameProperty    the trl property holding the translated text
     */
    public TrlSearchMeta(String trlEntityName, String backRefProperty, String nameProperty) {
      this.trlEntityName = trlEntityName;
      this.backRefProperty = backRefProperty;
      this.nameProperty = nameProperty;
    }
  }

  /**
   * Resolve the {@code *_Trl} metadata needed to filter {@code baseEntityName} by its translated
   * name, or {@code null} when the entity has no usable translation sibling. Pure model lookup (no
   * DB access) and never throws. Discovery follows the same conventions as {@link #translatedNames}.
   *
   * @param baseEntityName the model name of the base entity (e.g. {@code UOM}, {@code Country})
   * @return the trl search metadata, or {@code null} when the entity is not translatable
   */
  public static TrlSearchMeta resolveSearchMeta(String baseEntityName) {
    if (StringUtils.isBlank(baseEntityName)) {
      return null;
    }
    try {
      ModelProvider model = ModelProvider.getInstance();
      Entity base = model.getEntity(baseEntityName, false);
      Entity trl = base != null ? model.getEntity(baseEntityName + "Trl", false) : null;
      if (base == null || trl == null) {
        return null;
      }
      Property backRef = findBackReference(trl, base);
      String nameProperty = resolveNameProperty(base, trl);
      if (backRef == null || nameProperty == null) {
        return null;
      }
      return new TrlSearchMeta(trl.getName(), backRef.getName(), nameProperty);
    } catch (Exception e) {
      log.debug("Trl search-meta resolution failed for entity '{}': {}", baseEntityName, e.getMessage());
      return null;
    }
  }

  /** The single FK property on the trl entity that points back to the base entity, or null. */
  private static Property findBackReference(Entity trl, Entity base) {
    for (Property property : trl.getProperties()) {
      Entity target = property.getTargetEntity();
      if (target != null && target.getName().equals(base.getName())) {
        return property;
      }
    }
    return null;
  }

  /**
   * The trl property holding the translated identifier: the base entity's single identifier
   * property when the trl exposes it, otherwise the conventional {@code name} column, else null.
   */
  private static String resolveNameProperty(Entity base, Entity trl) {
    List<Property> identifiers = base.getIdentifierProperties();
    if (identifiers != null && identifiers.size() == 1) {
      String identifier = identifiers.get(0).getName();
      if (hasProperty(trl, identifier)) {
        return identifier;
      }
    }
    return hasProperty(trl, NAME_PROPERTY) ? NAME_PROPERTY : null;
  }

  private static boolean hasProperty(Entity entity, String propertyName) {
    for (Property property : entity.getProperties()) {
      if (property.getName().equals(propertyName)) {
        return true;
      }
    }
    return false;
  }
}
