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

import com.etendoerp.go.schemaforge.NeoExtension;

/**
 * ETP-5693: customization of the {@code accounting} entity in the Asset Group window — accounting
 * combinations must be posting accounts of the row's ledger, and that ledger cannot change on
 * update. See {@link AbstractAccountingSchemaAutoFillHandler}.
 */
@NeoExtension(spec = "asset-group", entity = "accounting")
public class AssetGroupAccountingHandler extends AbstractAccountingSchemaAutoFillHandler {

  @Override
  protected String describeContext() {
    return "asset group accounting line";
  }
}
