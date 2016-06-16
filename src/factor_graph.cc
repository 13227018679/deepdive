#include "factor_graph.h"
#include "factor.h"
#include "binary_format.h"

#include <iostream>

namespace dd {

FactorGraphDescriptor::FactorGraphDescriptor()
    : FactorGraphDescriptor(0, 0, 0, 0) {}

FactorGraphDescriptor::FactorGraphDescriptor(size_t n_var, size_t n_fac,
                                             size_t n_wgt, size_t n_edg)
    : num_variables(n_var),
      num_factors(n_fac),
      num_edges(n_edg),
      num_weights(n_wgt),
      num_variables_evidence(0),
      num_variables_query(0) {}

std::ostream &operator<<(std::ostream &stream,
                         const FactorGraphDescriptor &size) {
  stream << "#V=" << size.num_variables;
  if (size.num_variables_query + size.num_variables_evidence > 0) {
    stream << "(";
    stream << "qry=" << size.num_variables_query;
    stream << ",";
    stream << "evd=" << size.num_variables_evidence;
    stream << ")";
  }
  stream << ",\t"
         << "#F=" << size.num_factors;
  stream << ",\t"
         << "#W=" << size.num_weights;
  stream << ",\t"
         << "#E=" << size.num_edges;
  return stream;
}

FactorGraph::FactorGraph(const FactorGraphDescriptor &capacity)
    : capacity(capacity),
      size(),
      variables(new RawVariable[capacity.num_variables]),
      factors(new RawFactor[capacity.num_factors]),
      weights(new Weight[capacity.num_weights]) {}

CompactFactorGraph::CompactFactorGraph(const FactorGraph &fg)
    : CompactFactorGraph(fg.size) {
  size_t i_edge = 0;

  // For each factor, put the variables sorted within each factor in an array.
  for (factor_id_t i = 0; i < fg.size.num_factors; ++i) {
    RawFactor &rf = fg.factors[i];
    factors[i] = rf;
    factors[i].n_start_i_vif = i_edge;

    /*
     * Each factor knows how many variables it has. After sorting the variables
     * within the factor by their position, lay the variables in this factor
     * one after another in the vifs array.
     */
    std::sort(rf.tmp_variables.begin(), rf.tmp_variables.end(),
              [](const VariableInFactor &x, const VariableInFactor &y) {
                return x.n_position < y.n_position;
              });
    for (const VariableInFactor &vif : rf.tmp_variables) {
      vifs[i_edge] = vif;
      ++i_edge;
    }
  }
  assert(i_edge == fg.size.num_edges);

  // For each variable, lay the factors sequentially in an array as well.
  i_edge = 0;
  size_t ntallies = 0;
  for (variable_id_t i = 0; i < fg.size.num_variables; ++i) {
    const RawVariable &rv = fg.variables[i];
    variables[i] = rv;
    variables[i].n_factors = rv.tmp_factor_ids.size();
    variables[i].n_start_i_factors = i_edge;

    if (rv.domain_type == DTYPE_MULTINOMIAL) {
      variables[i].n_start_i_tally = ntallies;
      ntallies += variables[i].cardinality;
    }

    for (const auto &fid : rv.tmp_factor_ids) {
      factor_ids[i_edge] = fid;

      auto &cf = compact_factors[i_edge];
      const auto &f = factors[fid];
      cf.id = f.id;
      cf.func_id = f.func_id;
      cf.n_variables = f.n_variables;
      cf.n_start_i_vif = f.n_start_i_vif;

      compact_factors_weightids[i_edge] = f.weight_id;

      ++i_edge;
    }
  }
  assert(i_edge == fg.size.num_edges);

  size.num_edges = i_edge;
}

void FactorGraph::safety_check() {
  // check if any space is wasted
  assert(capacity.num_variables == size.num_variables);
  assert(capacity.num_factors == size.num_factors);
  assert(capacity.num_edges == size.num_edges);
  assert(capacity.num_weights == size.num_weights);

  // check whether variables, factors, and weights are stored
  // in the order of their id
  for (variable_id_t i = 0; i < size.num_variables; ++i) {
    assert(this->variables[i].id == i);
  }
  for (factor_id_t i = 0; i < size.num_factors; ++i) {
    assert(this->factors[i].id == i);
  }
  for (weight_id_t i = 0; i < size.num_weights; ++i) {
    assert(this->weights[i].id == i);
  }
}

CompactFactorGraph::CompactFactorGraph(const FactorGraphDescriptor &size)
    : size(size),
      variables(new Variable[size.num_variables]),
      factors(new Factor[size.num_factors]),
      compact_factors(new CompactFactor[size.num_edges]),
      compact_factors_weightids(new weight_id_t[size.num_edges]),
      factor_ids(new factor_id_t[size.num_edges]),
      vifs(new VariableInFactor[size.num_edges]) {}

CompactFactorGraph::CompactFactorGraph(const CompactFactorGraph &other)
    : CompactFactorGraph(other.size) {
  // copy each member from the given graph
  COPY_ARRAY_UNIQUE_PTR_MEMBER(variables, size.num_variables);
  COPY_ARRAY_UNIQUE_PTR_MEMBER(factors, size.num_factors);
  COPY_ARRAY_UNIQUE_PTR_MEMBER(compact_factors, size.num_edges);
  COPY_ARRAY_UNIQUE_PTR_MEMBER(compact_factors_weightids, size.num_edges);
  COPY_ARRAY_UNIQUE_PTR_MEMBER(factor_ids, size.num_edges);
  COPY_ARRAY_UNIQUE_PTR_MEMBER(vifs, size.num_edges);
}

weight_id_t CompactFactorGraph::get_multinomial_weight_id(
    const variable_value_t assignments[], const CompactFactor &fs,
    variable_id_t vid, variable_value_t proposal) {
  /**
   * For FUNC_MULTINOMIAL, the weight ids are aligned in a continuous region
   * according
   * to the numerical order of variable values.
   * For example, for variable assignment indexes i1, ..., ik with cardinality
   * d1, ..., dk
   * The weight index is
   * (...((((0 * d1 + i1) * d2) + i2) * d3 + i3) * d4 + ...) * dk + ik
   *
   * For FUNC_SPARSE_MULTINOMIAL, we look up the weight_ids map.
   *
   * TODO: refactor the above formula into a shared routine. (See also
   * binary_parser.read_factors)
   */
  weight_id_t weight_offset = 0;
  // for each variable in the factor
  for (size_t i = fs.n_start_i_vif; i < fs.n_start_i_vif + fs.n_variables;
       ++i) {
    const VariableInFactor &vif = vifs[i];
    const Variable &variable = variables[vif.vid];
    weight_offset *= variable.cardinality;
    weight_offset += variable.get_domain_index(
        vif.vid == vid ? proposal : assignments[vif.vid]);
  }

  switch (fs.func_id) {
    case FUNC_SPARSE_MULTINOMIAL: {
      auto iter = factors[fs.id].weight_ids->find(weight_offset);
      if (iter != factors[fs.id].weight_ids->end()) return iter->second;
      break;
    }
    case FUNC_MULTINOMIAL:
      return compact_factors_weightids[&fs - &compact_factors[0]] +
             weight_offset;
    default:
      std::abort();
  }
  return Weight::INVALID_ID;
}

void CompactFactorGraph::update_weight(const Variable &variable,
                                       InferenceResult &infrs,
                                       double stepsize) {
  // corresponding factors and weights in a continous region
  CompactFactor *const fs = &compact_factors[variable.n_start_i_factors];
  const weight_id_t *const ws =
      &compact_factors_weightids[variable.n_start_i_factors];
  // for each factor
  for (size_t i = 0; i < variable.n_factors; ++i) {
    // boolean variable
    switch (variable.domain_type) {
      case DTYPE_BOOLEAN: {
        // only update weight when it is not fixed
        if (!infrs.weights_isfixed[ws[i]]) {
          // stochastic gradient ascent
          // increment weight with stepsize * gradient of weight
          // gradient of weight = E[f|D] - E[f], where D is evidence variables,
          // f is the factor function, E[] is expectation. Expectation is
          // calculated
          // using a sample of the variable.
          infrs.weight_values[ws[i]] +=
              stepsize * (potential(fs[i], infrs.assignments_evid.get()) -
                          potential(fs[i], infrs.assignments_free.get()));
        }
        break;
      }
      case DTYPE_MULTINOMIAL: {
        // two weights need to be updated
        // sample with evidence fixed, I0, with corresponding weight w1
        // sample without evidence unfixed, I1, with corresponding weight w2
        // gradient of wd0 = f(I0) - I(w1==w2)f(I1)
        // gradient of wd1 = I(w1==w2)f(I0) - f(I1)
        weight_id_t wid1 = get_multinomial_weight_id(
            infrs.assignments_evid.get(), fs[i], -1, -1);
        weight_id_t wid2 = get_multinomial_weight_id(
            infrs.assignments_free.get(), fs[i], -1, -1);
        bool equal = (wid1 == wid2);

        if (wid1 != Weight::INVALID_ID && !infrs.weights_isfixed[wid1]) {
          infrs.weight_values[wid1] +=
              stepsize *
              (potential(fs[i], infrs.assignments_evid.get()) -
               equal * potential(fs[i], infrs.assignments_free.get()));
        }

        if (wid2 != Weight::INVALID_ID && !infrs.weights_isfixed[wid2]) {
          infrs.weight_values[wid2] +=
              stepsize *
              (equal * potential(fs[i], infrs.assignments_evid.get()) -
               potential(fs[i], infrs.assignments_free.get()));
        }
        break;
      }

      default:
        std::abort();
    }
  }
}

}  // namespace dd
