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
package com.etendoerp.go.schemaforge;

/**
 * Binds the transaction handler to the Product window's {@code transactions} entity (Movements tab, ETP-5908).
 *
 * <p>The behaviour lives in {@link ProductTransactionsHandler}; this class only binds it to the
 * pair through {@link NeoExtension}, which every channel resolves ahead of the row's
 * {@code Java_Qualifier}. No {@code @Named}.
 */
@NeoExtension(spec = "product", entity = "transactions")
public class ProductMovementsTabHandler extends ProductTransactionsHandler {
}
