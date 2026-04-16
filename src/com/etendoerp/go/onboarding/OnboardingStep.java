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

package com.etendoerp.go.onboarding;

/**
 * Contract for an individual onboarding action in the environment creation workflow.
 */
public interface OnboardingStep {

  /**
   * Returns the display name used in onboarding progress events.
   *
   * @return step name shown to the client while onboarding runs
   */
  String name();

  /**
   * Executes the onboarding action, updating the shared context as needed.
   *
   * @param ctx mutable onboarding context shared across the step chain
   * @throws OnboardingStepException when the step cannot complete successfully
   */
  void execute(OnboardingContext ctx) throws OnboardingStepException;
}
