/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.beans.steps.stepinfo.security.shared;

import static io.harness.annotations.dev.HarnessTeam.STO;
import static io.harness.beans.SwaggerConstants.INTEGER_CLASSPATH;
import static io.harness.beans.SwaggerConstants.STRING_CLASSPATH;
import static io.harness.yaml.schema.beans.SupportedPossibleFieldTypes.runtime;

import io.harness.annotations.dev.OwnedBy;
import io.harness.pms.yaml.ParameterField;
import io.harness.yaml.YamlSchemaTypes;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.annotations.ApiModelProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@OwnedBy(STO)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class STOYamlInstance {
  @ApiModelProperty(dataType = STRING_CLASSPATH, name = "access_id") protected ParameterField<String> accessId;

  @ApiModelProperty(dataType = STRING_CLASSPATH, name = "access_token") protected ParameterField<String> accessToken;

  @ApiModelProperty(dataType = STRING_CLASSPATH) protected ParameterField<String> domain;

  @ApiModelProperty(dataType = STRING_CLASSPATH) protected ParameterField<String> protocol;

  @YamlSchemaTypes(value = {runtime})
  @ApiModelProperty(dataType = INTEGER_CLASSPATH)
  protected ParameterField<Integer> port;

  @ApiModelProperty(dataType = STRING_CLASSPATH) protected ParameterField<String> path;
}
