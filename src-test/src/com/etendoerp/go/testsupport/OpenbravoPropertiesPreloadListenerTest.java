/* Etendo License. */
package com.etendoerp.go.testsupport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBConfigFileProvider;
import org.openbravo.base.provider.OBProvider;

import com.etendoerp.go.common.ConfigPropertyReader;

/**
 * Guards the JVM-wide preload of {@code Openbravo.properties}: whatever a test does first, the
 * config-file location an {@code OBBaseTest} needs is already set by the real providers.
 *
 * <p>The single test reproduces the worst case on purpose — its very first action reads a property
 * while {@code OBProvider} is statically mocked, exactly what poisoned the JVM before the listener
 * existed. Without the listener registered it fails on the location assertion.</p>
 *
 * @covers com.etendoerp.go.testsupport.OpenbravoPropertiesPreloadListener
 */
public class OpenbravoPropertiesPreloadListenerTest {

  @Test
  public void configFileLocationSurvivesAFirstPropertiesReadUnderAMockedOBProvider() {
    try (MockedStatic<OBProvider> ignored = mockStatic(OBProvider.class)) {
      // Swallows any exception, like the production readers that poisoned the JVM.
      ConfigPropertyReader.readConfigValue("etendo.go.preload.guard", "ETENDO_GO_PRELOAD_GUARD", "fallback");
    }

    OBConfigFileProvider configFileProvider = OBConfigFileProvider.getInstance();
    assertFalse("OBConfigFileProvider was cached as a Mockito mock",
        mockingDetails(configFileProvider).isMock());
    assertNotNull("Openbravo.properties location was never set; OBBaseTest would NPE in classSetUp",
        configFileProvider.getFileLocation());
    assertEquals("the LauncherSessionListener did not run in this JVM", 1,
        OpenbravoPropertiesPreloadListener.preloadCount());
  }
}
