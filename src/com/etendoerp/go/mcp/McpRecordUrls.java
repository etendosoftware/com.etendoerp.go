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

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.common.PublicUrlResolver;

/**
 * Builds Etendo Go application links for records reached through the MCP (ETP-5200, resolved in
 * ETP-5184).
 *
 * <p>Until this existed the MCP emitted no URL at all, so an agent asked for "the link to that
 * order" had to invent one — and did, guessing the legacy backoffice
 * {@code /etendo/?tabId=&recordId=} shape, which on a Go deployment is a different application
 * altogether. The React router (schema_forge {@code runtime-routes.jsx}) resolves a record at
 * {@code :windowName/:recordId}, where {@code windowName} is the kebab-case spec name — the very
 * string the MCP already takes as its {@code spec} argument. No translation is needed; the link is
 * just {@code baseUrl + "/" + spec + "/" + id}.</p>
 *
 * <p>Two rules keep the links honest:</p>
 * <ol>
 *   <li><b>No configured base, no link.</b> The base comes from
 *       {@link PublicUrlResolver#resolveConfiguredAppBaseUrl()} ({@code etendo.go.app.baseUrl} /
 *       {@code ETGO_APP_BASE_URL}) and from nowhere else. The tempting fallback —
 *       {@code context.url} — is the <em>internal</em> Tomcat address, and using it is exactly what
 *       produced {@code http://localhost:8080/...} instead of {@code http://localhost:3100/...} for
 *       the image-upload URL in ETP-5184. A wrong link is worse than no link, because an agent
 *       publishes it either way and only the human discovers it is dead.</li>
 *   <li><b>Only a spec's primary entity has a route.</b> A line record has no page of its own, so it
 *       gets no {@code url}; the agent is meant to link to its header instead.</li>
 * </ol>
 *
 * <p>The template is advertised once per session by {@code neo_discover} rather than repeated on
 * every row: it costs a couple of dozen tokens once and lets the agent build a link for any record
 * it ever sees, including the rows of a 100-record {@code neo_list} that deliberately carries
 * none.</p>
 */
public final class McpRecordUrls {

  /** Key holding the app metadata block inside the {@code neo_discover} result. */
  static final String KEY_APP = "app";
  /** Key holding the resolved public base URL of the Etendo Go app. */
  static final String KEY_BASE_URL = "baseUrl";
  /** Key holding the record URL template. */
  static final String KEY_RECORD_URL_TEMPLATE = "recordUrlTemplate";
  /** Key holding a single record's link in a {@code neo_get} / {@code neo_create} result. */
  static final String KEY_URL = "url";

  private static final String KEY_DATA = "data";
  private static final String KEY_ID = "id";

  /** The shape of a record link, in the placeholder form an agent can fill in itself. */
  static final String RECORD_URL_TEMPLATE = "{baseUrl}/{spec}/{id}";

  static final String APP_HINT = "Build a link to any record as " + RECORD_URL_TEMPLATE
      + ", where {spec} is the spec name you pass to the tools and {id} is the record id. "
      + "Only a spec's primaryEntity has a page of its own — link a line record to its header.";

  private McpRecordUrls() {
  }

  /**
   * Describes how record links are built, for {@code neo_discover} to advertise once per session.
   *
   * @return the app metadata object, or {@code null} when no public app base URL is configured —
   *     in which case the caller must omit the key entirely rather than guess a base
   * @throws JSONException never in practice (every value is a plain string)
   */
  static JSONObject buildAppMetadata() throws JSONException {
    String baseUrl = PublicUrlResolver.resolveConfiguredAppBaseUrl();
    if (StringUtils.isBlank(baseUrl)) {
      return null;
    }
    JSONObject app = new JSONObject();
    app.put(KEY_BASE_URL, baseUrl);
    app.put(KEY_RECORD_URL_TEMPLATE, RECORD_URL_TEMPLATE);
    app.put(McpConstants.KEY_HINT, APP_HINT);
    return app;
  }

  /**
   * Builds the Etendo Go link for one record.
   *
   * @param specName the spec name, which doubles as the app's window segment
   * @param recordId the record id
   * @return the absolute record URL, or {@code null} when the app base URL is not configured or
   *     either argument is blank
   */
  static String buildRecordUrl(String specName, String recordId) {
    if (StringUtils.isAnyBlank(specName, recordId)) {
      return null;
    }
    String baseUrl = PublicUrlResolver.resolveConfiguredAppBaseUrl();
    if (StringUtils.isBlank(baseUrl)) {
      return null;
    }
    return PublicUrlResolver.appendPath(baseUrl, specName + "/" + recordId);
  }

  /**
   * Adds a {@code url} to a flattened single-record result, when one can be built.
   *
   * <p>Silently does nothing when the entity is not the spec's primary one, when no base URL is
   * configured, or when no record id could be determined — all three are ordinary states, not
   * errors, and none of them should cost the agent a failed call.</p>
   *
   * @param flatResult      the flattened {@code neo_get} / {@code neo_create} result, mutated in
   *                        place; {@code null} is tolerated
   * @param specName        the spec name
   * @param recordId        the record id, or {@code null} to read it back from {@code flatResult}
   * @param isPrimaryEntity whether the addressed entity is the spec's header-level entity
   * @throws JSONException never in practice (the added value is a plain string)
   */
  static void addRecordUrl(JSONObject flatResult, String specName, String recordId,
      boolean isPrimaryEntity) throws JSONException {
    if (flatResult == null || !isPrimaryEntity) {
      return;
    }
    String id = StringUtils.isNotBlank(recordId) ? recordId : extractRecordId(flatResult);
    String url = buildRecordUrl(specName, id);
    if (url != null) {
      flatResult.put(KEY_URL, url);
    }
  }

  /**
   * Reads the record id back out of a flattened single-record result.
   *
   * <p>Needed by {@code neo_create}, where the id is assigned by the database and therefore only
   * exists in the response.</p>
   *
   * @param flatResult the flattened result
   * @return the id of the first returned record, or {@code null} when the result carries none
   */
  static String extractRecordId(JSONObject flatResult) {
    if (flatResult == null) {
      return null;
    }
    JSONArray data = flatResult.optJSONArray(KEY_DATA);
    if (data == null || data.length() == 0) {
      return null;
    }
    JSONObject first = data.optJSONObject(0);
    return first == null ? null : StringUtils.trimToNull(first.optString(KEY_ID, null));
  }
}
