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

import javax.inject.Named;

/**
 * NeoHandler for the {@code accounting} entity in the Product window.
 *
 * <p>On POST (create), auto-fills {@code accountingSchema} with the default
 * accounting schema for the current client when the field is absent from the
 * request body. This handles the first-line scenario where no existing sibling
 * rows are available to copy the value from.
 *
 * <p>All other endpoints pass through to the default service unchanged.
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'productAccountingHandler'} on
 * the ETGO_SF_ENTITY record for the product accounting tab.
 */
@Named("productAccountingHandler")
public class ProductAccountingHandler extends AbstractAccountingSchemaAutoFillHandler {

  @Override
  protected String describeContext() {
    return "product accounting line";
  }
}
