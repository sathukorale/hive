/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.ppd;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.hive.ql.exec.Operator;
import org.apache.hadoop.hive.ql.lib.NodeProcessorCtx;
import org.apache.hadoop.hive.ql.plan.ExprNodeDesc;
import org.apache.hadoop.hive.ql.plan.OperatorDesc;

/**
 * Context for Expression Walker for determining predicate pushdown candidates
 * It contains a ExprInfo object for each expression that is processed.
 */
public class ExprWalkerInfo implements NodeProcessorCtx {

  /** Information maintained for an expr while walking an expr tree. */
  protected class ExprInfo {
    /**
     * true if expr rooted at this node doesn't contain more than one table.
     * alias
     */
    protected boolean isCandidate = false;
    /** alias that this expression refers to. */
    protected String alias = null;
    /** new expr for this expression. */
    protected ExprNodeDesc convertedExpr = null;


  }

  protected static final Logger LOG = LoggerFactory.getLogger(OpProcFactory.class.getName());
  private Operator<? extends OperatorDesc> op = null;

  /**
   * Values the expression sub-trees (predicates) that can be pushed down for
   * root expression tree. Since there can be more than one alias in an
   * expression tree, this is a map from the alias to predicates.
   */
  private final Map<String, List<ExprNodeDesc>> pushdownPreds;

  /**
   * Values the expression sub-trees (predicates) that can not be pushed down for
   * root expression tree. Since there can be more than one alias in an
   * expression tree, this is a map from the alias to predicates.
   */
  private final Map<String, List<ExprNodeDesc>> nonFinalPreds;

  /**
   * this map contains a expr infos. Each key is a node in the expression tree
   * and the information for each node is the value which is used while walking
   * the tree by its parent.
   */
  private final Map<ExprNodeDesc, ExprInfo> exprInfoMap;

  /**
   * This is a map from a new pushdown expressions generated by the ExprWalker
   * to the old pushdown expression that it originated from. For example, if
   * an output column of the current operator is _col0, which comes from an
   * input column _col1, this would map the filter "Column[_col1]=2" to
   * "Column[_col0]=2" ("Column[_col1]=2" is new because we move from children
   * operators to parents in PPD)
   */
  private final Map<ExprNodeDesc, ExprNodeDesc> newToOldExprMap;

  private boolean isDeterministic = true;

  public ExprWalkerInfo() {
    pushdownPreds = new HashMap<String, List<ExprNodeDesc>>();
    nonFinalPreds = new HashMap<String, List<ExprNodeDesc>>();
    exprInfoMap = new IdentityHashMap<ExprNodeDesc, ExprInfo>();
    newToOldExprMap = new IdentityHashMap<ExprNodeDesc, ExprNodeDesc>();
  }

  public ExprWalkerInfo(Operator<? extends OperatorDesc> op) {
    this.op = op;

    pushdownPreds = new HashMap<String, List<ExprNodeDesc>>();
    exprInfoMap = new IdentityHashMap<ExprNodeDesc, ExprInfo>();
    nonFinalPreds = new HashMap<String, List<ExprNodeDesc>>();
    newToOldExprMap = new IdentityHashMap<ExprNodeDesc, ExprNodeDesc>();
  }

  /**
   * @return the op of this expression.
   */
  public Operator<? extends OperatorDesc> getOp() {
    return op;
  }

  /**
   * @return the new expression to old expression map
   */
  public Map<ExprNodeDesc, ExprNodeDesc> getNewToOldExprMap() {
    return newToOldExprMap;
  }

  /**
   * Get additional info for a given expression node
   */
  public ExprInfo getExprInfo(ExprNodeDesc expr) {
    return exprInfoMap.get(expr);
  }

  /**
   * Get additional info for a given expression node if it
   * exists, or create a new one and store it if it does not
   */
  public ExprInfo addExprInfo(ExprNodeDesc expr) {
    ExprInfo exprInfo = new ExprInfo();
    exprInfoMap.put(expr, exprInfo);
    return exprInfo;
  }

  /**
   * Get additional info for a given expression node if it
   * exists, or create a new one and store it if it does not
   */
  public ExprInfo addOrGetExprInfo(ExprNodeDesc expr) {
    ExprInfo exprInfo = exprInfoMap.get(expr);
    if (exprInfo == null) {
      exprInfo = new ExprInfo();
      exprInfoMap.put(expr, exprInfo);
    }
    return exprInfo;
  }

  public void addFinalCandidate(String alias, ExprNodeDesc expr) {
    List<ExprNodeDesc> predicates = getPushdownPreds(alias);
    for (ExprNodeDesc curPred: predicates) {
      if (curPred.isSame(expr)) {
        return;
      }
    }
    predicates.add(expr);
  }

  /**
   * Adds the passed list of pushDowns for the alias.
   *
   * @param alias
   * @param pushDowns
   */
  public void addPushDowns(String alias, List<ExprNodeDesc> pushDowns) {
    List<ExprNodeDesc> predicates = getPushdownPreds(alias);
    boolean isNew;
    for (ExprNodeDesc newPred: pushDowns) {
      isNew = true;
      for (ExprNodeDesc curPred: predicates) {
        if (curPred.isSame(newPred)) {
          isNew = false;
          break;
        }
      }
      if (isNew) {
        predicates.add(newPred);
      }
    }
  }

  /**
   * Returns the list of pushdown expressions for each alias that appear in the
   * current operator's RowResolver. The exprs in each list can be combined
   * using conjunction (AND).
   *
   * @return the map of alias to a list of pushdown predicates
   */
  public Map<String, List<ExprNodeDesc>> getFinalCandidates() {
    return pushdownPreds;
  }

  private List<ExprNodeDesc> getPushdownPreds(String alias) {
    List<ExprNodeDesc> predicates = pushdownPreds.get(alias);
    if (predicates == null) {
      pushdownPreds.put(alias, predicates = new ArrayList<ExprNodeDesc>());
    }
    return predicates;
  }

  public boolean hasAnyCandidates() {
    if (pushdownPreds == null || pushdownPreds.isEmpty()) {
      return false;
    }
    for (List<ExprNodeDesc> exprs : pushdownPreds.values()) {
      if (!exprs.isEmpty()) {
        return true;
      }
    }
    return false;
  }

  public boolean hasNonFinalCandidates() {
    if (nonFinalPreds == null || nonFinalPreds.isEmpty()) {
      return false;
    }
    for (List<ExprNodeDesc> exprs : nonFinalPreds.values()) {
      if (!exprs.isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Adds the specified expr as a non-final candidate
   *
   * @param expr
   */
  public void addNonFinalCandidate(String alias, ExprNodeDesc expr) {
    if (nonFinalPreds.get(alias) == null) {
      nonFinalPreds.put(alias, new ArrayList<ExprNodeDesc>());
    }
    nonFinalPreds.get(alias).add(expr);
  }

  /**
   * Returns list of non-final candidate predicate for each map.
   *
   * @return list of non-final candidate predicates
   */
  public Map<String, List<ExprNodeDesc>> getNonFinalCandidates() {
    return nonFinalPreds;
  }

  public Map<String, List<ExprNodeDesc>> getResidualPredicates(boolean clear) {
    Map<String, List<ExprNodeDesc>> oldExprs = new HashMap<String, List<ExprNodeDesc>>();
    for (Map.Entry<String, List<ExprNodeDesc>> entry : nonFinalPreds.entrySet()) {
      List<ExprNodeDesc> converted = new ArrayList<ExprNodeDesc>();
      for (ExprNodeDesc newExpr : entry.getValue()) {
        // We should clone it to avoid getting overwritten if two or more operator uses
        // this same expression.
        converted.add(newToOldExprMap.get(newExpr).clone());
      }
      oldExprs.put(entry.getKey(), converted);
    }
    if (clear) {
      nonFinalPreds.clear();
    }
    return oldExprs;
  }

  /**
   * Merges the specified pushdown predicates with the current class.
   *
   * @param ewi
   *          ExpressionWalkerInfo
   */
  public void merge(ExprWalkerInfo ewi) {
    if (ewi == null) {
      return;
    }
    for (Entry<String, List<ExprNodeDesc>> e : ewi.getFinalCandidates()
        .entrySet()) {
      List<ExprNodeDesc> predList = pushdownPreds.get(e.getKey());
      if (predList != null) {
        predList.addAll(e.getValue());
      } else {
        pushdownPreds.put(e.getKey(), e.getValue());
      }
    }
    for (Entry<String, List<ExprNodeDesc>> e : ewi.getNonFinalCandidates()
        .entrySet()) {
      List<ExprNodeDesc> predList = nonFinalPreds.get(e.getKey());
      if (predList != null) {
        predList.addAll(e.getValue());
      } else {
        nonFinalPreds.put(e.getKey(), e.getValue());
      }
    }
    newToOldExprMap.putAll(ewi.getNewToOldExprMap());
  }

  /**
   * sets the deterministic flag for this expression.
   *
   * @param b
   *          deterministic or not
   */
  public void setDeterministic(boolean b) {
    isDeterministic = b;
  }

  /**
   * @return whether this expression is deterministic or not.
   */
  public boolean isDeterministic() {
    return isDeterministic;
  }
}
