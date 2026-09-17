/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * *************************************************************************
 */
package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DevLifecycleToolServiceTest {

  @AfterEach
  void clearProperties() {
    System.clearProperty(DevLifecycleToolService.ENABLED_PROPERTY);
    System.clearProperty(DevLifecycleToolService.ENVIRONMENT_PROPERTY);
  }

  @Test
  void toolIsDisabledByDefault() {
    assertFalse(DevLifecycleToolService.isEnabled());
  }

  @Test
  void toolRequiresExplicitLocalEnvironmentAndFlag() {
    System.setProperty(DevLifecycleToolService.ENABLED_PROPERTY, "true");
    assertFalse(DevLifecycleToolService.isEnabled());

    System.setProperty(DevLifecycleToolService.ENVIRONMENT_PROPERTY, "production");
    assertFalse(DevLifecycleToolService.isEnabled());

    System.setProperty(DevLifecycleToolService.ENVIRONMENT_PROPERTY, "local");
    assertTrue(DevLifecycleToolService.isEnabled());
  }
}
