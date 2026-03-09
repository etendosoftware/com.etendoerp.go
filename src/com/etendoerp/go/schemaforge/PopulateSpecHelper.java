package com.etendoerp.go.schemaforge;

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessParameter;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Shared logic for populating ETGO_SF_Entity and ETGO_SF_Field records
 * from an ETGO_SF_Spec. Used by both PopulateSpecProcess and the
 * SFPopulateSpec webhook handler.
 *
 * Supports two spec types:
 * - "W" (Window): iterates AD_Tab -> AD_Column (original behavior)
 * - "P" (Process): iterates AD_Process_Para
 */
public class PopulateSpecHelper {

  private static final Logger log = LogManager.getLogger(PopulateSpecHelper.class);

  private static final String SPEC_TYPE_PROCESS = "P";

  private static final Set<String> SYSTEM_COLUMNS = new HashSet<>(Arrays.asList(
      "AD_CLIENT_ID", "AD_ORG_ID", "ISACTIVE",
      "CREATED", "CREATEDBY", "UPDATED", "UPDATEDBY"
  ));

  private PopulateSpecHelper() {
    // utility class
  }

  private static void setAuditFields(BaseOBObject obj) {
    User currentUser = OBContext.getOBContext().getUser();
    Date now = new Date();
    obj.set("createdBy", currentUser);
    obj.set("updatedBy", currentUser);
    obj.set("created", now);
    obj.set("updated", now);
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

    String specType = (String) spec.get("specType");

    if (SPEC_TYPE_PROCESS.equals(specType)) {
      return populateProcess(spec, specId, excludeSystemColumns, includeAllMethods);
    }
    return populateWindow(spec, specId, excludeSystemColumns, includeAllMethods);
  }

  /**
   * Populate entities and fields for a Window-type spec.
   * Iterates AD_Tab -> AD_Column for the linked AD_Window.
   */
  @SuppressWarnings("unchecked")
  private static int[] populateWindow(BaseOBObject spec, String specId,
      boolean excludeSystemColumns, boolean includeAllMethods) {

    Window window = (Window) spec.get("aDWindow");
    if (window == null) {
      throw new IllegalArgumentException("Window spec has no linked AD_Window");
    }

    BaseOBObject specModule = (BaseOBObject) spec.get("aDModule");
    Client specClient = (Client) spec.get("client");
    Organization specOrg = (Organization) spec.get("organization");

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
      entity.set("eTGOSFSpec", spec);
      entity.set("aDTab", tab);
      entity.set("aDModule", specModule);
      entity.set("client", specClient);
      entity.set("organization", specOrg);
      entity.set("isActive", true);
      entity.set("isIncluded", true);
      entity.set("get", includeAllMethods);
      entity.set("getByID", includeAllMethods);
      entity.set("post", includeAllMethods);
      entity.set("put", includeAllMethods);
      entity.set("patch", includeAllMethods);
      entity.set("delete", includeAllMethods);
      entity.set("seqNo", tab.getSequenceNumber());
      setAuditFields(entity);
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
        if (excludeSystemColumns
            && SYSTEM_COLUMNS.contains(col.getDBColumnName().toUpperCase())) {
          continue;
        }

        BaseOBObject field = (BaseOBObject) OBProvider.getInstance().get("ETGO_SF_Field");
        field.set("eTGOSFEntity", entity);
        field.set("aDColumn", col);
        field.set("aDModule", specModule);
        field.set("client", specClient);
        field.set("organization", specOrg);
        field.set("isActive", true);
        field.set("isIncluded", true);
        field.set("isReadOnly", false);
        field.set("seqNo", seqNo);
        setAuditFields(field);
        OBDal.getInstance().save(field);
        fieldCount++;
        seqNo += 10;
      }

      if (entityCount % 10 == 0) {
        OBDal.getInstance().flush();
      }
    }

    OBDal.getInstance().flush();
    log.info("Populated window spec {}: {} entities, {} fields", specId, entityCount, fieldCount);
    return new int[] { entityCount, fieldCount };
  }

  /**
   * Populate entities and fields for a Process-type spec.
   * Creates one entity for the process and one field per AD_Process_Para.
   * Process specs are POST-only (no GET/PUT/PATCH/DELETE).
   * Since there is no AD_Column for process parameters, the column FK is left null
   * and the parameter name is stored in the javaQualifier field.
   */
  @SuppressWarnings("unchecked")
  private static int[] populateProcess(BaseOBObject spec, String specId,
      boolean excludeSystemColumns, boolean includeAllMethods) {

    Process process = (Process) spec.get("process");
    if (process == null) {
      throw new IllegalArgumentException("Process spec has no linked AD_Process");
    }

    BaseOBObject specModule = (BaseOBObject) spec.get("aDModule");
    Client specClient = (Client) spec.get("client");
    Organization specOrg = (Organization) spec.get("organization");

    deleteExistingChildren(specId);

    // Create a single entity for the process (no tab — process specs have no tabs)
    BaseOBObject entity = (BaseOBObject) OBProvider.getInstance().get("ETGO_SF_Entity");
    entity.set("name", process.getName());
    entity.set("eTGOSFSpec", spec);
    // tab is null for process specs
    entity.set("aDModule", specModule);
    entity.set("client", specClient);
    entity.set("organization", specOrg);
    entity.set("isActive", true);
    entity.set("isIncluded", true);
    // Processes are POST-only
    entity.set("get", false);
    entity.set("getByID", false);
    entity.set("post", true);
    entity.set("put", false);
    entity.set("patch", false);
    entity.set("delete", false);
    entity.set("seqNo", 10L);
    setAuditFields(entity);
    OBDal.getInstance().save(entity);

    // Create fields from process parameters
    List<ProcessParameter> params = process.getADProcessParameterList();
    int fieldCount = 0;

    for (ProcessParameter param : params) {
      if (!param.isActive()) {
        continue;
      }

      BaseOBObject field = (BaseOBObject) OBProvider.getInstance().get("ETGO_SF_Field");
      field.set("eTGOSFEntity", entity);
      // column is null for process specs — no AD_Column exists for process parameters
      field.set("aDModule", specModule);
      field.set("client", specClient);
      field.set("organization", specOrg);
      field.set("isActive", true);
      field.set("isIncluded", true);
      field.set("isReadOnly", false);
      field.set("seqNo", param.getSequenceNumber());
      // Store the parameter name in javaQualifier since there is no NAME column on ETGO_SF_Field
      field.set("javaQualifier", param.getDBColumnName());
      // Store the parameter's default value if present
      if (param.getDefaultValue() != null) {
        field.set("defaultValue", param.getDefaultValue());
      }
      setAuditFields(field);
      OBDal.getInstance().save(field);
      fieldCount++;
    }

    OBDal.getInstance().flush();
    log.info("Populated process spec {}: 1 entity, {} fields", specId, fieldCount);
    return new int[] { 1, fieldCount };
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
    entityCriteria.add(Restrictions.eq("eTGOSFSpec.id", specId));
    List<BaseOBObject> existingEntities = entityCriteria.list();

    for (BaseOBObject entity : existingEntities) {
      // Delete child fields first
      OBCriteria<BaseOBObject> fieldCriteria = OBDal.getInstance()
          .createCriteria("ETGO_SF_Field");
      fieldCriteria.add(Restrictions.eq("eTGOSFEntity.id", entity.getId()));
      List<BaseOBObject> existingFields = fieldCriteria.list();
      for (BaseOBObject field : existingFields) {
        OBDal.getInstance().remove(field);
      }
      OBDal.getInstance().remove(entity);
    }
    OBDal.getInstance().flush();
  }
}
