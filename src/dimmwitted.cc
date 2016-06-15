#include "dimmwitted.h"
#include "common.h"
#include "assert.h"
#include "bin2text.h"
#include "text2bin.h"
#include "binary_format.h"
#include "factor_graph.h"
#include "gibbs_sampler.h"

#include <map>
#include <fstream>
#include <unistd.h>

namespace dd {

// the command-line entry point
int dw(int argc, const char *const argv[]) {
  // available modes
  const std::map<std::string, int (*)(const dd::CmdParser &)> MODES = {
      {"gibbs", gibbs},  // to do the learning and inference with Gibbs sampling
      {"text2bin", text2bin},  // to generate binary factor graphs from TSV
      {"bin2text", bin2text},  // to dump TSV of binary factor graphs
  };

  // parse command-line arguments
  dd::CmdParser cmd_parser(argc, argv);

  // dispatch to the correct function
  const auto &mode = MODES.find(cmd_parser.app_name);
  return (mode != MODES.end()) ? mode->second(cmd_parser) : 1;
}

int gibbs(const dd::CmdParser &args) {
  // number of NUMA nodes
  int n_numa_node = numa_max_node() + 1;
  // number of max threads per NUMA node
  int n_thread_per_numa = (sysconf(_SC_NPROCESSORS_CONF)) / (n_numa_node);

  if (!args.should_be_quiet) {
    std::cout << std::endl;
    std::cout << "#################MACHINE CONFIG#################"
              << std::endl;
    std::cout << "# # NUMA Node        : " << n_numa_node << std::endl;
    std::cout << "# # Thread/NUMA Node : " << n_thread_per_numa << std::endl;
    std::cout << "################################################"
              << std::endl;
    std::cout << std::endl;
    std::cout << args << std::endl;
  }

  FactorGraphDescriptor meta = read_meta(args.fg_file);
  if (!args.should_be_quiet) {
    std::cout << meta << std::endl;
  }

  // Run on NUMA node 0
  numa_run_on_node(0);
  numa_set_localalloc();

  // Load factor graph
  dprintf("Initializing factor graph...\n");
  dd::FactorGraph fg(meta);

  fg.load_variables(args.variable_file);
  fg.load_weights(args.weight_file);
  fg.load_domains(args.domain_file);
  fg.load_factors(args.factor_file);
  fg.safety_check();

  if (!args.should_be_quiet) {
    std::cout << "Printing FactorGraph statistics:" << std::endl;
    std::cout << fg << std::endl;
  }

  // Initialize Gibbs sampling application.
  DimmWitted dw(std::unique_ptr<CompactFactorGraph>(new CompactFactorGraph(fg)),
                fg.weights.get(), args);

  // learning
  dw.learn();

  // dump weights
  dw.dump_weights();

  // inference
  dw.inference();
  dw.aggregate_results_and_dump();

  return 0;
}

DimmWitted::DimmWitted(std::unique_ptr<CompactFactorGraph> p_cfg,
                       const Weight weights[], const CmdParser &opts)
    : weights(weights), opts(opts) {
  n_numa_nodes = numa_max_node() + 1;
  if (opts.n_datacopy > 0 && opts.n_datacopy < n_numa_nodes) {
    n_numa_nodes = opts.n_datacopy - 1;
  }

  // max possible threads per NUMA node
  n_thread_per_numa = (sysconf(_SC_NPROCESSORS_CONF)) / n_numa_nodes;
  // n_thread_per_numa = 1;

  // copy factor graphs
  for (int i = 0; i < n_numa_nodes; ++i) {
    numa_run_on_node(i);
    numa_set_localalloc();

    std::cout << "CREATE CFG ON NODE ..." << i << std::endl;
    sampler.push_back(GibbsSampler(
        std::unique_ptr<CompactFactorGraph>(
            i == 0 ? p_cfg.release() : new CompactFactorGraph(*p_cfg)),
        weights, n_thread_per_numa /* TODO fold this into opts */, i, opts));
  }
}

void DimmWitted::inference() {
  const int n_epoch = compute_n_epochs(opts.n_inference_epoch);
  const int nvar = sampler[0].fg.size.num_variables;
  const bool should_show_progress = !opts.should_be_quiet;
  Timer t_total, t;

  for (auto &s : sampler) s.infrs.clear_variabletally();

  // inference epochs
  for (int i_epoch = 0; i_epoch < n_epoch; ++i_epoch) {
    if (should_show_progress) {
      std::cout << std::setprecision(2) << "INFERENCE EPOCH "
                << i_epoch * n_numa_nodes << "~"
                << ((i_epoch + 1) * n_numa_nodes) << "...." << std::flush;
    }

    // restart timer
    t.restart();

    // sample
    for (auto &s : sampler) s.sample(i_epoch);

    // wait for samplers to finish
    for (auto &s : sampler) s.wait();

    double elapsed = t.elapsed();
    if (should_show_progress) {
      std::cout << "" << elapsed << " sec.";
      std::cout << "," << (nvar * n_numa_nodes) / elapsed << " vars/sec"
                << std::endl;
    }
  }

  double elapsed = t_total.elapsed();
  std::cout << "TOTAL INFERENCE TIME: " << elapsed << " sec." << std::endl;
}

void DimmWitted::learn() {
  InferenceResult &infrs = sampler[0].infrs;

  const int n_epoch = compute_n_epochs(opts.n_learning_epoch);
  const int nvar = infrs.nvars;
  const int nweight = infrs.nweights;
  const double decay = opts.decay;
  const bool should_show_progress = !opts.should_be_quiet;
  Timer t_total, t;

  double current_stepsize = opts.stepsize;
  const std::unique_ptr<double[]> prev_weights(new double[nweight]);
  memcpy(prev_weights.get(), infrs.weight_values.get(),
         sizeof(*prev_weights.get()) * nweight);

  // learning epochs
  for (int i_epoch = 0; i_epoch < n_epoch; ++i_epoch) {
    if (should_show_progress) {
      std::cout << std::setprecision(2) << "LEARNING EPOCH "
                << i_epoch * n_numa_nodes << "~"
                << ((i_epoch + 1) * n_numa_nodes) << "...." << std::flush;
    }

    t.restart();

    // performs stochastic gradient descent with sampling
    for (auto &s : sampler) s.sample_sgd(current_stepsize);

    // wait the samplers to finish
    for (auto &s : sampler) s.wait();

    // sum the weights and store in the first factor graph
    // the average weights will be calculated and assigned to all factor graphs
    for (int i = 1; i < n_numa_nodes; ++i)
      infrs.merge_weights_from(sampler[i].infrs);
    infrs.average_regularize_weights(current_stepsize);
    for (int i = 1; i < n_numa_nodes; ++i)
      infrs.copy_weights_to(sampler[i].infrs);

    // calculate the norms of the difference of weights from the current epoch
    // and last epoch
    double lmax = -INFINITY;
    double l2 = 0.0;
    for (int j = 0; j < nweight; ++j) {
      double diff = fabs(prev_weights[j] - infrs.weight_values[j]);
      prev_weights[j] = infrs.weight_values[j];
      l2 += diff * diff;
      if (lmax < diff) lmax = diff;
    }
    lmax /= current_stepsize;

    double elapsed = t.elapsed();
    if (should_show_progress) {
      std::cout << "" << elapsed << " sec.";
      std::cout << "," << (nvar * n_numa_nodes) / elapsed << " vars/sec."
                << ",stepsize=" << current_stepsize << ",lmax=" << lmax
                << ",l2=" << sqrt(l2) / current_stepsize << std::endl;
    }

    current_stepsize *= decay;
  }

  double elapsed = t_total.elapsed();
  std::cout << "TOTAL LEARNING TIME: " << elapsed << " sec." << std::endl;
}

void DimmWitted::dump_weights() {
  // learning weights snippets
  const InferenceResult &infrs = sampler[0].infrs;

  if (!opts.should_be_quiet) infrs.show_weights_snippet(std::cout);

  // dump learned weights
  std::string filename_text(opts.output_folder +
                            "/inference_result.out.weights.text");
  std::cout << "DUMPING... TEXT    : " << filename_text << std::endl;
  std::ofstream fout_text(filename_text);
  infrs.dump_weights_in_text(fout_text);
  fout_text.close();
}

void DimmWitted::aggregate_results_and_dump() {
  InferenceResult &infrs = sampler[0].infrs;

  // aggregate assignments across all possible worlds
  for (int i = 1; i < n_numa_nodes; ++i)
    infrs.aggregate_marginals_from(sampler[i].infrs);

  if (!opts.should_be_quiet) infrs.show_marginal_snippet(std::cout);

  // dump inference results
  std::string filename_text(opts.output_folder + "/inference_result.out.text");
  std::cout << "DUMPING... TEXT    : " << filename_text << std::endl;
  std::ofstream fout_text(filename_text);
  infrs.dump_marginals_in_text(fout_text);
  fout_text.close();

  if (!opts.should_be_quiet) infrs.show_marginal_histogram(std::cout);
}

// compute number of NUMA-aware epochs for learning or inference
int DimmWitted::compute_n_epochs(int n_epoch) {
  return std::ceil((double)n_epoch / n_numa_nodes);
}

}  // namespace dd
