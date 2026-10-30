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

/**
 * Customization of sales-quotation/quotationLine (ETP-5528): the behaviour lives in
 * {@link OrderLineCustomization}; this class only binds it to the entity.
 *
 * <p><b>Binding.</b> Resolved by {@link NeoExtension} on every channel, ahead of the row's
 * {@code Java_Qualifier}. The row still carries {@code orderLineHandler}, on purpose: several
 * readers look the qualifier up directly instead of going through the dispatcher (the MCP read
 * post-hook, the REST CRUD gate, the field filter, {@code protectedCreateCalloutFields}), and they
 * keep resolving {@link OrderLineHandler} — whose behaviour this class inherits unchanged — so
 * those surfaces behave exactly as before. The price is the ERROR line
 * {@code NeoExtensionIndex.warnOnQualifierDisagreement} logs on each resolution; see
 * {@code neo-headless.md} §4.12.20.
 *
 * <p>No {@code @Named}: the parent's {@code orderLineHandler} name is not inherited, and a second
 * bean carrying it would make the name ambiguous.
 * {@link SalesOrderLineHandler} binds the same customization to the
 * other entity; they are two classes only because {@link NeoExtension} is not repeatable.
 */
@NeoExtension(spec = "sales-quotation", entity = "quotationLine")
public class SalesQuotationLineHandler extends OrderLineCustomization {
}
