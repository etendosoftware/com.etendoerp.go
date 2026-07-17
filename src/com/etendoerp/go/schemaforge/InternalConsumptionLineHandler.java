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

package com.etendoerp.go.schemaforge;

import javax.inject.Named;

/**
 * NeoHandler for the {@code internalConsumptionLine} entity.
 *
 * <p>Historically this handler rewrote the storage bin (M_Locator_ID) selector labels to display
 * the parent warehouse name. That behavior is now generic and applies to every locator FK across
 * all windows, implemented in the shared selector pipeline
 * ({@code NeoSelectorService} → {@code NeoLocatorSelectorHelper}) and CRUD pipeline
 * ({@code NeoCrudHandler} → {@code NeoLocatorIdentifierHelper}).
 *
 * <p>Both hooks are therefore intentional no-ops: everything passes through to the default
 * service unchanged. The class (and its {@code JAVA_QUALIFIER = 'internalConsumptionLineHandler'}
 * registration on ETGO_SF_ENTITY record {@code 1EB67B71AE6445F787649951DFAEE661}) is kept so the
 * existing DB configuration keeps resolving to a valid bean.
 */
@Named("internalConsumptionLineHandler")
public class InternalConsumptionLineHandler implements NeoHandler {

  @Override
  public NeoResponse handle(NeoContext context) {
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    // Warehouse-name enrichment is now handled generically for all locator FKs.
    return null;
  }
}
