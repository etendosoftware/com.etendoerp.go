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

package com.etendoerp.go.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * ETP-5455 — structural guards for the "one authentication pipeline per surface" rule.
 *
 * <p>Every defect ETP-5455 fixed had the same shape: a servlet grew its own copy of "credential →
 * identity", and a rule added later reached some copies and not others. A behavioural test can only
 * pin the copies that exist today; these guards stop the next copy from being written.
 *
 * <ul>
 *   <li><b>G-01</b> — outside {@code com.etendoerp.go.auth}, only the files listed in
 *   {@link #ALLOWED_CREDENTIAL_READERS} may read an inbound credential (the {@code Authorization}
 *   header, a JWT decode, an OAuth2 token validation), each with an exact allowance. It is a
 *   ratchet: a new read fails, and a removed one fails too until the allowance is lowered, so the
 *   list can only shrink. Outbound clients that SET an Authorization header (Stripe, Jira,
 *   Context7) are not reads and are not matched.</li>
 *   <li><b>G-02</b> — every servlet mapped under an environment surface (discovered from
 *   {@code AD_MODEL_OBJECT_MAPPING.xml}, so a newly mapped servlet is covered automatically)
 *   authenticates through the shared pipeline.</li>
 *   <li><b>G-03</b> — inside {@code EtendoGoJwtServlet}, the Bearer header is read only by the
 *   account surface's single resolver and the three documented exceptions.</li>
 * </ul>
 *
 * <p>Comments are blanked before matching: prose that merely names these calls is not a read
 * (and deleting the comment is never the fix).
 */
class AuthenticationEntryPointGuardTest {

  private static final String MODULE_DIR = "modules/com.etendoerp.go";
  private static final String SOURCE_ROOT = "src/com/etendoerp/go";
  private static final String SOURCEDATA = "src-db/database/sourcedata";

  private static final Pattern CREDENTIAL_READ = Pattern.compile(
      "getHeader\\(\\s*(\"Authorization\"|[A-Z_]*AUTH[A-Z_]*)\\s*\\)"
          + "|SecureWebServicesUtils\\s*\\.\\s*decodeToken\\("
          + "|OAuth2Filter\\s*\\.\\s*validateToken\\(");

  /**
   * G-01 allowance: path under {@code src/com/etendoerp/go} → exact number of inbound credential
   * reads, with the reason it is not the shared pipeline. Lower a number when a read goes away;
   * never raise one to make a new read pass — route it through {@link EnvironmentRequestAuthenticator}
   * (environment surfaces) or {@code EtendoGoJwtServlet#resolveAccount} (account surface) instead.
   */
  private static final Map<String, Allowance> ALLOWED_CREDENTIAL_READERS = new LinkedHashMap<>();

  static {
    allow("rest/EtendoGoJwtServlet.java", 3,
        "the account surface's single resolver (extractBearerToken), the tenant claims of an "
            + "already-resolved legacy JWT (resolveTenantSession), and decoding a JWT this class "
            + "just minted to copy its context into the session (handleSessionEnvironment)");
    allow("rest/EtendoGoJwtDalHelper.java", 1,
        "the wide account lookup decodes an environment JWT; only resolveAccount reaches it");
    allow("mcp/McpServlet.java", 3,
        "the MCP protocol endpoint authenticates external OAuth2 clients, not the SPA user");
    allow("oauth2/OAuth2Filter.java", 1,
        "the OAuth2 protocol filter validates client-credentials tokens for /sws/mcp and /mcp");
    allow("oauth2/OAuth2RequestAuthenticator.java", 1,
        "/oauth2/authorize: cookie first, then the legacy JWT carried in the authorize BODY, "
            + "behind the legacy kill switch");
    allow("portal/PortalServlet.java", 1,
        "the customer portal authenticates a portal access token, not a GO user");
    allow("schemaforge/webhooks/SFRefreshToken.java", 1,
        "decodes the token it has just issued, to echo its claims; it authenticates nothing");
    allow("apps/AppsServlet.java", 2,
        "spike surface /sws/apps, explicitly out of ETP-5455's scope (decision 3)");
  }

  /** Mapping prefixes that are environment surfaces and must use the shared pipeline (G-02). */
  private static final List<String> ENVIRONMENT_SURFACES = Arrays.asList(
      "/sws/neo/", "/sws/report-selectors/", "/sws/survey-config/", "/sws/support/", "/oauth2/");

  /** Mappings under those prefixes that are deliberately unauthenticated, with the reason. */
  private static final Map<String, String> PUBLIC_MAPPINGS = Map.of(
      "/sws/neo/currency-format",
      "pure UI formatting reference data, no authentication by design");

  /** What proves a servlet authenticates through the shared pipeline. */
  private static final List<String> PIPELINE_MARKERS = Arrays.asList(
      "EnvironmentRequestAuthenticator", "JwtAuthUtils.authenticateOrFail",
      "NeoServletSupport.authenticate(", "new NeoAuthenticator(");

  /** A class member declaration line (2-space indent): modifiers, return type, name, "(". */
  private static final Pattern METHOD_DECLARATION = Pattern.compile(
      "^  (?:(?:private|public|protected|static|final|synchronized)\\s+)+"
          + "[\\w<>\\[\\], .?]+?\\s+(\\w+)\\s*\\(");

  /** G-03: the only methods of EtendoGoJwtServlet that may read the Bearer header. */
  private static final Set<String> BEARER_READERS_IN_ACCOUNT_SERVLET = new TreeSet<>(Arrays.asList(
      "hasAnyCredential", "resolveAccount", "resolveTenantSession", "handleEnvironmentLogin"));

  // ============================== G-01 ==============================

  @Test
  void onlyTheAllowedFilesReadAnInboundCredentialOutsideTheAuthPackage() throws IOException {
    Map<String, Integer> actual = new TreeMap<>();
    for (Path file : javaFiles()) {
      String relative = sourceRoot().relativize(file).toString().replace('\\', '/');
      if (relative.startsWith("auth/")) {
        continue;
      }
      int reads = count(CREDENTIAL_READ, blankComments(read(file)));
      if (reads > 0) {
        actual.put(relative, reads);
      }
    }

    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : actual.entrySet()) {
      Allowance allowance = ALLOWED_CREDENTIAL_READERS.get(entry.getKey());
      if (allowance == null) {
        problems.add(entry.getKey() + " reads an inbound credential " + entry.getValue()
            + " time(s): authenticate through EnvironmentRequestAuthenticator or "
            + "EtendoGoJwtServlet#resolveAccount instead of reading it here");
      } else if (entry.getValue() > allowance.reads) {
        problems.add(entry.getKey() + " now reads " + entry.getValue() + " credential(s), "
            + "allowance is " + allowance.reads + " — a new read must go through the pipeline");
      } else if (entry.getValue() < allowance.reads) {
        problems.add(entry.getKey() + " now reads " + entry.getValue() + ", allowance is "
            + allowance.reads + " — lower the allowance so the list keeps shrinking");
      }
    }
    for (String allowed : ALLOWED_CREDENTIAL_READERS.keySet()) {
      if (!actual.containsKey(allowed)) {
        problems.add(allowed + " no longer reads any credential — remove it from the allowance");
      }
    }
    assertTrue(problems.isEmpty(), String.join("\n", problems));
  }

  // ============================== G-02 ==============================

  @Test
  void everyServletMappedUnderAnEnvironmentSurfaceUsesTheSharedPipeline() throws IOException {
    Map<String, String> servletByMapping = servletByMapping();
    List<String> inScope = servletByMapping.keySet().stream()
        .filter(mapping -> ENVIRONMENT_SURFACES.stream().anyMatch(
            prefix -> mapping.startsWith(prefix) || mapping.equals(prefix + "*")))
        .filter(mapping -> !PUBLIC_MAPPINGS.containsKey(mapping))
        .sorted().collect(Collectors.toList());
    assertFalse(inScope.isEmpty(), "no environment-surface mapping found — the XML parse is broken");

    List<String> problems = new ArrayList<>();
    for (String mapping : inScope) {
      String className = servletByMapping.get(mapping);
      Path source = sourceOf(className);
      if (!Files.exists(source)) {
        problems.add(mapping + " → " + className + ": class not found in this module");
        continue;
      }
      String code = blankComments(read(source));
      if (PIPELINE_MARKERS.stream().noneMatch(code::contains)) {
        problems.add(mapping + " → " + className
            + " authenticates without the shared EnvironmentRequestAuthenticator pipeline");
      }
    }
    assertTrue(problems.isEmpty(), String.join("\n", problems));
  }

  /** The guard would pass vacuously if it scanned nothing: pin that it sees the known servlets. */
  @Test
  void theEnvironmentSurfaceDiscoveryFindsTheKnownServlets() throws IOException {
    Map<String, String> servletByMapping = servletByMapping();
    assertEquals("com.etendoerp.go.schemaforge.NeoServlet", servletByMapping.get("/sws/neo/*"));
    assertEquals("com.etendoerp.go.schemaforge.ReportSelectorsServlet",
        servletByMapping.get("/sws/report-selectors/*"));
    assertEquals("com.etendoerp.go.oauth2.OAuth2Servlet", servletByMapping.get("/oauth2/*"));
  }

  // ============================== G-03 ==============================

  @Test
  void theAccountServletReadsTheBearerOnlyFromItsSingleResolver() throws IOException {
    String code = blankComments(read(sourceRoot().resolve("rest/EtendoGoJwtServlet.java")));
    List<String> problems = new ArrayList<>();
    int calls = 0;
    String enclosing = "<unknown>";
    // Line-based on purpose: class members are declared at a 2-space indent in this file, and a
    // signature regex over the whole 5k-line source backtracks catastrophically.
    for (String line : code.split("\n")) {
      Matcher declaration = METHOD_DECLARATION.matcher(line);
      if (declaration.find()) {
        enclosing = declaration.group(1);
      }
      if (line.contains("extractBearerToken(request)")) {
        calls++;
        if (!BEARER_READERS_IN_ACCOUNT_SERVLET.contains(enclosing)) {
          problems.add("extractBearerToken(request) called from " + enclosing
              + " — resolve the account through resolveAccount(request, response, requirement)");
        }
      }
    }
    assertTrue(calls > 0, "no extractBearerToken call found — the guard no longer sees the servlet");
    assertTrue(problems.isEmpty(), String.join("\n", problems));
  }

  // ============================== plumbing ==============================

  private static void allow(String path, int reads, String reason) {
    ALLOWED_CREDENTIAL_READERS.put(path, new Allowance(reads, reason));
  }

  private static final class Allowance {
    private final int reads;
    @SuppressWarnings("unused") // documentation for whoever edits the list
    private final String reason;

    private Allowance(int reads, String reason) {
      this.reads = reads;
      this.reason = reason;
    }
  }

  /** Resolves the module whether tests run from the module root or the workspace root. */
  private static Path moduleRoot() {
    Path here = Paths.get(".");
    if (Files.isDirectory(here.resolve(SOURCE_ROOT))) {
      return here;
    }
    return Paths.get(MODULE_DIR);
  }

  private static Path sourceRoot() {
    Path root = moduleRoot().resolve(SOURCE_ROOT);
    assertTrue(Files.isDirectory(root), "source root not found: " + root.toAbsolutePath());
    return root;
  }

  private static Path sourceOf(String className) {
    return moduleRoot().resolve("src").resolve(className.replace('.', '/') + ".java");
  }

  private static List<Path> javaFiles() throws IOException {
    try (Stream<Path> walk = Files.walk(sourceRoot())) {
      return walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
    }
  }

  private static String read(Path file) throws IOException {
    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
  }

  /** Blanks block and line comments, preserving offsets. */
  static String blankComments(String source) {
    StringBuilder out = new StringBuilder(source);
    Matcher block = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(source);
    while (block.find()) {
      for (int i = block.start(); i < block.end(); i++) {
        if (out.charAt(i) != '\n') {
          out.setCharAt(i, ' ');
        }
      }
    }
    Matcher line = Pattern.compile("//[^\n]*").matcher(out.toString());
    while (line.find()) {
      for (int i = line.start(); i < line.end(); i++) {
        out.setCharAt(i, ' ');
      }
    }
    return out.toString();
  }

  private static int count(Pattern pattern, String text) {
    Matcher matcher = pattern.matcher(text);
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  /** mapping name → servlet class, joined over AD_MODEL_OBJECT_ID. */
  private static Map<String, String> servletByMapping() throws IOException {
    Path sourcedata = moduleRoot().resolve(SOURCEDATA);
    Map<String, String> classById = new HashMap<>();
    for (String record : records(read(sourcedata.resolve("AD_MODEL_OBJECT.xml")), "AD_MODEL_OBJECT")) {
      String id = field(record, "AD_MODEL_OBJECT_ID");
      String className = field(record, "CLASSNAME");
      if (id != null && className != null) {
        classById.put(id, className);
      }
    }
    Map<String, String> byMapping = new TreeMap<>();
    for (String record : records(read(sourcedata.resolve("AD_MODEL_OBJECT_MAPPING.xml")),
        "AD_MODEL_OBJECT_MAPPING")) {
      String mapping = field(record, "MAPPINGNAME");
      String className = classById.get(field(record, "AD_MODEL_OBJECT_ID"));
      if (mapping != null && className != null && !"N".equals(field(record, "ISACTIVE"))) {
        byMapping.put(mapping, className);
      }
    }
    return byMapping;
  }

  private static List<String> records(String xml, String tag) {
    List<String> records = new ArrayList<>();
    Matcher matcher = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL)
        .matcher(xml);
    while (matcher.find()) {
      records.add(matcher.group(1));
    }
    return records;
  }

  private static String field(String record, String name) {
    Matcher matcher = Pattern.compile("<" + name + "><!\\[CDATA\\[(.*?)\\]\\]></" + name + ">")
        .matcher(record);
    return matcher.find() ? matcher.group(1) : null;
  }
}
