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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.ocr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The OCR configuration (ETP-5715): which document types can be read from a PDF, with the question
 * and the answer schema of each. One resource, {@value #RESOURCE}, read by the MCP tools and served
 * to the application's upload panel, so neither keeps a copy.
 *
 * <p>Which specs support OCR is exactly the list in that file; nothing else in the code names one.
 * A missing or unreadable resource yields an empty configuration that is marked
 * {@link #isUnavailable() unavailable} (a deployment fault, not "no spec supports OCR") and an
 * error in the log, never an exception at tool-listing time.</p>
 */
public final class OcrConfig {

  /**
   * Classpath resource, in this class's package. It sits next to the class under {@code src/}: the
   * ant build ({@code src/build.xml}, {@code postsrc.modules}) copies every non-{@code .java} file
   * of a module's {@code src} tree into {@code build/classes}, hence into {@code WEB-INF/classes}
   * (the Gradle {@code goTestUnit} task puts the {@code src} folders on its classpath itself).
   * {@code etendo-resources/} would NOT do: only Gradle sees it, the deployed web application
   * never does.
   */
  static final String RESOURCE = "ocr-config.json";

  private static final Logger log = LogManager.getLogger(OcrConfig.class);

  private final Map<String, OcrDocType> bySpec;
  private final boolean unavailable;

  private OcrConfig(Map<String, OcrDocType> bySpec, boolean unavailable) {
    this.bySpec = Collections.unmodifiableMap(bySpec);
    this.unavailable = unavailable;
  }

  /**
   * Whether the packaged configuration could not be loaded (missing from the classpath or
   * unreadable). That is a deployment fault, not "no spec supports OCR", and callers must say so
   * instead of answering as if the feature did not exist.
   *
   * @return {@code true} when the configuration is broken
   */
  public boolean isUnavailable() {
    return unavailable;
  }

  /** Lazily loads the packaged configuration once. */
  private static final class Holder {
    private static final OcrConfig INSTANCE = loadPackaged();

    private Holder() {
    }
  }

  /**
   * The packaged configuration.
   *
   * @return the shared instance, empty when the resource cannot be read
   */
  public static OcrConfig get() {
    return Holder.INSTANCE;
  }

  private static OcrConfig loadPackaged() {
    try {
      return load(OcrConfig.class, RESOURCE);
    } catch (IOException | JSONException | RuntimeException e) {
      log.error("OCR configuration {} is NOT available ({}): the document-reading tools are "
          + "withdrawn and /sws/neo/ocrconfig answers 500. It must be in WEB-INF/classes next to "
          + "OcrConfig.class; rebuild the module (smartbuild/compile).", RESOURCE,
          e.getClass().getSimpleName());
      return new OcrConfig(new LinkedHashMap<>(), true);
    }
  }

  /**
   * Reads a configuration resource.
   *
   * @param owner    the class whose package holds the resource
   * @param resource the resource name
   * @return the configuration
   * @throws IOException when the resource cannot be read
   * @throws JSONException when it is not the expected JSON
   * @throws IllegalStateException when the resource is not on the classpath
   */
  static OcrConfig load(Class<?> owner, String resource) throws IOException, JSONException {
    try (InputStream in = owner.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Resource " + resource + " is not on the classpath");
      }
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  /**
   * Parses a configuration text.
   *
   * @param json the text of {@value #RESOURCE}
   * @return the configuration
   * @throws JSONException when the text is not the expected JSON
   */
  public static OcrConfig parse(String json) throws JSONException {
    JSONArray docTypes = new JSONObject(json).getJSONArray("docTypes");
    Map<String, OcrDocType> parsed = new LinkedHashMap<>();
    for (int i = 0; i < docTypes.length(); i++) {
      JSONObject entry = docTypes.getJSONObject(i);
      List<String> keys = new ArrayList<>();
      JSONArray dataKeys = entry.getJSONArray("dataKeys");
      for (int k = 0; k < dataKeys.length(); k++) {
        keys.add(dataKeys.getString(k));
      }
      OcrDocType type = new OcrDocType(entry.getString("id"), entry.getString("toolName"),
          entry.getString("resolver"), entry.getString("question"),
          entry.getJSONObject("schema"), keys);
      parsed.put(type.getSpec(), type);
    }
    return new OcrConfig(parsed, false);
  }

  /**
   * The document type of a spec.
   *
   * @param spec the spec name
   * @return the type, empty when the spec has no OCR
   */
  public Optional<OcrDocType> forSpec(String spec) {
    return Optional.ofNullable(spec == null ? null : bySpec.get(spec));
  }

  /**
   * The specs that support OCR.
   *
   * @return their names, in file order
   */
  public List<String> specs() {
    return List.copyOf(bySpec.keySet());
  }
}
