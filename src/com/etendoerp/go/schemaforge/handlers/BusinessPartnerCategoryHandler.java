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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoExtension;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Contact category ({@code C_BP_Group}, window "Categoría de Contacto") customization (ETP-5791).
 *
 * <p>A category defines whether its contacts are customers, vendors/creditors or both
 * ({@code EM_Etgo_Iscustomer} / {@code EM_Etgo_Isvendor}). On create and update this pre-hook
 * rejects a write that would leave the category with neither type, with the
 * {@code ETGO_BPGroupContactTypeRequired} message (and its key in {@code messageKeys}). Both
 * columns default to {@code 'N'}, so a create must check at least one of them explicitly.</p>
 *
 * <p>What the flags drive — inheritance of the type by a new contact — lives in the contact's own
 * customization, see {@link ContactTypeRules}.</p>
 */
@NeoExtension(spec = "business-partner-category", entity = "businessPartnerCategory")
public class BusinessPartnerCategoryHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(BusinessPartnerCategoryHandler.class);

  @Override
  public NeoResponse handle(NeoContext ctx) {
    if (!ContactTypeRules.isCrudWrite(ctx)) {
      return null;
    }
    try {
      return ContactTypeRules.validateCategoryWrite(ctx, ctx.getRequestBody());
    } catch (Exception e) {
      log.error("BusinessPartnerCategoryHandler: error validating the contact types of a category", e);
      throw new OBException("Error validating the contact category", e);
    }
  }
}
