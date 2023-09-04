/*
 * Copyright 2023 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.cvng.servicelevelobjective.beans.slospec;

import java.util.List;

public class WeightedAverageCompositeSLOEvaluator extends CompositeSLOEvaluator {
  @Override
  public Double evaluate(List<Double> weightage, List<Integer> sliValues) {
    List<Double> sliWithWeightage = getSLOValuesOfIndividualSLIs(weightage, sliValues);
    return sliWithWeightage.stream().mapToDouble(Double::doubleValue).sum();
  }
}
