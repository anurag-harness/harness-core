/*
 * Copyright 2023 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.gitsync.common.dtos;

import static io.harness.annotations.dev.HarnessTeam.PIPELINE;

import io.harness.annotations.dev.OwnedBy;
import io.harness.delegate.beans.connector.scm.bitbucket.BitbucketApiAccessDTO;
import io.harness.ng.userprofile.commons.SCMType;

import lombok.AccessLevel;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import lombok.experimental.SuperBuilder;

@OwnedBy(PIPELINE)
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
@SuperBuilder
public class BitbucketSCMDTO extends UserSourceCodeManagerDTO {
  BitbucketApiAccessDTO apiAccess;

  @Override
  public SCMType getType() {
    return SCMType.BITBUCKET;
  }
}
