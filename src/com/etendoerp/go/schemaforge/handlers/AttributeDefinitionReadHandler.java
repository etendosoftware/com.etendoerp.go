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

package com.etendoerp.go.attributes;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.plm.ProductCategory;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoExtension;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Lets the client learn at runtime which attributes apply to a product category (ETP-5977, H6):
 * {@code GET /sws/neo/attribute-definition/attributeDefinition?attrCategory=<categoryId>&_sortBy=sequenceNumber}
 * returns only the active definitions scoped to that category, with their code, label, type, mandatory flag
 * and order. The category id is validated (an existing category of a readable client) before it goes into
 * the server-built read predicate; nothing from the request is spliced otherwise.
 */
@NeoExtension(spec = "attribute-definition", entity = "attributeDefinition")
public class AttributeDefinitionReadHandler implements NeoHandler {

  static final String PARAM = "attrCategory";

  @Override
  public NeoResponse handle(NeoContext context) {
    return null;
  }

  @Override
  public List<String> readPredicates(NeoContext context) {
    String category = context.getQueryParams() == null ? null : context.getQueryParams().get(PARAM);
    if (StringUtils.isBlank(category)) {
      return List.of();
    }
    if (!category.matches("[0-9A-Fa-f]{32}")) {
      throw new IllegalArgumentException(PARAM + " is not a valid category id");
    }
    ProductCategory pc = OBDal.getInstance().get(ProductCategory.class, category);
    if (pc == null || !java.util.Arrays.asList(OBContext.getOBContext().getReadableClients())
        .contains(pc.getClient().getId())) {
      throw new IllegalArgumentException(PARAM + " does not name a product category of this client");
    }
    return List.of("e.active = true and exists (select 1 from ETGO_AttrDefCategory c where c.attributeDefinition = e"
        + " and c.productCategory.id = '" + pc.getId() + "' and c.active = true)");
  }
}
