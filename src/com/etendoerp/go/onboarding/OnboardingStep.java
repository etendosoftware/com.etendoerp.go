package com.etendoerp.go.onboarding;

public interface OnboardingStep {
  String name();
  void execute(OnboardingContext ctx) throws Exception;
}
