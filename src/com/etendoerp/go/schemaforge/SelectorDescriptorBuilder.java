package com.etendoerp.go.schemaforge;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.ad.datamodel.Column;

/**
 * Builds selector discovery descriptors returned by the NEO metadata endpoints.
 */
class SelectorDescriptorBuilder {

  private static final String KEY_COLUMN_NAME = "columnName";
  private static final String KEY_REFERENCE_TYPE = "referenceType";
  private static final String KEY_TYPE = "type";
  private static final String KEY_TARGET_ENTITY = "targetEntity";
  private static final String KEY_DISPLAY_PROPERTY = "displayProperty";
  private static final String KEY_AUX_FIELDS = "auxFields";
  private static final String KEY_SELECTOR_PARAMS = "selectorParams";
  private static final Pattern PARAM_EXTRACT = Pattern.compile("@(#?)(\\w+)@");

  private SelectorDescriptorBuilder() {
  }

  static JSONObject buildListSelectorItem(Column column) throws Exception {
    JSONObject item = new JSONObject();
    item.put(KEY_COLUMN_NAME, column.getDBColumnName());
    item.put(KEY_REFERENCE_TYPE, "List");
    item.put(KEY_TYPE, "list");
    return item;
  }

  static JSONObject buildSelectorItem(Column column, String refId,
      SelectorMeta meta, Set<String> sessionParams) throws Exception {
    JSONObject item = new JSONObject();
    item.put(KEY_COLUMN_NAME, column.getDBColumnName());
    if (meta.isRich) {
      item.put(KEY_REFERENCE_TYPE, "OBUISEL");
      item.put(KEY_TYPE, "rich");
    } else {
      item.put(KEY_REFERENCE_TYPE, resolveReferenceType(refId));
      item.put(KEY_TYPE, "simple");
    }
    item.put(KEY_TARGET_ENTITY, meta.entityName);
    item.put(KEY_DISPLAY_PROPERTY, meta.displayProperty);

    if (meta.auxFields != null && !meta.auxFields.isEmpty()) {
      item.put(KEY_AUX_FIELDS, buildAuxFieldsArray(meta.auxFields));
    }

    JSONArray params = extractSelectorParams(column, sessionParams);
    if (params.length() > 0) {
      item.put(KEY_SELECTOR_PARAMS, params);
    }
    return item;
  }

  private static String resolveReferenceType(String refId) {
    if (NeoSelectorService.REF_TABLE.equals(refId)) {
      return "Table";
    }
    if (NeoSelectorService.REF_TABLEDIR.equals(refId)) {
      return "TableDir";
    }
    return "Search";
  }

  private static JSONArray buildAuxFieldsArray(List<AuxFieldMeta> auxFields) throws Exception {
    JSONArray auxArray = new JSONArray();
    for (AuxFieldMeta auxField : auxFields) {
      JSONObject auxItem = new JSONObject();
      auxItem.put("suffix", auxField.suffix);
      auxItem.put("name", auxField.name);
      auxArray.put(auxItem);
    }
    return auxArray;
  }

  private static JSONArray extractSelectorParams(Column column,
      Set<String> sessionParams) {
    JSONArray params = new JSONArray();
    org.openbravo.model.ad.domain.Validation valRule = column.getValidation();
    if (valRule == null || org.apache.commons.lang3.StringUtils.isBlank(valRule.getValidationCode())) {
      return params;
    }
    Matcher matcher = PARAM_EXTRACT.matcher(valRule.getValidationCode());
    Set<String> seen = new HashSet<>();
    while (matcher.find()) {
      boolean isSession = "#".equals(matcher.group(1));
      String param = matcher.group(2);
      if (!isSession && !sessionParams.contains(param) && seen.add(param)) {
        params.put(param);
      }
    }
    return params;
  }
}
