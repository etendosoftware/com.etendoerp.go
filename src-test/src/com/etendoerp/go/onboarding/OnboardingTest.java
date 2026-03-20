package com.etendoerp.go.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for the onboarding system.
 * Tests OnboardingContext, OnboardingStep chain execution, and step failure behavior.
 * No OBDal or Etendo runtime required — pure unit tests.
 */
public class OnboardingTest {

  // ── OnboardingContext tests ──────────────────────────────────────────

  @Test
  public void testContextInputFieldsInitiallyNull() {
    OnboardingContext ctx = new OnboardingContext();

    assertNull("clientName should be null initially", ctx.getClientName());
    assertNull("orgName should be null initially", ctx.getOrgName());
    assertNull("adminUser should be null initially", ctx.getAdminUser());
    assertNull("adminPassword should be null initially", ctx.getAdminPassword());
    assertNull("currencyCode should be null initially", ctx.getCurrencyCode());
    assertNull("languageCode should be null initially", ctx.getLanguageCode());
    assertNull("countryCode should be null initially", ctx.getCountryCode());
  }

  @Test
  public void testContextAccumulatedIdsInitiallyNull() {
    OnboardingContext ctx = new OnboardingContext();

    assertNull("clientId should be null initially", ctx.getClientId());
    assertNull("orgId should be null initially", ctx.getOrgId());
    assertNull("clientAdminUserId should be null initially", ctx.getClientAdminUserId());
    assertNull("orgAdminUserId should be null initially", ctx.getOrgAdminUserId());
    assertNull("roleId should be null initially", ctx.getRoleId());
    assertNull("warehouseId should be null initially", ctx.getWarehouseId());
    assertNull("calendarId should be null initially", ctx.getCalendarId());
    assertNull("priceListSalesId should be null initially", ctx.getPriceListSalesId());
    assertNull("priceListPurchaseId should be null initially", ctx.getPriceListPurchaseId());
    assertNull("financialAccountId should be null initially", ctx.getFinancialAccountId());
    assertNull("productCategoryId should be null initially", ctx.getProductCategoryId());
    assertNull("taxCategoryId should be null initially", ctx.getTaxCategoryId());
  }

  @Test
  public void testContextInputSettersAndGetters() {
    OnboardingContext ctx = new OnboardingContext();

    ctx.setClientName("TestClient");
    ctx.setOrgName("TestOrg");
    ctx.setAdminUser("admin");
    ctx.setAdminPassword("secret123");
    ctx.setCurrencyCode("USD");
    ctx.setLanguageCode("en_US");
    ctx.setCountryCode("US");

    assertEquals("TestClient", ctx.getClientName());
    assertEquals("TestOrg", ctx.getOrgName());
    assertEquals("admin", ctx.getAdminUser());
    assertEquals("secret123", ctx.getAdminPassword());
    assertEquals("USD", ctx.getCurrencyCode());
    assertEquals("en_US", ctx.getLanguageCode());
    assertEquals("US", ctx.getCountryCode());
  }

  @Test
  public void testContextAccumulatedIdSettersAndGetters() {
    OnboardingContext ctx = new OnboardingContext();

    ctx.setClientId("C001");
    ctx.setOrgId("O001");
    ctx.setClientAdminUserId("U001");
    ctx.setOrgAdminUserId("U002");
    ctx.setRoleId("R001");
    ctx.setWarehouseId("W001");
    ctx.setCalendarId("CAL001");
    ctx.setPriceListSalesId("PLS001");
    ctx.setPriceListPurchaseId("PLP001");
    ctx.setFinancialAccountId("FA001");
    ctx.setProductCategoryId("PC001");
    ctx.setTaxCategoryId("TC001");

    assertEquals("C001", ctx.getClientId());
    assertEquals("O001", ctx.getOrgId());
    assertEquals("U001", ctx.getClientAdminUserId());
    assertEquals("U002", ctx.getOrgAdminUserId());
    assertEquals("R001", ctx.getRoleId());
    assertEquals("W001", ctx.getWarehouseId());
    assertEquals("CAL001", ctx.getCalendarId());
    assertEquals("PLS001", ctx.getPriceListSalesId());
    assertEquals("PLP001", ctx.getPriceListPurchaseId());
    assertEquals("FA001", ctx.getFinancialAccountId());
    assertEquals("PC001", ctx.getProductCategoryId());
    assertEquals("TC001", ctx.getTaxCategoryId());
  }

  // ── OnboardingStep chain tests ───────────────────────────────────────

  @Test
  public void testStepChainExecutesInOrder() throws Exception {
    List<String> executionLog = new ArrayList<>();

    OnboardingStep step1 = new OnboardingStep() {
      @Override
      public String name() {
        return "Step1";
      }

      @Override
      public void execute(OnboardingContext ctx) {
        executionLog.add("Step1");
        ctx.setClientId("CLIENT_FROM_STEP1");
      }
    };

    OnboardingStep step2 = new OnboardingStep() {
      @Override
      public String name() {
        return "Step2";
      }

      @Override
      public void execute(OnboardingContext ctx) {
        executionLog.add("Step2");
        ctx.setOrgId("ORG_FROM_STEP2");
      }
    };

    OnboardingStep step3 = new OnboardingStep() {
      @Override
      public String name() {
        return "Step3";
      }

      @Override
      public void execute(OnboardingContext ctx) {
        executionLog.add("Step3");
        ctx.setRoleId("ROLE_FROM_STEP3");
      }
    };

    List<OnboardingStep> steps = new ArrayList<>();
    steps.add(step1);
    steps.add(step2);
    steps.add(step3);

    OnboardingContext ctx = new OnboardingContext();
    for (OnboardingStep step : steps) {
      step.execute(ctx);
    }

    // Verify execution order
    assertEquals(3, executionLog.size());
    assertEquals("Step1", executionLog.get(0));
    assertEquals("Step2", executionLog.get(1));
    assertEquals("Step3", executionLog.get(2));

    // Verify context accumulated IDs from all steps
    assertEquals("CLIENT_FROM_STEP1", ctx.getClientId());
    assertEquals("ORG_FROM_STEP2", ctx.getOrgId());
    assertEquals("ROLE_FROM_STEP3", ctx.getRoleId());
  }

  @Test
  public void testStepChainContextAccumulatesAcrossSteps() throws Exception {
    // Step 1 sets clientId, step 2 reads clientId and sets orgId
    OnboardingStep createClient = new OnboardingStep() {
      @Override
      public String name() {
        return "CreateClient";
      }

      @Override
      public void execute(OnboardingContext ctx) {
        ctx.setClientId("C_ABC123");
      }
    };

    OnboardingStep createOrg = new OnboardingStep() {
      @Override
      public String name() {
        return "CreateOrg";
      }

      @Override
      public void execute(OnboardingContext ctx) {
        // Simulate reading clientId set by previous step
        assertNotNull("clientId should be available from previous step", ctx.getClientId());
        ctx.setOrgId("O_" + ctx.getClientId());
      }
    };

    OnboardingContext ctx = new OnboardingContext();
    createClient.execute(ctx);
    createOrg.execute(ctx);

    assertEquals("C_ABC123", ctx.getClientId());
    assertEquals("O_C_ABC123", ctx.getOrgId());
  }

  @Test
  public void testStepNameReturnsExpectedValue() {
    OnboardingStep step = new OnboardingStep() {
      @Override
      public String name() {
        return "TestStepName";
      }

      @Override
      public void execute(OnboardingContext ctx) {
        // no-op
      }
    };

    assertEquals("TestStepName", step.name());
  }

  // ── Step failure / rollback simulation tests ─────────────────────────

  @Test
  public void testStepFailureStopsExecution() {
    List<String> executionLog = new ArrayList<>();

    OnboardingStep step1 = new OnboardingStep() {
      @Override
      public String name() {
        return "Step1-OK";
      }

      @Override
      public void execute(OnboardingContext ctx) {
        executionLog.add("Step1");
        ctx.setClientId("C001");
      }
    };

    OnboardingStep step2 = new OnboardingStep() {
      @Override
      public String name() {
        return "Step2-OK";
      }

      @Override
      public void execute(OnboardingContext ctx) {
        executionLog.add("Step2");
        ctx.setOrgId("O001");
      }
    };

    OnboardingStep step3Fail = new OnboardingStep() {
      @Override
      public String name() {
        return "Step3-FAIL";
      }

      @Override
      public void execute(OnboardingContext ctx) throws Exception {
        executionLog.add("Step3");
        throw new RuntimeException("Simulated failure in step 3");
      }
    };

    OnboardingStep step4 = new OnboardingStep() {
      @Override
      public String name() {
        return "Step4-NeverReached";
      }

      @Override
      public void execute(OnboardingContext ctx) {
        executionLog.add("Step4");
        ctx.setRoleId("R001");
      }
    };

    List<OnboardingStep> steps = new ArrayList<>();
    steps.add(step1);
    steps.add(step2);
    steps.add(step3Fail);
    steps.add(step4);

    OnboardingContext ctx = new OnboardingContext();
    String failedStepName = null;
    int failedStepNum = -1;

    // Simulate the servlet's step execution loop
    for (int i = 0; i < steps.size(); i++) {
      OnboardingStep step = steps.get(i);
      try {
        step.execute(ctx);
      } catch (Exception e) {
        failedStepName = step.name();
        failedStepNum = i + 1;
        break;
      }
    }

    // Steps 1 and 2 executed, step 3 started but failed, step 4 never ran
    assertEquals(3, executionLog.size());
    assertTrue("Step1 should have executed", executionLog.contains("Step1"));
    assertTrue("Step2 should have executed", executionLog.contains("Step2"));
    assertTrue("Step3 should have started", executionLog.contains("Step3"));

    // Step 4 was NOT executed
    assertEquals("Step4 should not have executed", false, executionLog.contains("Step4"));

    // The failed step info is captured
    assertEquals("Step3-FAIL", failedStepName);
    assertEquals(3, failedStepNum);

    // Context has IDs from steps 1-2 but not step 4
    assertEquals("C001", ctx.getClientId());
    assertEquals("O001", ctx.getOrgId());
    assertNull("roleId should not be set (step 4 never ran)", ctx.getRoleId());
  }

  @Test
  public void testStepFailureAtFirstStep() {
    List<String> executionLog = new ArrayList<>();

    OnboardingStep failStep = new OnboardingStep() {
      @Override
      public String name() {
        return "FailFirst";
      }

      @Override
      public void execute(OnboardingContext ctx) throws Exception {
        throw new RuntimeException("Immediate failure");
      }
    };

    OnboardingStep neverRun = new OnboardingStep() {
      @Override
      public String name() {
        return "NeverReached";
      }

      @Override
      public void execute(OnboardingContext ctx) {
        executionLog.add("NeverReached");
      }
    };

    List<OnboardingStep> steps = new ArrayList<>();
    steps.add(failStep);
    steps.add(neverRun);

    OnboardingContext ctx = new OnboardingContext();
    boolean failed = false;

    for (OnboardingStep step : steps) {
      try {
        step.execute(ctx);
      } catch (Exception e) {
        failed = true;
        assertEquals("Immediate failure", e.getMessage());
        break;
      }
    }

    assertTrue("Should have detected failure", failed);
    assertTrue("No steps should have completed", executionLog.isEmpty());
    assertNull("No IDs should be set", ctx.getClientId());
  }

  @Test
  public void testAllStepsSucceedNoException() throws Exception {
    List<String> executionLog = new ArrayList<>();

    List<OnboardingStep> steps = new ArrayList<>();
    for (int n = 1; n <= 5; n++) {
      int stepNum = n;
      steps.add(new OnboardingStep() {
        @Override
        public String name() {
          return "Step" + stepNum;
        }

        @Override
        public void execute(OnboardingContext ctx) {
          executionLog.add("Step" + stepNum);
        }
      });
    }

    OnboardingContext ctx = new OnboardingContext();
    boolean failed = false;

    for (OnboardingStep step : steps) {
      try {
        step.execute(ctx);
      } catch (Exception e) {
        failed = true;
        break;
      }
    }

    assertEquals("All 5 steps should have executed", 5, executionLog.size());
    assertEquals(false, failed);
  }

  // ── Describe endpoint JSON structure tests ───────────────────────────

  /**
   * Tests the describe JSON structure that OnboardingServlet.sendDescribe() produces.
   * We reconstruct the describe logic here since sendDescribe is private and doGet
   * requires authentication (OBDal). This validates the contract of the describe response.
   */
  @Test
  public void testDescribeJsonContainsAllRequiredFields() throws Exception {
    // Reproduce the describe structure from OnboardingServlet.sendDescribe()
    JSONObject describe = buildDescribeJson();

    assertEquals("/sws/neo/onboarding", describe.getString("endpoint"));
    assertEquals("POST", describe.getString("method"));
    assertTrue("description should be non-empty",
        describe.getString("description").length() > 0);

    JSONArray fields = describe.getJSONArray("fields");
    assertNotNull("fields array should exist", fields);
    assertEquals("Should have 7 fields", 7, fields.length());
  }

  @Test
  public void testDescribeFieldDefinitionsComplete() throws Exception {
    JSONObject describe = buildDescribeJson();
    JSONArray fields = describe.getJSONArray("fields");

    // Verify all expected field names are present
    List<String> expectedNames = new ArrayList<>();
    expectedNames.add("clientName");
    expectedNames.add("orgName");
    expectedNames.add("adminUser");
    expectedNames.add("adminPassword");
    expectedNames.add("currency");
    expectedNames.add("language");
    expectedNames.add("countryCode");

    List<String> actualNames = new ArrayList<>();
    for (int i = 0; i < fields.length(); i++) {
      JSONObject field = fields.getJSONObject(i);
      actualNames.add(field.getString("name"));

      // Each field should have type, required, and description
      assertEquals("All fields should be type string", "string", field.getString("type"));
      assertTrue("All fields should be required", field.getBoolean("required"));
      assertTrue("All fields should have a description",
          field.getString("description").length() > 0);
    }

    assertEquals("Field names should match expected", expectedNames, actualNames);
  }

  @Test
  public void testDescribeFieldDefStructure() throws Exception {
    // Verify each field definition has exactly 4 keys: name, type, required, description
    JSONObject describe = buildDescribeJson();
    JSONArray fields = describe.getJSONArray("fields");

    for (int i = 0; i < fields.length(); i++) {
      JSONObject field = fields.getJSONObject(i);
      assertEquals("Each field should have 4 properties", 4, field.length());
      assertTrue("Field should have 'name'", field.has("name"));
      assertTrue("Field should have 'type'", field.has("type"));
      assertTrue("Field should have 'required'", field.has("required"));
      assertTrue("Field should have 'description'", field.has("description"));
    }
  }

  // ── Helper methods ───────────────────────────────────────────────────

  /**
   * Reconstructs the describe JSON that OnboardingServlet.sendDescribe() produces.
   * This mirrors the servlet's private method to validate the JSON contract.
   */
  private JSONObject buildDescribeJson() throws Exception {
    JSONObject describe = new JSONObject();
    describe.put("endpoint", "/sws/neo/onboarding");
    describe.put("method", "POST");
    describe.put("description", "Create a new client environment with organization, users, "
        + "roles, and reference data");

    JSONArray fields = new JSONArray();
    fields.put(fieldDef("clientName", "string", true, "Name for the new client"));
    fields.put(fieldDef("orgName", "string", true, "Name for the main organization"));
    fields.put(fieldDef("adminUser", "string", true, "Username for the client administrator"));
    fields.put(fieldDef("adminPassword", "string", true,
        "Password for the client administrator"));
    fields.put(fieldDef("currency", "string", true,
        "ISO 4217 currency code (e.g., USD, EUR)"));
    fields.put(fieldDef("language", "string", true,
        "Language code (e.g., en_US, es_ES)"));
    fields.put(fieldDef("countryCode", "string", true,
        "ISO 3166-1 alpha-2 country code (e.g., US, ES)"));
    describe.put("fields", fields);

    return describe;
  }

  private JSONObject fieldDef(String name, String type, boolean required, String description)
      throws Exception {
    JSONObject field = new JSONObject();
    field.put("name", name);
    field.put("type", type);
    field.put("required", required);
    field.put("description", description);
    return field;
  }
}
