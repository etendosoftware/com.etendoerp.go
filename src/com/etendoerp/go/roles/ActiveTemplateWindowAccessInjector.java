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
package com.etendoerp.go.roles;

import java.util.List;

import javax.enterprise.inject.Specializes;

import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.structure.InheritedAccessEnabled;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.role.inheritance.access.AccessTypeInjector;
import org.openbravo.role.inheritance.access.WindowAccessInjector;

/**
 * ETP-5830 — core's {@link WindowAccessInjector}, except that a system template's soft-deleted
 * rows are never inherited. See {@link SystemTemplateAccessRows}.
 *
 * <p>{@code @Specializes} replaces the core bean, so core still resolves exactly one injector for
 * the type. The qualifier is repeated because core reads it off {@code getClass()} and it is not
 * {@code @Inherited}.</p>
 */
@Specializes
@AccessTypeInjector.Qualifier(WindowAccess.class)
public class ActiveTemplateWindowAccessInjector extends WindowAccessInjector {

  @Override
  public <T extends BaseOBObject> List<? extends InheritedAccessEnabled> getAccessList(Role role) {
    return SystemTemplateAccessRows.withoutInactive(role, super.getAccessList(role));
  }
}
