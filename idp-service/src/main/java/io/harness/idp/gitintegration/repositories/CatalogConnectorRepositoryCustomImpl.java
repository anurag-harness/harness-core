/*
 * Copyright 2023 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.idp.gitintegration.repositories;

import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;
import io.harness.idp.gitintegration.entities.CatalogConnectorEntity;

import com.google.inject.Inject;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@AllArgsConstructor(access = AccessLevel.PACKAGE, onConstructor = @__({ @Inject }))
@OwnedBy(HarnessTeam.IDP)
public class CatalogConnectorRepositoryCustomImpl implements CatalogConnectorRepositoryCustom {
  private MongoTemplate mongoTemplate;
  @Override
  public CatalogConnectorEntity saveOrUpdate(CatalogConnectorEntity catalogConnectorEntity) {
    Criteria criteria = Criteria.where(CatalogConnectorEntity.CatalogConnectorKeys.accountIdentifier)
                            .is(catalogConnectorEntity.getAccountIdentifier())
                            .and(CatalogConnectorEntity.CatalogConnectorKeys.connectorProviderType)
                            .is(catalogConnectorEntity.getConnectorProviderType());
    CatalogConnectorEntity connector = findOneByAccountIdentifierAndProviderType(criteria);
    if (connector == null) {
      return mongoTemplate.save(catalogConnectorEntity);
    }
    Query query = new Query(criteria);
    Update update = buildUpdateQuery(catalogConnectorEntity);
    FindAndModifyOptions options = new FindAndModifyOptions().returnNew(true);
    return mongoTemplate.findAndModify(query, update, options, CatalogConnectorEntity.class);
  }

  @Override
  public CatalogConnectorEntity findLastUpdated(String accountIdentifier) {
    Query query =
        new Query(Criteria.where(CatalogConnectorEntity.CatalogConnectorKeys.accountIdentifier).is(accountIdentifier));
    query.with(Sort.by(Sort.Direction.DESC, CatalogConnectorEntity.CatalogConnectorKeys.lastUpdatedAt));
    query.limit(1);
    return mongoTemplate.findOne(query, CatalogConnectorEntity.class);
  }

  private CatalogConnectorEntity findOneByAccountIdentifierAndProviderType(Criteria criteria) {
    return mongoTemplate.findOne(Query.query(criteria), CatalogConnectorEntity.class);
  }

  private Update buildUpdateQuery(CatalogConnectorEntity catalogConnectorEntity) {
    Update update = new Update();
    update.set(CatalogConnectorEntity.CatalogConnectorKeys.identifier, catalogConnectorEntity.getIdentifier());
    update.set(CatalogConnectorEntity.CatalogConnectorKeys.connectorIdentifier,
        catalogConnectorEntity.getConnectorIdentifier());
    update.set(CatalogConnectorEntity.CatalogConnectorKeys.type, catalogConnectorEntity.getType());
    update.set(CatalogConnectorEntity.CatalogConnectorKeys.lastUpdatedAt, System.currentTimeMillis());
    update.set(CatalogConnectorEntity.CatalogConnectorKeys.host, catalogConnectorEntity.getHost());
    update.set(
        CatalogConnectorEntity.CatalogConnectorKeys.delegateSelectors, catalogConnectorEntity.getDelegateSelectors());
    return update;
  }
}
