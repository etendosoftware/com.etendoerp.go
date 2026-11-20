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


package com.etendoerp.go.featureflags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Keeps the three places that name a backend flag in agreement (ETP-4697): the
 * {@link GoFeatureFlags} {@code FLAG_*} constants, this module's {@code flags-registry.json}
 * contributions, and the {@code etendo.go.flags.*} / {@code ETGO_FLAG_*} property and environment
 * names that the sources document. Each is edited by hand, so each drifts: a constant with no
 * registry entry is invisible to the flag-debt scorer, a registry {@code flagKey} with no
 * constant is a flag nobody can evaluate, and a documented property for a key that does not exist
 * sends an operator to configure something inert.
 *
 * <p>A literal-regex backstop covers the one thing reflection cannot: an {@code isEnabled("…")}
 * call site that passes a string instead of a constant, which would evaluate a flag the registry
 * has never heard of.
 *
 * <p>The registry holds contributions only. A contribution names its flag with {@code flagKey};
 * once the flag is retired it drops the key, so a retired flag legitimately has no constant.
 *
 * @covers com.etendoerp.go.featureflags.GoFeatureFlags
 * @covers com.etendoerp.go.featureflags.PropertiesFeatureProvider
 */
class GoFeatureFlagsRegistrySyncTest {

  private static final String REGISTRY = "flags-registry.json";
  private static final String MODULE_DIR = "modules/com.etendoerp.go";
  private static final Pattern PROPERTY_NAME =
      Pattern.compile("etendo\\.go\\.flags\\.([a-z0-9][a-z0-9-]*[a-z0-9])");
  private static final Pattern ENV_NAME = Pattern.compile("ETGO_FLAG_[A-Z0-9_]*[A-Z0-9]");
  private static final Pattern LITERAL_CALL = Pattern.compile("isEnabled\\(\\s*\"([^\"]+)\"");

  private static Path moduleRoot;
  private static JSONObject registry;
  private static Set<String> constantKeys;
  private static Set<String> registryKeys;

  @BeforeAll
  static void load() throws Exception {
    moduleRoot = Files.exists(Paths.get(REGISTRY)) ? Paths.get(".") : Paths.get(MODULE_DIR);
    Path file = moduleRoot.resolve(REGISTRY);
    registry = new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    constantKeys = declaredConstants();
    registryKeys = contributionKeys(registry.getJSONArray("features"));
  }

  @Test
  void registryIsAV3ContributionRegistry() throws Exception {
    assertEquals(3, registry.getInt("version"));
    assertEquals("etendo-go", registry.getString("sonarProject"));
    JSONArray features = registry.getJSONArray("features");
    Set<String> ids = new TreeSet<>();
    for (int i = 0; i < features.length(); i++) {
      JSONObject feature = features.getJSONObject(i);
      String id = feature.getString("id");
      assertTrue(ids.add(id), "duplicate contribution id " + id);
      assertTrue(feature.has("flagKey") || feature.optBoolean("contribution", false),
          id + " is a header: headers live in etendo_schema_forge, this registry holds contributions");
      for (String forbidden : new String[] {"ttl", "ttlNote", "defaultValue", "flag"}) {
        assertTrue(!feature.has(forbidden),
            id + " declares \"" + forbidden + "\" — the header owns the flag's lifecycle");
      }
    }
  }

  @Test
  void everyFlagConstantHasAContribution() {
    Set<String> missing = new TreeSet<>(constantKeys);
    missing.removeAll(registryKeys);
    assertTrue(missing.isEmpty(),
        "GoFeatureFlags constants with no flagKey contribution in " + REGISTRY + ": " + missing);
  }

  @Test
  void everyContributionFlagKeyHasAConstant() {
    Set<String> orphans = new TreeSet<>(registryKeys);
    orphans.removeAll(constantKeys);
    assertTrue(orphans.isEmpty(),
        "flagKey in " + REGISTRY + " with no GoFeatureFlags FLAG_* constant (drop the key if the "
            + "flag is retired): " + orphans);
  }

  @Test
  void documentedPropertyNamesBelongToARegisteredFlag() throws IOException {
    Set<String> unknown = new TreeSet<>();
    for (String key : matches(PROPERTY_NAME, 1)) {
      if (!constantKeys.contains(key)) {
        unknown.add(PropertiesFeatureProvider.PROPERTY_PREFIX + key);
      }
    }
    assertTrue(unknown.isEmpty(),
        "property names in the sources that no FLAG_* constant backs: " + unknown);
  }

  @Test
  void documentedEnvironmentNamesBelongToARegisteredFlag() throws IOException {
    Set<String> expected = constantKeys.stream()
        .map(PropertiesFeatureProvider::toEnvName)
        .collect(Collectors.toCollection(TreeSet::new));
    Set<String> unknown = new TreeSet<>(matches(ENV_NAME, 0));
    unknown.removeAll(expected);
    assertTrue(unknown.isEmpty(),
        "environment variable names in the sources that no FLAG_* constant backs: " + unknown);
  }

  @Test
  void everyIsEnabledLiteralIsADeclaredFlagKey() throws IOException {
    Set<String> unknown = new TreeSet<>();
    for (String literal : matches(LITERAL_CALL, 1)) {
      if (!constantKeys.contains(literal)) {
        unknown.add(literal);
      }
    }
    assertTrue(unknown.isEmpty(),
        "isEnabled(\"…\") called with a string that is not a GoFeatureFlags constant — use the "
            + "constant, and register the flag: " + unknown);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static Set<String> declaredConstants() throws IllegalAccessException {
    Set<String> keys = new TreeSet<>();
    for (Field field : GoFeatureFlags.class.getDeclaredFields()) {
      int modifiers = field.getModifiers();
      if (field.getName().startsWith("FLAG_") && Modifier.isStatic(modifiers)
          && Modifier.isFinal(modifiers) && field.getType() == String.class) {
        keys.add((String) field.get(null));
      }
    }
    assertTrue(!keys.isEmpty(), "found no FLAG_* constants — did GoFeatureFlags change shape?");
    return keys;
  }

  private static Set<String> contributionKeys(JSONArray features) throws Exception {
    Set<String> keys = new TreeSet<>();
    for (int i = 0; i < features.length(); i++) {
      JSONObject feature = features.getJSONObject(i);
      if (feature.has("flagKey")) {
        assertTrue(keys.add(feature.getString("flagKey")),
            "flagKey declared twice: " + feature.getString("flagKey"));
      }
    }
    return keys;
  }

  /** Group {@code group} of every match of {@code pattern} in the module's Java sources. */
  private static Set<String> matches(Pattern pattern, int group) throws IOException {
    Set<String> found = new TreeSet<>();
    List<Path> sources = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(moduleRoot.resolve("src"))) {
      walk.filter(path -> path.toString().endsWith(".java"))
          // The provider documents the convention with placeholder keys (my-flag, …).
          .filter(path -> !path.getFileName().toString().equals("PropertiesFeatureProvider.java"))
          .forEach(sources::add);
    }
    for (Path source : sources) {
      Matcher matcher = pattern.matcher(new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
      while (matcher.find()) {
        found.add(matcher.group(group));
      }
    }
    return found;
  }
}
