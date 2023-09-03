/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.ccm.views.service.impl;

import static io.harness.NGCommonEntityConstants.MONGODB_ID;
import static io.harness.ccm.views.helper.RuleExecutionType.INTERNAL;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.project;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.sort;

import io.harness.ccm.commons.entities.CCMTimeFilter;
import io.harness.ccm.views.dao.RuleDAO;
import io.harness.ccm.views.dao.RuleExecutionDAO;
import io.harness.ccm.views.dao.RuleSetDAO;
import io.harness.ccm.views.entities.Rule;
import io.harness.ccm.views.entities.RuleExecution;
import io.harness.ccm.views.entities.RuleExecution.RuleExecutionKeys;
import io.harness.ccm.views.entities.RuleSet;
import io.harness.ccm.views.helper.FilterValues;
import io.harness.ccm.views.helper.GovernanceRuleFilter;
import io.harness.ccm.views.helper.OverviewExecutionDetails;
import io.harness.ccm.views.helper.ResourceTypeCount;
import io.harness.ccm.views.helper.ResourceTypeCount.ResourceTypeCountkey;
import io.harness.ccm.views.helper.ResourceTypePotentialCost;
import io.harness.ccm.views.helper.ResourceTypePotentialCost.ResourceTypeCostKey;
import io.harness.ccm.views.helper.RuleExecutionFilter;
import io.harness.ccm.views.helper.RuleExecutionList;
import io.harness.ccm.views.helper.RuleSetFilter;
import io.harness.ccm.views.service.RuleExecutionService;
import io.harness.exception.InvalidRequestException;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.GroupOperation;
import org.springframework.data.mongodb.core.aggregation.MatchOperation;
import org.springframework.data.mongodb.core.aggregation.ProjectionOperation;
import org.springframework.data.mongodb.core.aggregation.SortOperation;
import org.springframework.data.mongodb.core.query.Criteria;

@Slf4j
@Singleton
public class RuleExecutionServiceImpl implements RuleExecutionService {
  @Inject private RuleExecutionDAO rulesExecutionDAO;
  @Inject private RuleSetDAO ruleSetDAO;
  @Inject private RuleDAO rulesDao;
  @Inject private MongoTemplate mongoTemplate;
  private final String POTENTIALCOST = "potentialCost";

  @Override
  public String save(RuleExecution rulesExecution) {
    return rulesExecutionDAO.save(rulesExecution);
  }

  @Override
  public RuleExecution get(String accountId, String uuid) {
    return rulesExecutionDAO.get(accountId, uuid);
  }
  @Override

  public List<RuleExecution> list(String accountId) {
    return rulesExecutionDAO.list(accountId);
  }

  @Override
  public RuleExecutionList filterExecution(RuleExecutionFilter rulesExecutionFilter) {
    return rulesExecutionDAO.filterExecution(rulesExecutionFilter);
  }

  @Override
  public FilterValues filterValue(String accountId) {
    FilterValues filterValues = FilterValues.builder().build();
    RuleSetFilter ruleSetFilter = RuleSetFilter.builder().build();
    List<RuleSet> ruleSet = ruleSetDAO.list(accountId, ruleSetFilter).getRuleSet();
    if (ruleSet != null) {
      HashMap<String, String> ruleSetsIds = new HashMap<>();
      for (RuleSet iterate : ruleSet) {
        ruleSetsIds.put(iterate.getUuid(), iterate.getName());
      }
      filterValues.setRuleSetIds(ruleSetsIds);
    }
    GovernanceRuleFilter governancePolicyFilter = GovernanceRuleFilter.builder().build();
    governancePolicyFilter.setAccountId(accountId);
    List<Rule> rules = rulesDao.list(governancePolicyFilter).getRules();
    if (rules != null) {
      HashMap<String, String> rulesIds = new HashMap<>();
      for (Rule iterate : rules) {
        rulesIds.put(iterate.getUuid(), iterate.getName());
      }
      filterValues.setRuleIds(rulesIds);
    }

    return filterValues;
  }

  @Override
  public OverviewExecutionDetails getOverviewExecutionDetails(
      String accountId, RuleExecutionFilter ruleExecutionFilter) {
    OverviewExecutionDetails overviewExecutionDetails =
        rulesExecutionDAO.getOverviewExecutionDetails(accountId, ruleExecutionFilter);
    overviewExecutionDetails.setTopResourceTypeExecution(getResourceTypeCount(accountId, ruleExecutionFilter));
    overviewExecutionDetails.setMonthlyRealizedSavings(getResourceActualCost(accountId, ruleExecutionFilter));
    return overviewExecutionDetails;
  }

  public Map<String, Double> getExecutionCostDetails(String accountId, RuleExecutionFilter ruleExecutionFilter) {
    return getResourcePotentialCost(accountId, ruleExecutionFilter);
  }

  public <T> AggregationResults<T> aggregate(Aggregation aggregation, Class<T> classToFillResultIn) {
    return mongoTemplate.aggregate(aggregation, RuleExecution.class, classToFillResultIn);
  }

  public Map<String, Double> getResourcePotentialCost(String accountId, RuleExecutionFilter ruleExecutionFilter) {
    Criteria criteria = Criteria.where(RuleExecutionKeys.accountId)
                            .is(accountId)
                            .and(RuleExecutionKeys.potentialSavings)
                            .ne(null)
                            .and(RuleExecutionKeys.executionType)
                            .is(INTERNAL);
    if (ruleExecutionFilter.getTime() != null) {
      for (CCMTimeFilter time : ruleExecutionFilter.getTime()) {
        switch (time.getOperator()) {
          case AFTER:
            criteria.and(RuleExecutionKeys.lastUpdatedAt).gte(time.getTimestamp());
            break;
          default:
            throw new InvalidRequestException("Operator not supported not supported for time fields");
        }
      }
    }
    MatchOperation matchStage = Aggregation.match(criteria);
    GroupOperation group =
        group(RuleExecutionKeys.resourceType).sum(RuleExecutionKeys.potentialSavings).as(ResourceTypeCostKey.cost);
    ProjectionOperation projectionStage =
        project().and(MONGODB_ID).as(ResourceTypeCostKey.resourceName).andInclude(ResourceTypeCostKey.cost);
    SortOperation sortStage = sort(Sort.by(ResourceTypeCostKey.cost));
    Map<String, Double> result = new HashMap<>();
    aggregate(newAggregation(matchStage, sortStage, group, projectionStage), ResourceTypePotentialCost.class)
        .getMappedResults()
        .forEach(resource
            -> result.put(
                resource.getResourceName() != null ? resource.getResourceName() : "others", resource.getCost()));
    log.info("result: {}", result);
    return result;
  }

  public List<Map> getResourceActualCost(String accountId, RuleExecutionFilter ruleExecutionFilter) {
    Criteria criteria = Criteria.where(RuleExecutionKeys.accountId)
                            .is(accountId)
                            .and(RuleExecutionKeys.realizedSavings)
                            .ne(null)
                            .and(RuleExecutionKeys.executionType)
                            .ne(INTERNAL);
    if (ruleExecutionFilter.getTime() != null) {
      for (CCMTimeFilter time : ruleExecutionFilter.getTime()) {
        switch (time.getOperator()) {
          case AFTER:
            criteria.and(RuleExecutionKeys.createdAt).gte(time.getTimestamp());
            break;
          default:
            throw new InvalidRequestException("Operator not supported not supported for time fields");
        }
      }
    }
    MatchOperation matchStage = Aggregation.match(criteria);
    ProjectionOperation projectionStage = Aggregation.project()
                                              .andExpression("dateToString('%Y-%m-%d', toDate("
                                                  + "$createdAt"
                                                  + "))")
                                              .as("formatted_day")
                                              .andInclude("realizedSavings");

    GroupOperation group = Aggregation.group("formatted_day").sum("realizedSavings").as("totalRealizedSavings");
    AggregationOperation[] stages = {matchStage, projectionStage, group};
    Aggregation aggregation = Aggregation.newAggregation(stages);

    List<Map> result = mongoTemplate.aggregate(aggregation, "governanceRuleExecution", Map.class).getMappedResults();
    log.info("getResourceActualCost: {}", result);
    return result;
  }

  public Map<String, Integer> getResourceTypeCount(String accountId, RuleExecutionFilter ruleExecutionFilter) {
    Criteria criteria = Criteria.where(RuleExecutionKeys.accountId)
                            .is(accountId)
                            .and(RuleExecutionKeys.resourceType)
                            .exists(true)
                            .and(RuleExecutionKeys.executionType)
                            .ne(INTERNAL);
    if (ruleExecutionFilter.getTime() != null) {
      for (CCMTimeFilter time : ruleExecutionFilter.getTime()) {
        switch (time.getOperator()) {
          case AFTER:
            criteria.and(RuleExecutionKeys.lastUpdatedAt).gte(time.getTimestamp());
            break;
          default:
            throw new InvalidRequestException("Operator not supported not supported for time fields");
        }
      }
    }
    MatchOperation matchStage = Aggregation.match(criteria);
    GroupOperation group = group(RuleExecutionKeys.resourceType).count().as(ResourceTypeCountkey.count);
    SortOperation sortStage = sort(Sort.by(ResourceTypeCountkey.count));

    ProjectionOperation projectionStage =
        project().and(MONGODB_ID).as(ResourceTypeCountkey.resourceName).andInclude(ResourceTypeCountkey.count);
    Map<String, Integer> result = new HashMap<>();
    aggregate(newAggregation(matchStage, sortStage, group, projectionStage), ResourceTypeCount.class)
        .getMappedResults()
        .forEach(resource -> result.put(resource.getResourceName(), resource.getCount()));
    log.info("result: {}", result);
    return result;
  }

  @Override
  public RuleExecutionList getRuleRecommendationDetails(String ruleRecommendationId, String accountId) {
    return rulesExecutionDAO.getRuleRecommendationDetails(ruleRecommendationId, accountId);
  }
}
