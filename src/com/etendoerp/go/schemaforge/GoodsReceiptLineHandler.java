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
 * NeoHandler for the Goods Receipt line entity ({@code goodsReceiptLine}).
 *
 * <p>Everything this entity needs — invoice-line linking, order/invoice qty + product-code
 * enrichment, the storageBin default-locator pre-hook (ETP-4671/ETP-4863), and the stock-derived
 * {@code movementQuantity} strip (ETP-4671, extended to Goods Shipment by ETP-5062) — is
 * identical to {@link GoodsShipmentLineHandler}'s, so it all lives in the shared
 * {@link AbstractInOutLineHandler} base class. This class exists only to bind the
 * {@code goodsReceiptLineHandler} Java_Qualifier via {@code @Named}.
 */
@Named("goodsReceiptLineHandler")
public class GoodsReceiptLineHandler extends AbstractInOutLineHandler {
}
