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
 * One dispatch, as the system saw it.
 *
 * <p>This is the deliverable of the T1 step: a single, uniform record of what was asked for
 * ({@code specName}, {@code entityName}, {@code surface}, {@code channel}, {@code qualifier}), what
 * answered ({@code customizationClass}, {@code method}) and what came of it ({@code outcome}).</p>
 *
 * @param specName           the spec the request targeted
 * @param entityName         the entity the request targeted
 * @param surface            the operation dispatched
 * @param channel            the caller family, which also decided the resolver
 * @param qualifier          the {@code Java_Qualifier} looked up
 * @param customizationClass the fully qualified class that answered, or {@code null} when none did
 * @param method             the customization method invoked ({@code handle} / {@code afterHandle}),
 *                           or {@code null} when nothing was invoked
 * @param outcome            what the dispatch did
 */
public record NeoExtensionTrace(String specName, String entityName,
    NeoExtensionSurface surface, NeoExtensionChannel channel, String qualifier,
    String customizationClass, String method, NeoExtensionOutcome outcome) {
}
