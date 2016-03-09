/*
 * Transform a TSV format factor graph file and output corresponding binary
 * format used in DeepDive
 */

#include <iostream>
#include <fstream>
#include <sstream>
#include <stdlib.h>
#include <stdint.h>
#include <assert.h>
#include <vector>

using namespace std;

// 64-bit endian conversion, note input must be unsigned long
// for double, cast its underlying bytes to unsigned long, see examples in
// load_var
#define bswap_64(x)                                                            \
  ((((x)&0xff00000000000000ull) >> 56) | (((x)&0x00ff000000000000ull) >> 40) | \
   (((x)&0x0000ff0000000000ull) >> 24) | (((x)&0x000000ff00000000ull) >> 8) |  \
   (((x)&0x00000000ff000000ull) << 8) | (((x)&0x0000000000ff0000ull) << 24) |  \
   (((x)&0x000000000000ff00ull) << 40) | (((x)&0x00000000000000ffull) << 56))

// 16-bit endian conversion
#define bswap_16(x) \
  ((unsigned short int)((((x) >> 8) & 0xff) | (((x)&0xff) << 8)))

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

  edge_count = bswap_64(edge_count);

  while (fin >> vid >> is_evidence >> initial_value >> type >> cardinality) {
    // endianess
    vid = bswap_64(vid);
    uint64_t initval = bswap_64(*(uint64_t *)&initial_value);
    type = bswap_16(type);
    cardinality = bswap_64(cardinality);

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
    wid = bswap_64(wid);
    uint64_t initval = bswap_64(*(uint64_t *)&initial_value);

    fout.write((char *)&wid, 8);
    fout.write((char *)&isfixed, 1);
    fout.write((char *)&initval, 8);

    ++count;
  }
  std::cout << count << std::endl;

  fin.close();
  fout.close();
}

// load factors
// fid, wid, vids
void load_factor_with_fid(std::string input_filename,
                          std::string output_factors_filename,
                          std::string output_edges_filename, short funcid,
                          long nvar, char **positives) {
  std::ifstream fin(input_filename.c_str());
  std::ofstream fout(output_factors_filename.c_str(),
                     std::ios::binary | std::ios::out);
  std::ofstream fedgeout(output_edges_filename.c_str(),
                         std::ios::binary | std::ios::out);

  long factorid = 0;
  long weightid = 0;
  long variableid = 0;
  long nedge = 0;
  long nvars_big = bswap_64(nvar);
  long predicate = funcid == 5 ? -1 : 1;
  vector<int> positives_vec;

  funcid = bswap_16(funcid);

  for (int i = 0; i < nvar; i++) {
    positives_vec.push_back(atoi(positives[i]));
  }

  predicate = bswap_64(predicate);

  const char field_delim = '\t';  // tsv file delimiter
  const char array_delim = ',';   // array delimiter
  string line;
  while (getline(fin, line)) {
    string field;
    istringstream ss(line);

    // factor id
    getline(ss, field, field_delim);
    factorid = atol(field.c_str());
    factorid = bswap_64(factorid);

    // weightid
    getline(ss, field, field_delim);
    weightid = atol(field.c_str());
    weightid = bswap_64(weightid);

    fout.write((char *)&factorid, 8);
    fout.write((char *)&weightid, 8);
    fout.write((char *)&funcid, 2);
    // fout.write((char *)&nvars_big, 8);

    uint64_t position = 0;
    uint64_t position_big;
    long n_vars = 0;

    for (long i = 0; i < nvar; i++) {
      getline(ss, field, field_delim);

      // array type
      if (field.at(0) == '{') {
        string subfield;
        istringstream ss1(field);
        ss1.get();  // get '{'
        bool ended = false;
        while (getline(ss1, subfield, array_delim)) {
          if (subfield.at(subfield.length() - 1) == '}') {
            ended = true;
            subfield = subfield.substr(0, subfield.length() - 1);
          }
          variableid = atol(subfield.c_str());
          variableid = bswap_64(variableid);
          position_big = bswap_64(position);

          fedgeout.write((char *)&variableid, 8);
          fedgeout.write((char *)&factorid, 8);
          fedgeout.write((char *)&position_big, 8);
          fedgeout.write((char *)&positives_vec[i], 1);
          fedgeout.write((char *)&predicate, 8);

          nedge++;
          position++;
          n_vars++;
          if (ended) break;
        }
      } else {
        variableid = atol(field.c_str());
        variableid = bswap_64(variableid);
        position_big = bswap_64(position);

        fedgeout.write((char *)&variableid, 8);
        fedgeout.write((char *)&factorid, 8);
        fedgeout.write((char *)&position_big, 8);
        fedgeout.write((char *)&positives_vec[i], 1);
        fedgeout.write((char *)&predicate, 8);

        nedge++;
        position++;
        n_vars++;
      }
    }
    n_vars = bswap_64(n_vars);
    fout.write((char *)&n_vars, 8);
  }
  std::cout << nedge << std::endl;

  fin.close();
  fout.close();
  fedgeout.close();
}

// load factors
// wid, vids
void load_factor(std::string input_filename, std::string output_filename,
                 short funcid, long nvar, char **positives) {
  std::ifstream fin(input_filename.c_str());
  std::ofstream fout(output_filename.c_str(), std::ios::binary | std::ios::out);

  long weightid = 0;
  long variableid = 0;
  long nedge = 0;
  long predicate = funcid == 5 ? -1 : 1;
  vector<int> positives_vec;
  vector<long> variables;

  funcid = bswap_16(funcid);

  for (int i = 0; i < nvar; i++) {
    positives_vec.push_back(atoi(positives[i]));
  }

  predicate = bswap_64(predicate);

  const char field_delim = '\t';  // tsv file delimiter
  const char array_delim = ',';   // array delimiter
  string line;
  while (getline(fin, line)) {
    string field;
    istringstream ss(line);
    variables.clear();

    // weightid
    getline(ss, field, field_delim);
    weightid = atol(field.c_str());
    weightid = bswap_64(weightid);

    fout.write((char *)&weightid, 8);
    fout.write((char *)&funcid, 2);

    uint64_t position = 0;
    uint64_t position_big;
    long n_vars = 0;

    for (long i = 0; i < nvar; i++) {
      getline(ss, field, field_delim);

      // array type
      if (field.at(0) == '{') {
        string subfield;
        istringstream ss1(field);
        ss1.get();  // get '{'
        bool ended = false;
        while (getline(ss1, subfield, array_delim)) {
          if (subfield.at(subfield.length() - 1) == '}') {
            ended = true;
            subfield = subfield.substr(0, subfield.length() - 1);
          }
          variableid = atol(subfield.c_str());
          variableid = bswap_64(variableid);
          variables.push_back(variableid);

          nedge++;
          n_vars++;
          if (ended) break;
        }
      } else {
        variableid = atol(field.c_str());
        variableid = bswap_64(variableid);
        variables.push_back(variableid);

        nedge++;
        n_vars++;
      }
    }
    n_vars = bswap_64(n_vars);
    fout.write((char *)&predicate, 8);
    fout.write((char *)&n_vars, 8);
    for (long i = 0; i < variables.size(); i++) {
      fout.write((char *)&variables[i], 8);
      fout.write((char *)&positives_vec[i], 1);
    }
  }
  std::cout << nedge << std::endl;

  fin.close();
  fout.close();
}

void load_active(std::string input_filename, std::string output_filename) {
  std::ifstream fin(input_filename.c_str());
  std::ofstream fout(output_filename.c_str(), std::ios::binary);
  long count = 0;

  long id;
  while (fin >> id) {
    id = bswap_64(id);
    fout.write((char *)&id, 8);
    ++count;
  }
  std::cout << count << std::endl;

  fin.close();
  fout.close();
}

// read multinomial variable domains and convert to binary format
void load_domain(std::string input_filename, std::string output_filename) {
  std::ifstream fin(input_filename.c_str());
  std::ofstream fout(output_filename.c_str(), std::ios::binary | std::ios::out);

  long vid, cardinality, cardinality_big, value;
  std::string domain;

  while (fin >> vid >> cardinality >> domain) {
    // endianess
    vid = bswap_64(vid);
    cardinality_big = bswap_64(cardinality);

    fout.write((char *)&vid, 8);
    fout.write((char *)&cardinality_big, 8);

    // an array of domain values
    std::string subfield;
    std::istringstream ss1(domain);
    ss1.get();  // get '{'
    bool ended = false;
    long count = 0;
    while (getline(ss1, subfield, ',')) {
      if (subfield.at(subfield.length() - 1) == '}') {
        ended = true;
        subfield = subfield.substr(0, subfield.length() - 1);
      }
      value = atol(subfield.c_str());
      value = bswap_64(value);
      fout.write((char *)&value, 8);

      count++;
      if (ended) break;
    }
    assert(count == cardinality);
  }

  fin.close();
  fout.close();
}

int main(int argc, char **argv) {
  std::string app(argv[1]);
  if (app.compare("variable") == 0) {
    load_var(argv[2], argv[3]);
  } else if (app.compare("weight") == 0) {
    load_weight(argv[2], argv[3]);
  } else if (app.compare("factor") == 0) {
    std::string inc(argv[6]);
    if (inc.compare("inc") == 0 || inc.compare("mat") == 0)
      load_factor_with_fid(argv[2], argv[3], argv[7], atoi(argv[4]),
                           atoi(argv[5]), &argv[8]);
    else
      load_factor(argv[2], argv[3], atoi(argv[4]), atoi(argv[5]), &argv[7]);
  } else if (app.compare("active") == 0) {
    load_active(argv[2], argv[3]);
  } else if (app.compare("domain") == 0) {
    load_domain(argv[2], argv[3]);
  } else {
    std::cerr << "Unsupported type" << std::endl;
    exit(1);
  }
  return 0;
}
