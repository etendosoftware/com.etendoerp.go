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

package com.etendoerp.go.mcp;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoVectorSearchEndpoint;

/**
 * The {@code etendo_vector_search} tool handler, split out of {@link McpToolRouter} (java:S1448).
 *
 * <p>Runs in the caller's role context: the router dispatches here before it enters admin mode,
 * so vector target authorization keeps AD_Window/entity organization isolation.</p>
 */
final class McpVectorSearchTool {

  private static final String ERROR_BUILDING_CONTENT = "Error building MCP error content";

  private McpVectorSearchTool() {
  }

  /** Route semantic search through the same authenticated DB Extended contract as REST. */
  static JSONObject handle(JSONObject arguments) {
    String query = arguments == null ? null : arguments.optString(McpConstants.PARAM_QUERY, null);
    String targets = McpArgumentUtils.joinStringArray(
        arguments == null ? null : arguments.optJSONArray("targets"));
    if (StringUtils.isBlank(targets)) {
      // IMP-41: `targets` is optional, and omitting it means "search everywhere I may read".
      // The MCP surface exposes no `namespaces` alternative, so demanding a target up front asked
      // the agent for the one thing a natural-language question does not come with.
      java.util.Optional<List<String>> allowed = NeoVectorSearchEndpoint.authorizedTargetKeys();
      if (allowed.isPresent() && allowed.get().isEmpty()) {
        return McpToolRouter.wrapAsErrorContent(buildNoSearchableTargetsBody());
      }
      // Absent means the catalogue could not be read at all, which is not the same as "you may
      // search nothing": leave targets null so the endpoint decides, as it did before IMP-41.
      targets = allowed.map(keys -> String.join(",", keys)).orElse(null);
    }
    NeoResponse response = new NeoVectorSearchEndpoint().handle(query, null, targets,
        McpArgumentUtils.optionalString(arguments, "topK"),
        McpArgumentUtils.optionalString(arguments, "minScore"),
        McpArgumentUtils.optionalString(arguments, "maxScore"), null);
    // ETP-5306: the JSONObject overloads, so the body is sanitised before it is rendered.
    JSONObject body = response.getBody();
    return response.getHttpStatus() >= 400 ? McpToolRouter.wrapAsErrorContent(body)
        : McpToolRouter.wrapAsTextContent(body);
  }

  /**
   * The refusal for a role that can read no search target at all (IMP-41).
   *
   * <p>Said plainly and with a next step, because the alternative is worse than useless: an empty
   * target list would reach the endpoint as "no targets and no namespaces" and come back as a
   * generic 400 about a missing parameter, sending the agent to re-send the same call with
   * invented target names.</p>
   *
   * @return the error envelope
   */
  static JSONObject buildNoSearchableTargetsBody() {
    try {
      JSONObject envelope = new JSONObject();
      envelope.put(McpConstants.KEY_STATUS, McpConstants.STATUS_FORBIDDEN);
      envelope.put(McpConstants.KEY_ERROR, "no_searchable_vector_targets");
      envelope.put(McpConstants.KEY_DETAIL, "Semantic search is configured on this instance, but "
          + "your role cannot read any of its indexes.");
      envelope.put(McpConstants.KEY_TOOL, McpConstants.TOOL_NEO_VECTOR_SEARCH);
      envelope.put(McpConstants.KEY_HINT, "Do not retry with other target names — none would work. "
          + "Use etendo_list or etendo_selectors to find the record instead.");
      return envelope;
    } catch (JSONException e) {
      throw new McpToolException(ERROR_BUILDING_CONTENT, e);
    }
  }
}
