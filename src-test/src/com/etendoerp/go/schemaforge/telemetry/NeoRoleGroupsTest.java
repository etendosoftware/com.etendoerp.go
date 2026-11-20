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

package com.etendoerp.go.schemaforge.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.rest.EtendoGoJwtSupport;

/**
 * Unit tests for {@link NeoRoleGroups}, the Java port of the SPA's {@code resolveRoleGroups}: a
 * client-admin role is {@code [admin]}; otherwise the groups of the known template roles composed
 * into the user's personal role, sorted and deduplicated; {@code [other]} when none is known; and,
 * for the running request, the templates apply only while the active role is the user's default
 * one (any other role is {@code [other]}), and no role or user is an empty list.
 *
 * <p>{@link OBContext} and {@link EtendoGoJwtSupport#resolveEffectiveRoleNames} are mocked
 * statically.
 *
 * <p>A class of its own: {@code NeoRoleGroups} is a new production class.
 *
 * @covers com.etendoerp.go.schemaforge.telemetry.NeoRoleGroups
 */
class NeoRoleGroupsTest {

  private MockedStatic<OBContext> contextStatic;
  private MockedStatic<EtendoGoJwtSupport> jwtSupport;
  private OBContext context;
  private Role role;
  private User user;

  @BeforeEach
  void setUp() {
    role = mock(Role.class);
    when(role.getId()).thenReturn("ROLE-PERSONAL");
    when(role.isClientAdmin()).thenReturn(false);
    user = mock(User.class);
    when(user.getId()).thenReturn("USER-1");
    when(user.getDefaultRole()).thenReturn(role);
    context = mock(OBContext.class);
    when(context.getRole()).thenReturn(role);
    when(context.getUser()).thenReturn(user);
    contextStatic = mockStatic(OBContext.class);
    contextStatic.when(OBContext::getOBContext).thenReturn(context);
    jwtSupport = mockStatic(EtendoGoJwtSupport.class);
  }

  @AfterEach
  void tearDown() {
    jwtSupport.close();
    contextStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  @Test
  void aClientAdminIsAdminWhateverItsTemplates() {
    assertEquals(List.of("admin"), NeoRoleGroups.resolve(true, List.of("Sales", "Finance")));
  }

  @Test
  void theKnownTemplatesAreMappedSortedAndDeduplicated() {
    assertEquals(List.of("finance", "sales", "warehouse"), NeoRoleGroups.resolve(false,
        Arrays.asList("Sales", "Inventory", "Finance", "Sales", "Custom role", null)));
    assertEquals(List.of("purchasing"), NeoRoleGroups.resolve(false, List.of("Purchasing")));
  }

  @Test
  void noKnownTemplateIsOther() {
    assertEquals(List.of("other"), NeoRoleGroups.resolve(false, List.of("Mi rol")));
    assertEquals(List.of("other"), NeoRoleGroups.resolve(false, List.of()));
    assertEquals(List.of("other"), NeoRoleGroups.resolve(false, null));
  }

  @Test
  void theCurrentDefaultRoleUsesItsComposedTemplates() {
    jwtSupport.when(() -> EtendoGoJwtSupport.resolveEffectiveRoleNames("USER-1"))
        .thenReturn(List.of("Finance", "Sales"));

    assertEquals(List.of("finance", "sales"), NeoRoleGroups.current());
  }

  /** The session's templates belong to the default role: another active role is [other]. */
  @Test
  void aNonDefaultActiveRoleIsOther() {
    Role other = mock(Role.class);
    when(other.getId()).thenReturn("ROLE-OTHER");
    when(context.getRole()).thenReturn(other);
    jwtSupport.when(() -> EtendoGoJwtSupport.resolveEffectiveRoleNames(anyString()))
        .thenReturn(List.of("Finance"));

    assertEquals(List.of("other"), NeoRoleGroups.current());
    jwtSupport.verify(() -> EtendoGoJwtSupport.resolveEffectiveRoleNames(anyString()),
        Mockito.never());

    when(user.getDefaultRole()).thenReturn(null);
    assertEquals(List.of("other"), NeoRoleGroups.current());
  }

  @Test
  void theCurrentAdminRoleIsAdmin() {
    when(role.isClientAdmin()).thenReturn(true);

    assertEquals(List.of("admin"), NeoRoleGroups.current());
  }

  /** Nothing to read is an empty list (the property is then left off), never an exception. */
  @Test
  void noRoleNoUserOrAFailureIsAnEmptyList() {
    when(context.getRole()).thenReturn(null);
    assertTrue(NeoRoleGroups.current().isEmpty());

    when(context.getRole()).thenReturn(role);
    when(context.getUser()).thenReturn(null);
    assertTrue(NeoRoleGroups.current().isEmpty());

    contextStatic.when(OBContext::getOBContext).thenReturn(null);
    assertTrue(NeoRoleGroups.current().isEmpty());

    contextStatic.when(OBContext::getOBContext).thenThrow(new IllegalStateException("no context"));
    assertTrue(NeoRoleGroups.current().isEmpty());
  }
}
