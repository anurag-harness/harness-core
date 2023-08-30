/*
 * Copyright 2023 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.idp.envvariable.repositories;

import io.harness.idp.envvariable.beans.entity.BackstageEnvVariableEntity;

import java.util.List;
import java.util.Optional;

public interface BackstageEnvVariableRepositoryCustom {
  BackstageEnvVariableEntity update(BackstageEnvVariableEntity backstageEnvVariableEntity);
  Optional<BackstageEnvVariableEntity> findByAccountIdentifierAndHarnessSecretIdentifier(
      String accountIdentifier, String harnessSecretIdentifier);
  List<BackstageEnvVariableEntity> findAllByAccountIdentifierAndMultipleEnvNames(
      String accountIdentifier, List<String> envNames);
  void deleteAllByAccountIdentifierAndEnvNames(String accountIdentifier, List<String> envName);
}
