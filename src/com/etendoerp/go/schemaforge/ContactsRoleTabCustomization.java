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

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

/**
 * The role tabs of the Contacts window over {@code C_BPartner} — {@code contacts/customer} and
 * {@code contacts/vendorCreditor} (ETP-5852, M-172). Their AD tabs carry no filter, so an MCP
 * {@code etendo_list} on {@code customer} returned every business partner, vendors included, and
 * agents asking for "the customers" got suppliers mixed in.
 *
 * <p>On the MCP channel only, a list that names no parent business partner keeps the partners that
 * hold the role ({@code isCustomer} / {@code isVendor}). A list scoped to one partner
 * ({@code parentId}) is that partner's role tab and is left alone, and the SPA and REST lists are
 * unchanged.</p>
 */
abstract class ContactsRoleTabCustomization implements NeoHandler {

  private static final String PARAM_PARENT_ID = "parentId";

  /** @return the HQL predicate over {@code e} that keeps the partners holding this tab's role */
  abstract String rolePredicate();

  @Override
  public NeoResponse handle(NeoContext context) {
    return null;
  }

  @Override
  public List<String> readPredicates(NeoContext context) {
    if (context == null || !context.isMcpOrigin() || hasParent(context.getQueryParams())) {
      return List.of();
    }
    return List.of(rolePredicate());
  }

  private static boolean hasParent(Map<String, String> queryParams) {
    return queryParams != null && StringUtils.isNotBlank(queryParams.get(PARAM_PARENT_ID));
  }
}
