/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.cvng.servicelevelobjective.beans.slotargetspec;

import static io.harness.cvng.CVConstants.SLO_TARGET_TYPE;

import io.harness.cvng.servicelevelobjective.beans.SLOTargetType;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonSubTypes({
  @JsonSubTypes.Type(value = RollingSLOTargetSpec.class, name = "Rolling")
  , @JsonSubTypes.Type(value = CalenderSLOTargetSpec.class, name = "Calender"),
})
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = SLO_TARGET_TYPE, include = JsonTypeInfo.As.EXTERNAL_PROPERTY)
public abstract class SLOTargetSpec {
  @JsonIgnore public abstract SLOTargetType getType();
  @JsonIgnore public abstract boolean isErrorBudgetResetEnabled();
}
