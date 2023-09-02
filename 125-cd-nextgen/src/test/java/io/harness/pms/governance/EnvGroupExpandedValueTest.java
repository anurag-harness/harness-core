/*
 * Copyright 2023 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.pms.governance;

import static org.assertj.core.api.Assertions.assertThat;

import io.harness.CategoryTest;
import io.harness.category.element.UnitTests;
import io.harness.cdng.infra.yaml.K8SDirectInfrastructure;
import io.harness.ng.core.environment.beans.EnvironmentType;
import io.harness.ng.core.infrastructure.InfrastructureType;
import io.harness.pms.yaml.ParameterField;
import io.harness.rule.Owner;
import io.harness.rule.OwnerRule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.io.IOUtils;
import org.junit.Test;
import org.junit.experimental.categories.Category;

public class EnvGroupExpandedValueTest extends CategoryTest {
  @Test
  @Owner(developers = OwnerRule.YOGESH)
  @Category(UnitTests.class)
  public void toJson() throws IOException {
    String connectorNodeJson = IOUtils.resourceToString(
        "governance/connectorNode.json", StandardCharsets.UTF_8, this.getClass().getClassLoader());

    JsonNode connectorNode = new ObjectMapper().readTree(connectorNodeJson);
    EnvGroupExpandedValue value =
        EnvGroupExpandedValue.builder()
            .name("wave1")
            .identifier("xyz")
            .environments(
                List.of(SingleEnvironmentExpandedValue.builder()
                            .name("my env")
                            .identifier("my_env")
                            .projectIdentifier("proj")
                            .orgIdentifier("org")
                            .type(EnvironmentType.Production)
                            .infrastructures(List.of(
                                InfrastructureExpandedValue.builder()
                                    .infrastructureDefinition(
                                        InfrastructureValue.builder()
                                            .type(InfrastructureType.KUBERNETES_DIRECT)
                                            .spec(K8SDirectInfrastructure.builder()
                                                      .namespace(ParameterField.createValueField("default"))
                                                      .connectorRef(ParameterField.createValueField("k8s_connector"))
                                                      .releaseName(ParameterField.createValueField("release_name"))
                                                      .build())
                                            .build())
                                    .infrastructureConnectorNode(connectorNode)
                                    .build()))
                            .build()))
            .build();
    String expectedJson = IOUtils.resourceToString(
        "governance/expected/environmentgroup_expected.json", StandardCharsets.UTF_8, this.getClass().getClassLoader());
    assertThat(value.toJson()).isEqualTo(expectedJson);
    assertThat(value.getKey()).isEqualTo("environmentGroup");
  }
}
