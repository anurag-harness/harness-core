/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.ngmigration.client;

import static io.harness.security.NextGenAuthenticationFilter.X_API_KEY;

import io.harness.NGCommonEntityConstants;
import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;
import io.harness.gitsync.beans.StoreType;
import io.harness.gitsync.sdk.GitSyncApiConstants;
import io.harness.ng.core.dto.ResponseDTO;
import io.harness.template.resources.beans.TemplateWrapperResponseDTO;

import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Query;

@OwnedBy(HarnessTeam.CDC)
public interface TemplateClient {
  @POST("templates")
  Call<ResponseDTO<TemplateWrapperResponseDTO>> createTemplate(@Header(X_API_KEY) String auth,
      @Query(NGCommonEntityConstants.ACCOUNT_KEY) String accountId,
      @Query(NGCommonEntityConstants.ORG_KEY) String orgId,
      @Query(NGCommonEntityConstants.PROJECT_KEY) String projectId, @Body RequestBody templateYaml,
      @Query(GitSyncApiConstants.STORE_TYPE) StoreType storeType);
}
