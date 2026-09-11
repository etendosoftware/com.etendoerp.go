/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashSet;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link StarOrgWriteScope} — the scoped {@code *}-organization write grant used by the
 * document-number generation paths (ETP-5230).
 *
 * <p>Pure Mockito: {@link OBContext} and {@link OBDal} are statically mocked, so there is no DAL and no
 * database. Deliberately NOT an {@code OBBaseTest} subclass — that would route the class into the
 * isolated DAL test JVM, which this helper does not need.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StarOrgWriteScopeTest {

  /** {@code AD_Org_ID} of the {@code *} ("All Organizations") organization. */
  private static final String STAR_ORG = "0";
  /** An ordinary client organization, standing in for whatever the caller's role can already write. */
  private static final String TENANT_ORG = "E443A31992CB4635AFCAEABE7183CE85";
  /** The value the body produces, asserted to travel back to the caller unchanged. */
  private static final String BODY_RESULT = "PAY-000042";
  /** Message of the unchecked exception used for the restore-on-failure case. */
  private static final String BOOM = "boom";

  @Mock private OBContext context;
  @Mock private OBDal obDal;
  /** The wrapped work. A mock (not a lambda) so its execution can take part in {@link InOrder}. */
  @Mock private Supplier<String> body;

  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<OBDal> obDalMock;

  @BeforeEach
  void setUp() {
    obContextMock = mockStatic(OBContext.class);
    obDalMock = mockStatic(OBDal.class);

    obContextMock.when(OBContext::getOBContext).thenReturn(context);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    when(body.get()).thenReturn(BODY_RESULT);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
  }

  /**
   * Stubs {@code getWritableOrganizations()} with a real mutable set — the production code only reads it
   * via {@code contains}, and a real {@link HashSet} keeps that honest.
   */
  private void stubWritableOrganizations(String... orgIds) {
    when(context.getWritableOrganizations()).thenReturn(new HashSet<>(Arrays.asList(orgIds)));
  }

  @Test
  @DisplayName("Grants * before the body and restores the org lists after it")
  void testGrantsAndRestoresWhenStarOrgNotWritable() {
    stubWritableOrganizations(TENANT_ORG);

    String result = StarOrgWriteScope.withWritableStarOrg(body);

    // The body's value travels back to the caller untouched.
    assertEquals(BODY_RESULT, result);

    InOrder order = inOrder(context, body);
    order.verify(context).addWritableOrganization(STAR_ORG);   // grant
    order.verify(body).get();                                  // body runs inside the grant
    // restoreOrgLists: addWritableOrganization again — for its cache-nulling side effect, which forces
    // both org lists to be recomputed from the role — then drop "0" from the additional set. Two adds in
    // total is intentional, not a double-grant bug.
    order.verify(context).addWritableOrganization(STAR_ORG);
    order.verify(context).removeWritableOrganization(STAR_ORG);

    verify(context, times(2)).addWritableOrganization(STAR_ORG);
    verify(context, times(1)).removeWritableOrganization(STAR_ORG);
  }

  @Test
  @DisplayName("Flushes inside the scope: after the body, before the restore")
  void testFlushesInsideTheScope() {
    stubWritableOrganizations(TENANT_ORG);

    StarOrgWriteScope.withWritableStarOrg(body);

    // The security check fires on flush, so the flush must happen while "0" is still writable.
    InOrder order = inOrder(context, body, obDal);
    order.verify(context).addWritableOrganization(STAR_ORG);
    order.verify(body).get();
    order.verify(obDal).flush();
    order.verify(context).removeWritableOrganization(STAR_ORG);

    verify(obDal, times(1)).flush();
  }

  @Test
  @DisplayName("Is a pass-through when the role already holds * (client-admin \" CO\" level)")
  void testIsNoOpWhenStarOrgAlreadyWritable() {
    stubWritableOrganizations(STAR_ORG, TENANT_ORG);

    String result = StarOrgWriteScope.withWritableStarOrg(body);

    assertEquals(BODY_RESULT, result);
    verify(body).get();
    // Nothing to grant and nothing to take away: the context and the session are left untouched.
    verify(context, never()).addWritableOrganization(anyString());
    verify(context, never()).removeWritableOrganization(anyString());
    verify(context, never()).removeFromWritableOrganization(anyString());
    verify(obDal, never()).flush();
  }

  @Test
  @DisplayName("Is a pass-through when there is no OBContext (background process)")
  void testIsNoOpWhenThereIsNoObContext() {
    obContextMock.when(OBContext::getOBContext).thenReturn(null);

    String result = assertDoesNotThrow(() -> StarOrgWriteScope.withWritableStarOrg(body));

    assertEquals(BODY_RESULT, result);
    verify(body).get();
    verify(obDal, never()).flush();
  }

  @Test
  @DisplayName("Restores the org lists and propagates the failure when the body throws")
  void testRestoresWhenBodyThrows() {
    stubWritableOrganizations(TENANT_ORG);
    when(body.get()).thenThrow(new IllegalStateException(BOOM));

    IllegalStateException thrown = assertThrows(IllegalStateException.class,
        () -> StarOrgWriteScope.withWritableStarOrg(body));

    assertEquals(BOOM, thrown.getMessage());
    // The finally block still ran, so the grant does not leak into the rest of the request.
    verify(context, times(2)).addWritableOrganization(STAR_ORG);
    verify(context, times(1)).removeWritableOrganization(STAR_ORG);
    // The flush sits after the body, so a failing body must never reach it.
    verify(obDal, never()).flush();
  }

  @Test
  @DisplayName("Never calls removeFromWritableOrganization — it would NPE on the nulled list")
  void testNeverCallsRemoveFromWritableOrganization() {
    stubWritableOrganizations(TENANT_ORG);

    StarOrgWriteScope.withWritableStarOrg(body);

    // Regression guard. restoreOrgLists' addWritableOrganization nulls writableOrganizations, and
    // OBContext#removeFromWritableOrganization dereferences that field directly
    // (writableOrganizations.remove(orgId)) — appending it to the restore would throw a
    // NullPointerException out of the finally block, masking whatever the body returned or threw.
    verify(context, never()).removeFromWritableOrganization(anyString());
  }
}
