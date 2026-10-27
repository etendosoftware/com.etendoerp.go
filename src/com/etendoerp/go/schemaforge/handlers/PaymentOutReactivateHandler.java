/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"); you may not use this file except in compliance with
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

package com.etendoerp.go.schemaforge.handlers;

import com.etendoerp.go.schemaforge.NeoExtension;

/**
 * Binding of the shared {@link ReactivatePaymentHandler} to the {@code payment-out} ({@code pagos})
 * window's {@code header} entity.
 *
 * <p>The behaviour lives in {@link ReactivatePaymentHandler}; this class only binds it to the entity
 * through {@link NeoExtension}. It is a second class because the annotation is not repeatable and
 * {@link ReactivatePaymentHandler} serves two entities — the same shape
 * {@code SalesOrderLineHandler}/{@code SalesQuotationLineHandler} use. The annotation is the only
 * binding: the row carries no {@code Java_Qualifier} and no class carries {@code @Named}
 * (ETP-5588); see {@code neo-headless.md} §5.3.a.
 */
@NeoExtension(spec = "payment-out", entity = "header")
public class PaymentOutReactivateHandler extends ReactivatePaymentHandler {
}
