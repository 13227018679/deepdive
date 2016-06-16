/*
 * Transform a TSV format factor graph file and output corresponding binary
 * format used in DeepDive
 */

#include "binary_format.h"
#include <assert.h>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdint.h>
#include <stdlib.h>
#include <vector>

namespace dd {

constexpr char field_delim = '\t';  // tsv file delimiter

// read variables and convert to binary format
void load_var(std::string input_filename, std::string output_filename) {
  std::ifstream fin(input_filename.c_str());
  std::ofstream fout(output_filename.c_str(), std::ios::binary | std::ios::out);
  long count = 0;

  long vid;
  int is_evidence;
  double initial_value;
  short type;
  long edge_count = -1;
  long cardinality;

  edge_count = htobe64(edge_count);

  while (fin >> vid >> is_evidence >> initial_value >> type >> cardinality) {
    // endianess
    vid = htobe64(vid);
    uint64_t initval = htobe64(*(uint64_t *)&initial_value);
    type = htobe16(type);
    cardinality = htobe64(cardinality);

    fout.write((char *)&vid, 8);
    fout.write((char *)&is_evidence, 1);
    fout.write((char *)&initval, 8);
    fout.write((char *)&type, 2);
    fout.write((char *)&edge_count, 8);
    fout.write((char *)&cardinality, 8);

    ++count;
  }
  std::cout << count << std::endl;

  fin.close();
  fout.close();
}

// convert weights
void load_weight(std::string input_filename, std::string output_filename) {
  std::ifstream fin(input_filename.c_str());
  std::ofstream fout(output_filename.c_str(), std::ios::binary | std::ios::out);
  long count = 0;

  long wid;
  int isfixed;
  double initial_value;

  while (fin >> wid >> isfixed >> initial_value) {
    wid = htobe64(wid);
    uint64_t initval = htobe64(*(uint64_t *)&initial_value);

    fout.write((char *)&wid, 8);
    fout.write((char *)&isfixed, 1);
    fout.write((char *)&initval, 8);

    ++count;
  }
  std::cout << count << std::endl;

  fin.close();
  fout.close();
}

static inline long parse_pgarray(
    std::istream &input, std::function<void(const std::string &)> parse_element,
    long expected_count = -1) {
  if (input.peek() == '{') {
    input.get();
    std::string element;
    bool ended = false;
    long count = 0;
    while (getline(input, element, ',')) {
      if (element.at(element.length() - 1) == '}') {
        ended = true;
        element = element.substr(0, element.length() - 1);
      }
      parse_element(element);
      count++;
      if (ended) break;
    }
    assert(expected_count < 0 || count == expected_count);
    return count;
  } else {
    return -1;
  }
}
static inline long parse_pgarray_or_die(
    std::istream &input, std::function<void(const std::string &)> parse_element,
    long expected_count = -1) {
  long count = parse_pgarray(input, parse_element, expected_count);
  if (count >= 0) {
    return count;
  } else {
    std::cerr << "Expected an array '{' but found: " << input.get()
              << std::endl;
    abort();
  }
}

// load factors
// wid, vids
void load_factor(std::string input_filename, std::string output_filename,
                 short funcid, long nvar,
                 const std::vector<bool> &positives_vec) {
  std::ifstream fin(input_filename.c_str());
  std::ofstream fout(output_filename.c_str(), std::ios::binary | std::ios::out);

  long weightid = 0;
  long nedge = 0;
  long predicate = funcid == 5 ? -1 : 1;
  std::vector<long> variables;
  std::vector<long> vals_and_weights;

  funcid = htobe16(funcid);

  predicate = htobe64(predicate);

  std::string line;
  std::string array_piece;
  while (getline(fin, line)) {
    std::string field;
    istringstream ss(line);
    variables.clear();

    // factor type
    fout.write((char *)&funcid, 2);

    // variable ids
    long n_vars = 0;
    auto parse_variableid = [&variables, &n_vars,
                             &nedge](const std::string &element) {
      long variableid = atol(element.c_str());
      variableid = htobe64(variableid);
      variables.push_back(variableid);
      ++nedge;
      ++n_vars;
    };
    for (long i = 0; i < nvar; ++i) {
      getline(ss, field, field_delim);
      // try parsing as an array first
      istringstream fieldinput(field);
      if (parse_pgarray(fieldinput, parse_variableid) < 0) {
        // otherwise, parse it as a single variable
        parse_variableid(field);
      }
    }
    n_vars = htobe64(n_vars);
    fout.write((char *)&predicate, 8);
    fout.write((char *)&n_vars, 8);
    for (variable_id_t i = 0; i < variables.size(); ++i) {
      fout.write((char *)&variables[i], 8);
      char flag = positives_vec.at(i) ? 1 : 0;
      fout.write((char *)&flag, 1);
    }

    // weight ids
    switch (be16toh(funcid)) {
      case FUNC_SPARSE_MULTINOMIAL: {
        vals_and_weights.clear();
        // IN  Format: NUM_WEIGHTS [VAR1 VAL ID] [VAR2 VAL ID] ... [WEIGHT ID]
        // OUT Format: NUM_WEIGHTS [[VAR1_VALi, VAR2_VALi, ..., WEIGHTi]]
        // first, the run-length
        getline(ss, field, field_delim);
        long num_weightids = atol(field.c_str());
        long num_weightids_be = htobe64(num_weightids);
        fout.write((char *)&num_weightids_be, 8);

        // second, parse var vals for each var
        // TODO: hard coding cid length (4) for now
        for (long i = 0; i < nvar; ++i) {
          getline(ss, array_piece, field_delim);
          istringstream ass(array_piece);
          parse_pgarray_or_die(
              ass, [&vals_and_weights](const std::string &element) {
                int cid = atoi(element.c_str());
                cid = htobe32(cid);
                vals_and_weights.push_back(cid);
              }, num_weightids);
        }
        // third, parse weights
        parse_pgarray_or_die(
            ss, [&vals_and_weights](const std::string &element) {
              long weightid = atol(element.c_str());
              weightid = htobe64(weightid);
              vals_and_weights.push_back(weightid);
            }, num_weightids);

        // fourth, transpose into output format
        for (long i = 0; i < num_weightids; ++i) {
          for (long j = 0; j < nvar; ++j) {
            int cid = (int)vals_and_weights[j * num_weightids + i];
            fout.write((char *)&cid, 4);
          }
          long weightid = vals_and_weights[nvar * num_weightids + i];
          fout.write((char *)&weightid, 8);
        }
        break;
      }

      default:
        // a single weight id
        getline(ss, field, field_delim);
        weightid = atol(field.c_str());
        weightid = htobe64(weightid);
        fout.write((char *)&weightid, 8);
    }
  }

  std::cout << nedge << std::endl;

  fin.close();
  fout.close();
}

// read multinomial variable domains and convert to binary format
void load_domain(std::string input_filename, std::string output_filename) {
  std::ifstream fin(input_filename.c_str());
  std::ofstream fout(output_filename.c_str(), std::ios::binary | std::ios::out);

  std::string line;
  while (getline(fin, line)) {
    istringstream line_input(line);
    long vid, cardinality, cardinality_big;
    std::string domain;
    assert(line_input >> vid >> cardinality >> domain);

    // endianess
    vid = htobe64(vid);
    cardinality_big = htobe64(cardinality);

    fout.write((char *)&vid, 8);
    fout.write((char *)&cardinality_big, 8);

    // an array of domain values
    istringstream domain_input(domain);
    parse_pgarray_or_die(domain_input, [&fout](const std::string &subfield) {
      long value = atol(subfield.c_str());
      value = htobe64(value);
      fout.write((char *)&value, 8);
    }, cardinality);
  }

  fin.close();
  fout.close();
}

int text2bin(const CmdParser &args) {
  // common arguments
  if (args.text2bin_mode == "variable") {
    load_var(args.text2bin_input, args.text2bin_output);
  } else if (args.text2bin_mode == "weight") {
    load_weight(args.text2bin_input, args.text2bin_output);
  } else if (args.text2bin_mode == "factor") {
    load_factor(args.text2bin_input, args.text2bin_output,
                args.text2bin_factor_func_id, args.text2bin_factor_arity,
                args.text2bin_factor_positives_or_not);
  } else if (args.text2bin_mode == "domain") {
    load_domain(args.text2bin_input, args.text2bin_output);
  } else {
    std::cerr << "Unsupported type" << std::endl;
    return 1;
  }
  return 0;
}

}  // namespace dd
