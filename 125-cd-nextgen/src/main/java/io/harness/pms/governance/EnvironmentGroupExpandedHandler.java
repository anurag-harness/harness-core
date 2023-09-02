/*
 * Copyright 2023 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.pms.governance;

import io.harness.cdng.envGroup.beans.EnvironmentGroupEntity;
import io.harness.cdng.envGroup.services.EnvironmentGroupService;
import io.harness.cdng.envgroup.yaml.EnvironmentGroupMetadata;
import io.harness.cdng.envgroup.yaml.EnvironmentGroupMetadata.environmentGroupMetadataKeys;
import io.harness.cdng.envgroup.yaml.EnvironmentGroupYaml.environmentGroupYamlKeys;
import io.harness.exception.InvalidRequestException;
import io.harness.pms.contracts.governance.ExpansionPlacementStrategy;
import io.harness.pms.contracts.governance.ExpansionRequestMetadata;
import io.harness.pms.sdk.core.governance.ExpansionResponse;
import io.harness.pms.sdk.core.governance.JsonExpansionHandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EnvironmentGroupExpandedHandler implements JsonExpansionHandler {
  @Inject private EnvironmentGroupService environmentGroupService;
  @Inject private EnvironmentExpansionUtils utils;
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public ExpansionResponse expand(JsonNode fieldValue, ExpansionRequestMetadata metadata, String fqn) {
    if (!fieldValue.isObject()) {
      return ExpansionResponse.builder().success(false).errorMessage("field value is not an object").build();
    }

    final JsonNode metadataNode = fieldValue.get("metadata");

    EnvironmentGroupMetadata environmentGroupMetadata = EnvironmentGroupMetadata.builder().build();
    if (metadataNode != null && metadataNode.isObject()) {
      try {
        environmentGroupMetadata = mapper.readValue(metadataNode.toString(), EnvironmentGroupMetadata.class);
      } catch (JsonProcessingException e) {
        log.warn("Failed to parse environment group metadata field. skipping it");
      }
    }

    final JsonNode envGroupRefNode = fieldValue.get(environmentGroupYamlKeys.envGroupRef);
    if (envGroupRefNode == null) {
      return ExpansionResponse.builder()
          .success(false)
          .errorMessage("environmentGroupRef value is not present in the yaml")
          .build();
    }

    final JsonNode deployToAllNode = fieldValue.get(environmentGroupYamlKeys.deployToAll);
    Boolean deployToAll = null;
    if (deployToAllNode != null && deployToAllNode.isBoolean()) {
      deployToAll = deployToAllNode.asBoolean();
    }

    if (!envGroupRefNode.isTextual()) {
      return ExpansionResponse.builder()
          .success(false)
          .errorMessage("environmentGroupRef value is not a text field in the yaml")
          .build();
    }

    final String envGroupRef = envGroupRefNode.asText();

    Optional<EnvironmentGroupEntity> environmentGroupEntity = environmentGroupService.get(
        metadata.getAccountId(), metadata.getOrgId(), metadata.getProjectId(), envGroupRef, false);

    if (environmentGroupEntity.isEmpty()) {
      return ExpansionResponse.builder()
          .success(false)
          .errorMessage(String.format("Environment group ref %s not found", envGroupRef))
          .build();
    }

    try {
      final Map<String, Object> metadataMap =
          environmentGroupMetadata != null && environmentGroupMetadata.getParallel() != null
          ? Map.of(environmentGroupMetadataKeys.parallel, environmentGroupMetadata.getParallel())
          : Map.of();
      final EnvGroupExpandedValue value = EnvGroupExpandedValue.builder()
                                              .name(environmentGroupEntity.get().getName())
                                              .identifier(environmentGroupEntity.get().getIdentifier())
                                              .environments(generateEnvironmentsExpansion(metadata, fieldValue))
                                              .metadata(metadataMap)
                                              .deployToAll(deployToAll)
                                              .build();
      return ExpansionResponse.builder()
          .key(value.getKey())
          .value(value)
          .success(true)
          .placement(ExpansionPlacementStrategy.REPLACE)
          .build();
    } catch (Exception ex) {
      return ExpansionResponse.builder().success(false).errorMessage(ex.getMessage()).build();
    }
  }

  private List<SingleEnvironmentExpandedValue> generateEnvironmentsExpansion(
      ExpansionRequestMetadata metadata, JsonNode fieldValue) {
    final JsonNode environments = fieldValue.get(environmentGroupYamlKeys.environments);
    if (!environments.isArray()) {
      throw new InvalidRequestException("environments field is not an array");
    }

    final List<SingleEnvironmentExpandedValue> values = new ArrayList<>();
    environments.forEach(
        environmentNode -> values.add(utils.toSingleEnvironmentExpandedValue(metadata, environmentNode)));
    return values;
  }
}
