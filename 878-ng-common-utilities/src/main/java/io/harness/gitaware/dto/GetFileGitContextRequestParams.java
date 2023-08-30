/*
 * Copyright 2023 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.gitaware.dto;

import io.harness.EntityType;
import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

@OwnedBy(HarnessTeam.PIPELINE)
@Getter
@FieldDefaults(level = AccessLevel.PRIVATE)
@Builder
public class GetFileGitContextRequestParams {
  String repoName;
  String branchName;
  String commitId;
  String filePath;
  String connectorRef;
  boolean loadFromCache;
  EntityType entityType;
  boolean getOnlyFileContent;
}
