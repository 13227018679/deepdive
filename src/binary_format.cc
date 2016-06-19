/**
 * This file contains binary formatting methods for FactorGraphs and
 * CompactFactorGraphs. We think this file is a good place to put the
 * definitions of these methods as it conveys the intentions quite clearly
 * (i.e. for binary formatting of these objects).
 */
#include "common.h"
#include "binary_format.h"
#include "factor_graph.h"
#include "factor.h"
#include "variable.h"
#include <fstream>
#include <iostream>
#include <cstdint>

namespace dd {

// Read meta data file, return Meta struct
FactorGraphDescriptor read_meta(const std::string &meta_file) {
  FactorGraphDescriptor meta;
  std::ifstream file;
  file.open(meta_file.c_str());
  std::string buf;
  getline(file, buf, ',');
  meta.num_weights = atoll(buf.c_str());
  getline(file, buf, ',');
  meta.num_variables = atoll(buf.c_str());
  getline(file, buf, ',');
  meta.num_factors = atoll(buf.c_str());
  getline(file, buf, ',');
  meta.num_edges = atoll(buf.c_str());
  file.close();
  return meta;
}

void FactorGraph::load_weights(const std::string &filename) {
  std::ifstream file;
  file.open(filename, std::ios::in | std::ios::binary);

  long long count = 0;
  long long id;
  bool isfixed;
  char padding;
  double initial_value;
  while (file.good()) {
    // read fields
    file.read((char *)&id, 8);
    file.read((char *)&padding, 1);
    if (!file.read((char *)&initial_value, 8)) break;
    // convert endian
    id = be64toh(id);
    isfixed = padding;
    long long tmp = be64toh(*(uint64_t *)&initial_value);
    initial_value = *(double *)&tmp;

    // load into factor graph
    weights[id] = Weight(id, initial_value, isfixed);
    ++count;
  }
  size.num_weights += count;
  file.close();
}

void FactorGraph::load_variables(const std::string &filename) {
  std::ifstream file;
  file.open(filename, std::ios::in | std::ios::binary);

  long long count = 0;
  long long id;
  char isevidence;
  double initial_value;
  short type;
  long long edge_count;
  variable_value_t cardinality;
  while (file.good()) {
    // read fields
    file.read((char *)&id, 8);
    file.read((char *)&isevidence, 1);
    file.read((char *)&initial_value, 8);
    file.read((char *)&type, 2);
    file.read((char *)&edge_count, 8);
    if (!file.read((char *)&cardinality, 4)) break;

    // convert endian
    id = be64toh(id);
    type = be16toh(type);
    long long tmp = be64toh(*(uint64_t *)&initial_value);
    initial_value = *(double *)&tmp;
    edge_count = be64toh(edge_count);
    cardinality = be32toh(cardinality);

    ++count;

    variable_domain_type_t type_const;
    switch (type) {
      case 0:
        type_const = DTYPE_BOOLEAN;
        break;
      case 1:
        type_const = DTYPE_CATEGORICAL;
        break;
      default:
        std::cerr
            << "[ERROR] Only Boolean and Categorical variables are supported "
               "now!"
            << std::endl;
        std::abort();
    }
    bool is_evidence = isevidence >= 1;
    bool is_observation = isevidence == 2;
    double init_value = is_evidence ? initial_value : 0;

    variables[id] =
        RawVariable(id, type_const, is_evidence, cardinality, init_value,
                    init_value, edge_count, is_observation);

    ++size.num_variables;
    if (is_evidence) {
      ++size.num_variables_evidence;
    } else {
      ++size.num_variables_query;
    }
  }
  file.close();
}

void FactorGraph::load_factors(const std::string &filename) {
  std::ifstream file;
  file.open(filename.c_str(), std::ios::in | std::ios::binary);
  size_t count = 0;
  variable_id_t variable_id;
  weight_id_t weightid;
  variable_value_t value_id;
  short type;
  size_t edge_count;
  variable_value_t equal_predicate;
  bool ispositive;
  while (file.good()) {
    file.read((char *)&type, 2);
    file.read((char *)&equal_predicate, 8);
    if (!file.read((char *)&edge_count, 8)) break;

    type = be16toh(type);
    edge_count = be64toh(edge_count);
    equal_predicate = be64toh(equal_predicate);

    ++count;

    factors[size.num_factors] = RawFactor(
        size.num_factors, -1, (factor_function_type_t)type, edge_count);

    for (size_t position = 0; position < edge_count; ++position) {
      file.read((char *)&variable_id, 8);
      file.read((char *)&ispositive, 1);
      variable_id = be64toh(variable_id);

      // check for wrong id
      assert(variable_id < capacity.num_variables && variable_id >= 0);

      // add variables to factors
      factors[size.num_factors].add_variable_in_factor(
          VariableInFactor(variable_id, position, ispositive, equal_predicate));
      variables[variable_id].add_factor_id(size.num_factors);
    }
    size.num_edges += edge_count;

    switch (type) {
      case (FUNC_AND_CATEGORICAL): {
        weight_id_t n_weights = 0;
        file.read((char *)&n_weights, 8);
        n_weights = be64toh(n_weights);

        factors[size.num_factors].weight_ids =
            new std::unordered_map<variable_value_t, weight_id_t>(n_weights);
        for (weight_id_t i = 0; i < n_weights; ++i) {
          // calculate radix-based key into weight_ids (see also
          // FactorGraph::get_categorical_weight_id)
          // TODO: refactor the above formula into a shared routine. (See also
          // FactorGraph::get_categorical_weight_id)
          size_t key = 0;
          for (size_t j = 0; j < edge_count; ++j) {
            const Variable &var =
                variables[factors[size.num_factors].tmp_variables.at(j).vid];
            file.read((char *)&value_id, 4);
            value_id = be32toh(value_id);
            key *= var.cardinality;
            key += var.get_domain_index(value_id);
          }
          file.read((char *)&weightid, 8);
          weightid = be64toh(weightid);
          (*factors[size.num_factors].weight_ids)[key] = weightid;
        }
        break;
      }

      default:
        file.read((char *)&weightid, 8);
        weightid = be64toh(weightid);
        factors[size.num_factors].weight_id = weightid;
    }

    ++size.num_factors;
  }
  file.close();
}

void FactorGraph::load_domains(const std::string &filename) {
  std::ifstream file;
  file.open(filename.c_str(), std::ios::in | std::ios::binary);
  long id;
  variable_value_t value;

  while (true) {
    file.read((char *)&id, 8);
    if (!file.good()) {
      return;
    }

    id = be64toh(id);
    RawVariable &variable = variables[id];

    variable_value_t domain_size;
    file.read((char *)&domain_size, 4);
    domain_size = be32toh(domain_size);
    assert(variable.cardinality == domain_size);

    std::vector<int> domain_list(domain_size);
    variable.domain_map.reset(
        new std::unordered_map<variable_value_t, size_t>());

    for (size_t i = 0; i < domain_size; ++i) {
      file.read((char *)&value, 4);
      value = be32toh(value);
      domain_list[i] = value;
    }

    std::sort(domain_list.begin(), domain_list.end());
    for (size_t i = 0; i < domain_size; ++i) {
      (*variable.domain_map)[domain_list[i]] = i;
    }

    // adjust the initial assignments to a valid one instead of zero for query
    // variables
    if (!variable.is_evid) {
      variable.assignment_free = variable.assignment_evid = domain_list[0];
    }
  }
}

}  // namespace dd
