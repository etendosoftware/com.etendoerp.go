package com.etendoerp.go.supportaccess;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.Role;

/**
 * ETP-5351 - edge cases of {@link SupportAccessGuard#changesAccessFields}: null/blank handling,
 * reference-shaped values and the fail-closed paths. No database.
 */
class SupportAccessGuardChangesAccessFieldsEdgeTest {

  private static final String TARGET = "TARGET00000000000000000000000001";

  private static void withUser(String email, String username, String roleId, Runnable body) {
    User stored = mock(User.class);
    when(stored.getEmail()).thenReturn(email);
    when(stored.getUsername()).thenReturn(username);
    if (roleId != null) {
      Role role = mock(Role.class);
      when(role.getId()).thenReturn(roleId);
      when(stored.getDefaultRole()).thenReturn(role);
    }
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, TARGET)).thenReturn(stored);
    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
      dal.when(OBDal::getInstance).thenReturn(obDal);
      body.run();
    }
  }

  @Test
  void nullUserIdWithAnAccessFieldFailsClosed() throws Exception {
    JSONObject body = new JSONObject().put("email", "a@b.c");
    withUser("a@b.c", "u", "R1", () -> assertTrue(SupportAccessGuard.changesAccessFields(body, null)));
  }

  @Test
  void emptyBodyIsNotAChange() {
    withUser("a@b.c", "u", "R1",
        () -> assertFalse(SupportAccessGuard.changesAccessFields(new JSONObject(), TARGET)));
  }

  @Test
  void jsonNullEmailOverAStoredEmailIsAChange() throws Exception {
    JSONObject body = new JSONObject().put("email", JSONObject.NULL);
    withUser("a@b.c", "u", "R1", () -> assertTrue(SupportAccessGuard.changesAccessFields(body, TARGET)));
  }

  @Test
  void jsonNullEmailOverANullStoredEmailIsNotAChange() throws Exception {
    JSONObject body = new JSONObject().put("email", JSONObject.NULL);
    withUser(null, "u", "R1", () -> assertFalse(SupportAccessGuard.changesAccessFields(body, TARGET)));
  }

  @Test
  void defaultRoleAsReferenceObjectWithSameIdIsNotAChange() throws Exception {
    JSONObject body = new JSONObject().put("defaultRole", new JSONObject().put("id", "R1"));
    withUser("a@b.c", "u", "R1", () -> assertFalse(SupportAccessGuard.changesAccessFields(body, TARGET)));
  }

  @Test
  void defaultRoleAsReferenceObjectWithOtherIdIsAChange() throws Exception {
    JSONObject body = new JSONObject().put("defaultRole", new JSONObject().put("id", "R2"));
    withUser("a@b.c", "u", "R1", () -> assertTrue(SupportAccessGuard.changesAccessFields(body, TARGET)));
  }

  @Test
  void defaultRoleIsCaseSensitive() throws Exception {
    JSONObject body = new JSONObject().put("defaultRole", "r1");
    withUser("a@b.c", "u", "R1", () -> assertTrue(SupportAccessGuard.changesAccessFields(body, TARGET)));
  }

  @Test
  void settingADefaultRoleWhenNoneIsStoredIsAChange() throws Exception {
    JSONObject body = new JSONObject().put("defaultRole", "R1");
    withUser("a@b.c", "u", null, () -> assertTrue(SupportAccessGuard.changesAccessFields(body, TARGET)));
  }

  @Test
  void blankDefaultRoleWhenNoneIsStoredIsNotAChange() throws Exception {
    JSONObject body = new JSONObject().put("defaultRole", "  ");
    withUser("a@b.c", "u", null, () -> assertFalse(SupportAccessGuard.changesAccessFields(body, TARGET)));
  }

  @Test
  void nonBlankPasswordIsAChangeEvenForAnUnknownOrStoredUser() throws Exception {
    JSONObject body = new JSONObject().put("password", "known");
    withUser("a@b.c", "u", "R1", () -> assertTrue(SupportAccessGuard.changesAccessFields(body, TARGET)));
  }

  @Test
  void blankPasswordAndPhoneOnlyAreNotAChange() throws Exception {
    JSONObject body = new JSONObject().put("password", "").put("phone", "1");
    withUser("a@b.c", "u", "R1", () -> assertFalse(SupportAccessGuard.changesAccessFields(body, TARGET)));
  }

  @Test
  void flagAgainstANullStoredValueDiffersFromTrue() throws Exception {
    JSONObject body = new JSONObject().put("locked", true);
    withUser("a@b.c", "u", "R1", () -> assertTrue(SupportAccessGuard.changesAccessFields(body, TARGET)));
  }
}
