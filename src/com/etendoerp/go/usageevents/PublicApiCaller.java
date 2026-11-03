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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.usageevents;

/**
 * ETP-6057 — who made a public API request: the tenant and the public API key, as the OAuth2 layer
 * resolved them from the key's token (or, at the token exchange, from the key row itself).
 *
 * <p>Immutable and free of any Etendo type, so it can be built on the request thread, stored in a
 * request attribute and used as part of a {@link PublicApiUsageCounter} bucket key. It carries ids
 * only: {@code keyId} is the key's public {@code client_identifier}, never its secret, never a token
 * or a hash of one.</p>
 *
 * @param clientId {@code AD_Client_ID} of the tenant the key belongs to (the owner organization's
 *                 client, or the key role's client when that organization is {@code *})
 * @param orgId    {@code AD_Org_ID} of the key's owner organization
 * @param userId   {@code AD_User_ID} the key acts as
 * @param roleId   {@code AD_Role_ID} the key acts with
 * @param keyId    the key's public {@code ETGO_OAUTH2_CLIENT.CLIENT_IDENTIFIER}
 */
public record PublicApiCaller(
    String clientId,
    String orgId,
    String userId,
    String roleId,
    String keyId) {
}
