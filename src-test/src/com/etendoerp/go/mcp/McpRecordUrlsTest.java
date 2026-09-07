/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Properties;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.session.OBPropertiesProvider;
import org.openbravo.model.ad.ui.Tab;

/**
 * Unit tests for the MCP record-link support (ETP-5200, resolved in ETP-5184).
 *
 * <p>The assertions compare WHOLE URLs rather than checking that a URL merely contains the
 * expected path. That is deliberate: the equivalent test for the image-upload URL only asserted
 * containment, which is precisely why a wrong base ({@code localhost:8080} instead of
 * {@code localhost:3100}) survived the suite and was only caught by a 405 against the running
 * instance.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpRecordUrlsTest {

  private static final String APP_BASE = "https://go.experimental.etendo.cloud";
  private static final String SPEC = "sales-order";
  private static final String RECORD_ID = "4B2DBECAC0D34E309AA5C8C86DC81519";

  private interface ThrowingSupplier<T> {
    T get() throws Exception;
  }

  /**
   * Runs a body with the given Openbravo properties in place. System properties win over these in
   * {@code PublicUrlResolver}, so a developer machine that happens to export the app base URL
   * cannot make a "not configured" test pass by accident — those tests clear it explicitly.
   */
  private <T> T withProperties(Properties props, ThrowingSupplier<T> body) throws Exception {
    OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
    when(provider.getOpenbravoProperties()).thenReturn(props);
    String previous = System.getProperty("etendo.go.app.baseUrl");
    System.clearProperty("etendo.go.app.baseUrl");
    try (MockedStatic<OBPropertiesProvider> propsMock = mockStatic(OBPropertiesProvider.class)) {
      propsMock.when(OBPropertiesProvider::getInstance).thenReturn(provider);
      return body.get();
    } finally {
      if (previous != null) {
        System.setProperty("etendo.go.app.baseUrl", previous);
      }
    }
  }

  private Properties configured() {
    Properties props = new Properties();
    props.setProperty("etendo.go.app.baseUrl", APP_BASE);
    // The internal Tomcat address is present, as it is on every real instance. Nothing here may
    // fall back to it — that is the ETP-5184 uploadUrl bug.
    props.setProperty("context.url", "http://localhost:8080/etendo");
    props.setProperty("context.name", "etendo");
    return props;
  }

  private Properties unconfigured() {
    Properties props = new Properties();
    props.setProperty("context.url", "http://localhost:8080/etendo");
    props.setProperty("context.name", "etendo");
    return props;
  }

  private JSONObject singleRecordResult(String id) throws Exception {
    // Not `record`: restricted identifier since Java 16 (S6213).
    JSONObject recordJson = new JSONObject();
    recordJson.put("id", id);
    recordJson.put("documentNo", "SO/0001");
    JSONArray data = new JSONArray();
    data.put(recordJson);
    JSONObject flat = new JSONObject();
    flat.put("data", data);
    return flat;
  }

  @Nested
  @DisplayName("buildRecordUrl")
  class BuildRecordUrl {

    @Test
    @DisplayName("joins the configured app base, the spec name and the id")
    void buildsTheWholeUrl() throws Exception {
      String url = withProperties(configured(),
          () -> McpRecordUrls.buildRecordUrl(SPEC, RECORD_ID));

      assertEquals(APP_BASE + "/" + SPEC + "/" + RECORD_ID, url);
    }

    @Test
    @DisplayName("normalises a trailing slash on the configured base")
    void trailingSlashIsNormalised() throws Exception {
      Properties props = configured();
      props.setProperty("etendo.go.app.baseUrl", APP_BASE + "/");

      String url = withProperties(props, () -> McpRecordUrls.buildRecordUrl(SPEC, RECORD_ID));

      assertEquals(APP_BASE + "/" + SPEC + "/" + RECORD_ID, url);
    }

    @Test
    @DisplayName("returns null without an app base rather than falling back to context.url")
    void noBaseMeansNoUrl() throws Exception {
      String url = withProperties(unconfigured(),
          () -> McpRecordUrls.buildRecordUrl(SPEC, RECORD_ID));

      // context.url is the internal backend address. A link built on it is dead for the caller,
      // and an agent publishes it anyway — so no link at all is the correct answer.
      assertNull(url);
    }

    @Test
    @DisplayName("returns null for a blank spec or id")
    void blankArgumentsMeanNoUrl() throws Exception {
      assertNull(withProperties(configured(), () -> McpRecordUrls.buildRecordUrl(SPEC, "  ")));
      assertNull(withProperties(configured(), () -> McpRecordUrls.buildRecordUrl(null, RECORD_ID)));
    }
  }

  @Nested
  @DisplayName("buildAppMetadata")
  class BuildAppMetadata {

    @Test
    @DisplayName("advertises the base and the template when the app base URL is configured")
    void emitsTheTemplate() throws Exception {
      JSONObject app = withProperties(configured(), McpRecordUrls::buildAppMetadata);

      assertEquals(APP_BASE, app.getString(McpRecordUrls.KEY_BASE_URL));
      assertEquals("{baseUrl}/{spec}/{id}",
          app.getString(McpRecordUrls.KEY_RECORD_URL_TEMPLATE));
      // The agent must be told that a line record has no page of its own, or it will happily
      // build a link to one.
      assertTrue(app.getString(McpConstants.KEY_HINT).contains("primaryEntity"));
    }

    @Test
    @DisplayName("is absent entirely when no app base URL is configured")
    void omittedWhenUnconfigured() throws Exception {
      assertNull(withProperties(unconfigured(), McpRecordUrls::buildAppMetadata));
    }
  }

  @Nested
  @DisplayName("addRecordUrl")
  class AddRecordUrl {

    @Test
    @DisplayName("adds the whole link to a header record's result")
    void addsUrlForPrimaryEntity() throws Exception {
      JSONObject flat = singleRecordResult(RECORD_ID);

      withProperties(configured(), () -> {
        McpRecordUrls.addRecordUrl(flat, SPEC, RECORD_ID, true);
        return null;
      });

      assertEquals(APP_BASE + "/" + SPEC + "/" + RECORD_ID,
          flat.getString(McpRecordUrls.KEY_URL));
    }

    @Test
    @DisplayName("reads the id back from the response when the caller has none (neo_create)")
    void readsIdFromTheResponse() throws Exception {
      JSONObject flat = singleRecordResult(RECORD_ID);

      withProperties(configured(), () -> {
        McpRecordUrls.addRecordUrl(flat, SPEC, null, true);
        return null;
      });

      assertEquals(APP_BASE + "/" + SPEC + "/" + RECORD_ID,
          flat.getString(McpRecordUrls.KEY_URL));
    }

    @Test
    @DisplayName("a line record gets no url — its page is the header's")
    void noUrlForALine() throws Exception {
      JSONObject flat = singleRecordResult(RECORD_ID);

      withProperties(configured(), () -> {
        McpRecordUrls.addRecordUrl(flat, SPEC, RECORD_ID, false);
        return null;
      });

      assertFalse(flat.has(McpRecordUrls.KEY_URL));
    }

    @Test
    @DisplayName("no url is added when the app base URL is not configured")
    void noUrlWithoutABase() throws Exception {
      JSONObject flat = singleRecordResult(RECORD_ID);

      withProperties(unconfigured(), () -> {
        McpRecordUrls.addRecordUrl(flat, SPEC, RECORD_ID, true);
        return null;
      });

      assertFalse(flat.has(McpRecordUrls.KEY_URL));
    }

    @Test
    @DisplayName("a result carrying no record leaves the body untouched")
    void noRecordMeansNoUrl() throws Exception {
      JSONObject flat = new JSONObject();
      flat.put("data", new JSONArray());

      withProperties(configured(), () -> {
        McpRecordUrls.addRecordUrl(flat, SPEC, null, true);
        return null;
      });

      assertFalse(flat.has(McpRecordUrls.KEY_URL));
    }
  }

  @Nested
  @DisplayName("isPrimaryTab")
  class IsPrimaryTab {

    @Test
    @DisplayName("only tab level 0 is primary")
    void onlyLevelZero() {
      Tab header = mock(Tab.class);
      when(header.getTabLevel()).thenReturn(0L);
      Tab line = mock(Tab.class);
      when(line.getTabLevel()).thenReturn(1L);
      Tab unset = mock(Tab.class);
      when(unset.getTabLevel()).thenReturn(null);

      assertTrue(McpToolRouterSupport.isPrimaryTab(header));
      assertFalse(McpToolRouterSupport.isPrimaryTab(line));
      assertFalse(McpToolRouterSupport.isPrimaryTab(unset));
      assertFalse(McpToolRouterSupport.isPrimaryTab(null));
    }
  }
}
