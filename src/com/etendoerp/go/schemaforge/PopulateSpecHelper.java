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
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.module.Module;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessParameter;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;
import com.etendoerp.go.schemaforge.data.SFSpec;

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

  private static void setAuditFields(SFEntity entity) {
    User currentUser = OBContext.getOBContext().getUser();
    Date now = new Date();
    entity.setCreatedBy(currentUser);
    entity.setUpdatedBy(currentUser);
    entity.setCreationDate(now);
    entity.setUpdated(now);
  }

  private static void setAuditFields(SFField field) {
    User currentUser = OBContext.getOBContext().getUser();
    Date now = new Date();
    field.setCreatedBy(currentUser);
    field.setUpdatedBy(currentUser);
    field.setCreationDate(now);
    field.setUpdated(now);
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
  public static int[] populate(String specId, boolean excludeSystemColumns,
      boolean includeAllMethods) {

    SFSpec spec = OBDal.getInstance().get(SFSpec.class, specId);
    if (spec == null) {
      throw new IllegalArgumentException("Spec not found: " + specId);
    }

    if (SPEC_TYPE_PROCESS.equals(spec.getSpecType())) {
      return populateProcess(spec, specId, excludeSystemColumns, includeAllMethods);
    }
    return populateWindow(spec, specId, excludeSystemColumns, includeAllMethods);
  }

  /**
   * Populate entities and fields for a Window-type spec.
   * Iterates AD_Tab -> AD_Column for the linked AD_Window.
   */
  private static int[] populateWindow(SFSpec spec, String specId,
      boolean excludeSystemColumns, boolean includeAllMethods) {

    Window window = spec.getADWindow();
    if (window == null) {
      throw new IllegalArgumentException("Window spec has no linked AD_Window");
    }

    Module specModule = spec.getADModule();
    Client specClient = spec.getClient();
    Organization specOrg = spec.getOrganization();

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
      SFEntity entity = OBProvider.getInstance().get(SFEntity.class);
      entity.setName(tab.getName());
      entity.setETGOSFSpec(spec);
      entity.setADTab(tab);
      entity.setADModule(specModule);
      entity.setClient(specClient);
      entity.setOrganization(specOrg);
      entity.setActive(true);
      entity.setIncluded(true);
      entity.setGet(includeAllMethods);
      entity.setGetByID(includeAllMethods);
      entity.setPost(includeAllMethods);
      entity.setPut(includeAllMethods);
      entity.setPatch(includeAllMethods);
      entity.setDelete(includeAllMethods);
      entity.setSeqNo(tab.getSequenceNumber());
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

        SFField field = OBProvider.getInstance().get(SFField.class);
        field.setETGOSFEntity(entity);
        field.setADColumn(col);
        field.setADModule(specModule);
        field.setClient(specClient);
        field.setOrganization(specOrg);
        field.setActive(true);
        field.setIncluded(true);
        field.setReadOnly(false);
        field.setSeqNo(seqNo);
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
  private static int[] populateProcess(SFSpec spec, String specId,
      boolean excludeSystemColumns, boolean includeAllMethods) {

    Process process = spec.getProcess();
    if (process == null) {
      throw new IllegalArgumentException("Process spec has no linked AD_Process");
    }

    Module specModule = spec.getADModule();
    Client specClient = spec.getClient();
    Organization specOrg = spec.getOrganization();

    deleteExistingChildren(specId);

    // Create a single entity for the process (no tab — process specs have no tabs)
    SFEntity entity = OBProvider.getInstance().get(SFEntity.class);
    entity.setName(process.getName());
    entity.setETGOSFSpec(spec);
    // tab is null for process specs
    entity.setADModule(specModule);
    entity.setClient(specClient);
    entity.setOrganization(specOrg);
    entity.setActive(true);
    entity.setIncluded(true);
    // Processes are POST-only
    entity.setGet(false);
    entity.setGetByID(false);
    entity.setPost(true);
    entity.setPut(false);
    entity.setPatch(false);
    entity.setDelete(false);
    entity.setSeqNo(10L);
    setAuditFields(entity);
    OBDal.getInstance().save(entity);

    // Create fields from process parameters
    List<ProcessParameter> params = process.getADProcessParameterList();
    int fieldCount = 0;

    for (ProcessParameter param : params) {
      if (!param.isActive()) {
        continue;
      }

      SFField field = OBProvider.getInstance().get(SFField.class);
      field.setETGOSFEntity(entity);
      // column is null for process specs — no AD_Column exists for process parameters
      field.setADModule(specModule);
      field.setClient(specClient);
      field.setOrganization(specOrg);
      field.setActive(true);
      field.setIncluded(true);
      field.setReadOnly(false);
      field.setSeqNo(param.getSequenceNumber());
      // Store the parameter name in javaQualifier since there is no NAME column on ETGO_SF_Field
      field.setJavaQualifier(param.getDBColumnName());
      // Store the parameter's default value if present
      if (param.getDefaultValue() != null) {
        field.setDefaultValue(param.getDefaultValue());
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
  private static void deleteExistingChildren(String specId) {
    // Find existing entities for this spec
    OBCriteria<SFEntity> entityCriteria = OBDal.getInstance()
        .createCriteria(SFEntity.class);
    entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    List<SFEntity> existingEntities = entityCriteria.list();

    for (SFEntity entity : existingEntities) {
      // Delete child fields first
      OBCriteria<SFField> fieldCriteria = OBDal.getInstance()
          .createCriteria(SFField.class);
      fieldCriteria.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", entity.getId()));
      List<SFField> existingFields = fieldCriteria.list();
      for (SFField field : existingFields) {
        OBDal.getInstance().remove(field);
      }
      OBDal.getInstance().remove(entity);
    }
    OBDal.getInstance().flush();
  }
}
