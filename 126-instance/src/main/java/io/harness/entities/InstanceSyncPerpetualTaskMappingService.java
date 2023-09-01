/*
 * Copyright 2023 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Shield 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/06/PolyForm-Shield-1.0.0.txt.
 */

package io.harness.entities;

import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;
import io.harness.dtos.InstanceSyncPerpetualTaskMappingDTO;

import java.util.Optional;
import javax.validation.constraints.NotEmpty;

@OwnedBy(HarnessTeam.CDP)
public interface InstanceSyncPerpetualTaskMappingService {
  InstanceSyncPerpetualTaskMappingDTO save(InstanceSyncPerpetualTaskMappingDTO instanceSyncPerpetualTaskMapping);
  boolean delete(String accountId, String perpetualTaskId);

  Optional<InstanceSyncPerpetualTaskMapping> findByConnectorRef(
      @NotEmpty String accountIdentifier, String orgIdentifier, String projectIdentifier, @NotEmpty String connectorId);
}
