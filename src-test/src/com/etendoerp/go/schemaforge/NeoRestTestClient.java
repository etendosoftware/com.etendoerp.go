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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Test support for integration tests that go through the real NEO REST dispatch:
 * {@link NeoCrudHandler#handleWindowEntityCrud} with only the servlet's HTTP seams stubbed, and the
 * customization dispatch delegated to the real {@link NeoServletSupport#handleWithHooks} (so a
 * {@code @NeoExtension} handler, whose row has no {@code Java_Qualifier}, is resolved as in
 * production). Not a test itself.
 *
 * @covers com.etendoerp.go.schemaforge.NeoCrudHandler
 */
final class NeoRestTestClient {

  private NeoRestTestClient() {
  }

  /**
   * One REST request to {@code spec/entity}.
   *
   * @param spec   the spec name
   * @param entity the entity name
   * @param method {@code GET}, {@code POST}, {@code PATCH}, …
   * @param id     the record id, or {@code null}
   * @param body   the JSON body
   * @return what the dispatch answered
   */
  static NeoResponse send(String spec, String entity, String method, String id, JSONObject body)
      throws Exception {
    SFSpec sfSpec = NeoServletSupport.findSpec(spec);
    assumeNotNull("spec not configured: " + spec, sfSpec);
    SFEntity sfEntity = findEntity(sfSpec.getId(), entity);
    assumeNotNull("entity not configured: " + spec + "/" + entity, sfEntity);

    NeoServlet servlet = mock(NeoServlet.class);
    NeoCrudHandler handler = new NeoCrudHandler(servlet);
    AtomicReference<NeoResponse> written = new AtomicReference<>();
    when(servlet.findEntity(sfSpec.getId(), entity)).thenReturn(sfEntity);
    when(servlet.extractQueryParams(any())).thenReturn(new HashMap<>());
    when(servlet.handleWithHooks(nullable(String.class), any(NeoContext.class), any(), any()))
        .thenAnswer(inv -> NeoServletSupport.handleWithHooks(inv.getArgument(0),
            inv.getArgument(1), handler));
    doAnswer(inv -> {
      written.set(inv.getArgument(1));
      return null;
    }).when(servlet).writeResponse(any(), any());
    doAnswer(inv -> {
      written.set(NeoResponse.error((int) inv.getArgument(1), (String) inv.getArgument(2)));
      return null;
    }).when(servlet).sendError(any(), anyInt(), anyString());

    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getInputStream()).thenReturn(inputStreamOf(body.toString()));
    handler.handleWindowEntityCrud(sfSpec, new NeoServlet.NeoPathInfo(spec, entity, id), method,
        request, mock(HttpServletResponse.class));
    assertNotNull(method + " answered nothing", written.get());
    return written.get();
  }

  /**
   * {@code GET spec/entity/defaults}: the real defaults resolver run as the default step of the
   * real customization pipeline ({@link NeoServletSupport#handleWithDefaultStep}), as the
   * sub-endpoint dispatcher runs it.
   */
  static NeoResponse defaults(String spec, String entity) throws Exception {
    SFSpec sfSpec = NeoServletSupport.findSpec(spec);
    assumeNotNull("spec not configured: " + spec, sfSpec);
    SFEntity sfEntity = findEntity(sfSpec.getId(), entity);
    assumeNotNull("entity not configured: " + spec + "/" + entity, sfEntity);
    NeoServlet servlet = mock(NeoServlet.class);
    when(servlet.findEntity(sfSpec.getId(), entity)).thenReturn(sfEntity);
    when(servlet.extractQueryParams(any())).thenReturn(new HashMap<>());
    NeoDefaultsEndpoint endpoint = new NeoDefaultsEndpoint(servlet);
    HttpServletRequest request = mock(HttpServletRequest.class);
    NeoServlet.NeoPathInfo path = new NeoServlet.NeoPathInfo(spec, entity, null, false, null,
        false, null, false, false, true);
    NeoContext context = NeoContext.builder().specName(spec).entityName(entity)
        .httpMethod("GET").endpointType(NeoEndpointType.DEFAULTS).sfEntity(sfEntity)
        .adTab(sfEntity.getADTab()).queryParams(new HashMap<>()).build();
    return NeoServletSupport.handleWithDefaultStep(sfEntity.getJavaQualifier(), context,
        ctx -> endpoint.handleDefaults(sfSpec, path, request), NeoExtensionChannel.REST_SINGLE);
  }

  /**
   * PATCH {@code changes} as a client does: with the {@code updated} value it read through NEO
   * (mandatory on every update, the optimistic-concurrency check).
   */
  static NeoResponse patch(String spec, String entity, String id, JSONObject changes)
      throws Exception {
    return send(spec, entity, "PATCH", id, changes.put("updated", readUpdated(spec, entity, id)));
  }

  /** The {@code updated} value a GET of the record returns. */
  static Object readUpdated(String spec, String entity, String id) throws Exception {
    NeoResponse read = send(spec, entity, "GET", id, new JSONObject());
    assertEquals(String.valueOf(read.getBody()), 200, read.getHttpStatus());
    Object updated = findKey(read.getBody(), "updated");
    assertNotNull("the read carries updated: " + read.getBody(), updated);
    return updated;
  }

  /** The first value of {@code key} in {@code json}, searched depth-first. */
  static Object findKey(Object json, String key) {
    if (json instanceof JSONObject) {
      JSONObject object = (JSONObject) json;
      if (object.has(key) && !object.isNull(key)) {
        return object.opt(key);
      }
      Iterator<?> keys = object.keys();
      while (keys.hasNext()) {
        Object found = findKey(object.opt((String) keys.next()), key);
        if (found != null) {
          return found;
        }
      }
    } else if (json instanceof JSONArray) {
      JSONArray array = (JSONArray) json;
      for (int i = 0; i < array.length(); i++) {
        Object found = findKey(array.opt(i), key);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  /** The error key of a NEO refusal ({@code error.messageKeys[0]}), or {@code null}. */
  static String messageKey(NeoResponse response) {
    JSONObject error = response.getBody() != null ? response.getBody().optJSONObject("error")
        : null;
    JSONArray keys = error != null ? error.optJSONArray("messageKeys") : null;
    return keys != null && keys.length() > 0 ? keys.optString(0) : null;
  }

  private static SFEntity findEntity(String specId, String entityName) {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    criteria.add(Restrictions.ilike(SFEntity.PROPERTY_NAME, entityName, MatchMode.EXACT));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
    criteria.setMaxResults(1);
    List<SFEntity> rows = criteria.list();
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static ServletInputStream inputStreamOf(String content) {
    ByteArrayInputStream bytes = new ByteArrayInputStream(
        content.getBytes(StandardCharsets.UTF_8));
    return new ServletInputStream() {
      @Override
      public int read() {
        return bytes.read();
      }

      @Override
      public boolean isFinished() {
        return bytes.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener listener) {
        // not used: the body is read synchronously
      }
    };
  }
}
