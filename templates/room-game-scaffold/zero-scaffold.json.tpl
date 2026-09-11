{
  "schemaVersion": 1,
  "generator": "zero-codegen/project-scaffold",
  "projectName": __PROJECT_NAME_JSON__,
  "packageName": __PACKAGE_JSON__,
  "zeroVersion": __ZERO_VERSION_JSON__,
  "template": __TEMPLATE_NAME_JSON__,
  "templateDescription": __TEMPLATE_DESCRIPTION_JSON__,
  "useCase": __TEMPLATE_USE_CASE_JSON__,
  "protocolFile": __PROTOCOL_FILE_JSON__,
  "summaryPrefix": __SUMMARY_PREFIX_JSON__,
  "prototype": true,
  "connectsExternalMiddleware": __EXTERNAL_COMPONENTS__,
  "opensNetworkPorts": false,
  "runtimeProfile": "__RUNTIME_PROFILE__",
  "requiresExternalServices": __EXTERNAL_COMPONENTS__,
  "selectedComponents": [
__SELECTED_COMPONENTS_JSON__
  ],
  "selectedProviders": [
__SELECTED_PROVIDERS_JSON__
  ],
  "runtimeCapabilities": [
__RUNTIME_CAPABILITIES_JSON__
  ],
  "frameworkComponents": [
__FRAMEWORK_COMPONENTS_JSON__
  ],
  "documents": [
    "README.md",
    "BUSINESS_GUIDE.md",
    "COMPONENTS.md",
    "NEXT_STEPS.md"
  ],
  "verification": {
    "testCommand": "mvn -q clean test",
    "runCommand": "mvn -q exec:java"
  },
  "productionGap": __PRODUCTION_GAP_JSON__
}
