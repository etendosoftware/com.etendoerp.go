package com.etendoerp.go.schemaforge;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.structure.Column;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Shared logic for populating ETGO_SF_Entity and ETGO_SF_Field records
 * from an ETGO_SF_Spec. Used by both PopulateSpecProcess and the
 * SFPopulateSpec webhook handler.
 */
public class PopulateSpecHelper {

  private static final Logger log = LogManager.getLogger(PopulateSpecHelper.class);

  private static final Set<String> SYSTEM_COLUMNS = new HashSet<>(Arrays.asList(
      "AD_CLIENT_ID", "AD_ORG_ID", "ISACTIVE",
      "CREATED", "CREATEDBY", "UPDATED", "UPDATEDBY"
  ));

  private PopulateSpecHelper() {
    // utility class
  }

  /**
   * Populate entities and fields for the given spec.
   *
   * @param specId                the ETGO_SF_Spec_ID
   * @param excludeSystemColumns  whether to skip audit/system columns
   * @return int array: [entityCount, fieldCount]
   */
  public static int[] populate(String specId, boolean excludeSystemColumns) {
    return populate(specId, excludeSystemColumns, false);
  }

  /**
   * Populate entities and fields for the given spec.
   *
   * @param specId                the ETGO_SF_Spec_ID
   * @param excludeSystemColumns  whether to skip audit/system columns
   * @param includeAllMethods     whether to set all HTTP method flags to Y
   * @return int array: [entityCount, fieldCount]
   */
  @SuppressWarnings("unchecked")
  public static int[] populate(String specId, boolean excludeSystemColumns,
      boolean includeAllMethods) {

    BaseOBObject spec = OBDal.getInstance().get("ETGO_SF_Spec", specId);
    if (spec == null) {
      throw new IllegalArgumentException("Spec not found: " + specId);
    }

    Window window = (Window) spec.get("window");
    if (window == null) {
      throw new IllegalArgumentException("Spec has no linked AD_Window");
    }

    BaseOBObject specModule = (BaseOBObject) spec.get("module");
    Client specClient = (Client) spec.get("client");
    Organization specOrg = (Organization) spec.get("organization");

    // Delete existing entities (and their fields via cascade or manual delete)
    deleteExistingChildren(specId);

    // Find all tabs in the window, ordered by SeqNo
    OBCriteria<Tab> tabCriteria = OBDal.getInstance().createCriteria(Tab.class);
    tabCriteria.add(Restrictions.eq(Tab.PROPERTY_WINDOW + ".id", window.getId()));
    tabCriteria.add(Restrictions.eq(Tab.PROPERTY_ACTIVE, true));
    tabCriteria.addOrderBy(Tab.PROPERTY_SEQUENCENUMBER, true);
    List<Tab> tabs = tabCriteria.list();

    int entityCount = 0;
    int fieldCount = 0;

    for (Tab tab : tabs) {
      // Create entity record
      BaseOBObject entity = (BaseOBObject) OBProvider.getInstance().get("ETGO_SF_Entity");
      entity.set("name", tab.getName());
      entity.set("etgoSfSpec", spec);
      entity.set("tab", tab);
      entity.set("module", specModule);
      entity.set("client", specClient);
      entity.set("organization", specOrg);
      entity.set("active", true);
      entity.set("included", true);
      entity.set("get", includeAllMethods);
      entity.set("getbyid", includeAllMethods);
      entity.set("post", includeAllMethods);
      entity.set("put", includeAllMethods);
      entity.set("patch", includeAllMethods);
      entity.set("delete", includeAllMethods);
      entity.set("sequenceNumber", tab.getSequenceNumber());
      OBDal.getInstance().save(entity);
      entityCount++;

      // Find columns for this tab's table
      OBCriteria<Column> colCriteria = OBDal.getInstance().createCriteria(Column.class);
      colCriteria.add(Restrictions.eq(Column.PROPERTY_TABLE + ".id", tab.getTable().getId()));
      colCriteria.add(Restrictions.eq(Column.PROPERTY_ACTIVE, true));
      colCriteria.addOrderBy(Column.PROPERTY_POSITION, true);
      List<Column> columns = colCriteria.list();

      long seqNo = 10;
      for (Column col : columns) {
        // Skip system columns if requested
        if (excludeSystemColumns
            && SYSTEM_COLUMNS.contains(col.getDBColumnName().toUpperCase())) {
          continue;
        }

        BaseOBObject field = (BaseOBObject) OBProvider.getInstance().get("ETGO_SF_Field");
        field.set("etgoSfEntity", entity);
        field.set("column", col);
        field.set("module", specModule);
        field.set("client", specClient);
        field.set("organization", specOrg);
        field.set("active", true);
        field.set("included", true);
        field.set("readOnly", false);
        field.set("sequenceNumber", seqNo);
        OBDal.getInstance().save(field);
        fieldCount++;
        seqNo += 10;
      }

      // Flush periodically to avoid large in-memory batch
      if (entityCount % 10 == 0) {
        OBDal.getInstance().flush();
      }
    }

    OBDal.getInstance().flush();
    log.info("Populated spec {}: {} entities, {} fields", specId, entityCount, fieldCount);
    return new int[] { entityCount, fieldCount };
  }

  /**
   * Delete all ETGO_SF_Entity records (and their child ETGO_SF_Field records)
   * for the given spec.
   */
  @SuppressWarnings("unchecked")
  private static void deleteExistingChildren(String specId) {
    // Find existing entities for this spec
    OBCriteria<BaseOBObject> entityCriteria = OBDal.getInstance()
        .createCriteria("ETGO_SF_Entity");
    entityCriteria.add(Restrictions.eq("etgoSfSpec.id", specId));
    List<BaseOBObject> existingEntities = entityCriteria.list();

    for (BaseOBObject entity : existingEntities) {
      // Delete child fields first
      OBCriteria<BaseOBObject> fieldCriteria = OBDal.getInstance()
          .createCriteria("ETGO_SF_Field");
      fieldCriteria.add(Restrictions.eq("etgoSfEntity.id", entity.getId()));
      List<BaseOBObject> existingFields = fieldCriteria.list();
      for (BaseOBObject field : existingFields) {
        OBDal.getInstance().remove(field);
      }
      OBDal.getInstance().remove(entity);
    }
    OBDal.getInstance().flush();
  }
}
