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

import static com.etendoerp.go.mcp.McpJsonSchema.buildObjectSchema;
import static com.etendoerp.go.mcp.McpJsonSchema.enumProp;
import static com.etendoerp.go.mcp.McpJsonSchema.stringProp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.etendoerp.go.schemaforge.util.NeoImageHelper;

/**
 * The tool definitions of the three built-in image-upload tools (ETP-5184), split out of
 * {@link ToolRegistry} (java:S1448). Their runtime handlers live in {@link McpImageTools}.
 *
 * <p>Built-in and type-driven, not spec-driven: they create an AD_Image row and nothing else, and
 * the same three tools serve every image-typed field in the instance.</p>
 */
final class McpImageToolDefinitions {

  private McpImageToolDefinitions() {
  }

  /**
   * Description of {@link McpConstants#TOOL_NEO_REQUEST_IMAGE_UPLOAD}.
   *
   * <p>Held as a constant because a test asserts it names the cheap path and the cap: the guidance
   * an agent reads and the validation the server enforces must not be able to drift apart.
   */
  static final String REQUEST_IMAGE_UPLOAD_DESCRIPTION =
      "Returns a single-use URL to upload an image to Etendo, plus a ready-to-run curl command. "
      + "Prefer this over " + McpConstants.TOOL_NEO_UPLOAD_IMAGE + " whenever you can run a shell "
      + "command or the user can open a link: the image bytes never pass through the conversation, "
      + "so it costs almost no tokens. After the upload succeeds you get an imageId — write it to "
      + "any field of type 'image' with etendo_update. The URL works exactly once and expires in 10 "
      + "minutes.";

  /** Description of {@link McpConstants#TOOL_NEO_UPLOAD_IMAGE}. See above for why it is a constant. */
  static final String UPLOAD_IMAGE_DESCRIPTION =
      "Uploads an image inline as base64 and returns its imageId. Use only for images under 256 KB: "
      + "base64 in a tool argument is model output, so ~100 KB of image costs ~100k tokens. If you "
      + "can run a shell command, use " + McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD + " instead. "
      + "image/png or image/jpeg only; resize to max 1024 px on the long side before encoding.";

  /** Description of {@link McpConstants#TOOL_NEO_GET_IMAGE_UPLOAD}. */
  static final String GET_IMAGE_UPLOAD_DESCRIPTION =
      "Looks up an upload ticket returned by " + McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD
      + " and reports whether the file has arrived, plus the imageId once it has. Use it only when "
      + "you did not see the output of the upload itself — the PUT already returns the imageId.";

  static McpToolDefinition requestImageUpload() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("name", stringProp(
        "Optional name for the stored image (defaults to 'image')."));
    props.put("mime_type", enumProp(
        "Optional expected type. Omit it and the type is detected from the uploaded bytes; if you "
            + "do send it, it is cross-checked against them and a mismatch is rejected.",
        NeoImageHelper.ALLOWED_MIME_TYPES));
    return new McpToolDefinition(McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD,
        REQUEST_IMAGE_UPLOAD_DESCRIPTION, buildObjectSchema(props, null));
  }

  static McpToolDefinition uploadImage() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("data_base64", stringProp(
        "The image file encoded as base64. A 'data:image/png;base64,' prefix is accepted and "
            + "stripped. Hard limit: 256 KB decoded — over that the call is rejected and points you "
            + "at " + McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD + "."));
    props.put("name", stringProp(
        "Optional name for the stored image (defaults to 'image')."));
    props.put("mime_type", enumProp(
        "Optional. Cross-checked against the actual bytes; omit it and the type is detected.",
        NeoImageHelper.ALLOWED_MIME_TYPES));
    return new McpToolDefinition(McpConstants.TOOL_NEO_UPLOAD_IMAGE, UPLOAD_IMAGE_DESCRIPTION,
        buildObjectSchema(props, List.of("data_base64")));
  }

  static McpToolDefinition getImageUpload() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("token", stringProp("The token returned by "
        + McpConstants.TOOL_NEO_REQUEST_IMAGE_UPLOAD + "."));
    return new McpToolDefinition(McpConstants.TOOL_NEO_GET_IMAGE_UPLOAD,
        GET_IMAGE_UPLOAD_DESCRIPTION, buildObjectSchema(props, List.of("token")));
  }
}
