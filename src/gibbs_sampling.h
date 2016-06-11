#ifndef DIMMWITTED_GIBBS_SAMPLING_H_
#define DIMMWITTED_GIBBS_SAMPLING_H_

#include "cmd_parser.h"
#include "factor_graph.h"
#include <iostream>

namespace dd {

/**
 * Class for (NUMA-aware) gibbs sampling
 *
 * This class encapsulates gibbs learning and inference, and dumping results.
 * Note the factor graph is copied on each NUMA node.
 */
class GibbsSampling {
 public:
  const Weight* const weights;

  // command line parser
  const CmdParser* const p_cmd_parser;

  // the highest node number available
  // actually, number of NUMA nodes = n_numa_nodes + 1
  int n_numa_nodes;

  // number of threads per NUMA node
  int n_thread_per_numa;

  // factor graph copies
  std::vector<std::unique_ptr<CompiledFactorGraph>> factorgraphs;

  // sample evidence in inference
  bool sample_evidence;

  // burn-in period
  int burn_in;

  // whether sample non-evidence during learning
  bool learn_non_evidence;

  /**
   * Constructs GibbsSampling class with given factor graph, command line
   * parser,
   * and number of data copies. Allocate factor graph to NUMA nodes.
   * n_datacopy number of factor graph copies. n_datacopy = 1 means only
   * keeping one factor graph.
   */
  GibbsSampling(std::unique_ptr<CompiledFactorGraph> p_cfg,
                const Weight weights[], const CmdParser* p_cmd_parser,
                bool sample_evidence, int burn_in, bool learn_non_evidence,
                int n_datacopy);

  /**
   * Performs learning
   * n_epoch number of epochs. A epoch is one pass over data
   * n_sample_per_epoch not used any more.
   * stepsize starting step size for weight update (aka learning rate)
   * decay after each epoch, the stepsize is updated as stepsize = stepsize *
   * decay
   * reg_param regularization parameter
   * is_quiet whether to compress information display
   */
  void learn(const int& n_epoch, const int& n_sample_per_epoch,
             const double& stepsize, const double& decay,
             const double reg_param, const bool is_quiet,
             const regularization reg);

  /**
   * Performs inference
   * n_epoch number of epochs. A epoch is one pass over data
   * is_quiet whether to compress information display
   */
  void inference(const int& n_epoch, const bool is_quiet);

  /**
   * Aggregates results from different NUMA nodes
   * Dumps the inference result for variables
   * is_quiet whether to compress information display
   */
  void aggregate_results_and_dump(const bool is_quiet);

  /**
   * Dumps the learned weights
   * is_quiet whether to compress information display
   */
  void dump_weights(const bool is_quiet);
};

}  // namespace dd

#endif  // DIMMWITTED_GIBBS_SAMPLING_H_
