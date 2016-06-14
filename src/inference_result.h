#ifndef DIMMWITTED_INFERENCE_RESULT_H_
#define DIMMWITTED_INFERENCE_RESULT_H_

#include "variable.h"
#include "weight.h"
#include "cmd_parser.h"

namespace dd {

class CompiledFactorGraph;

/**
 * Encapsulates inference result statistics
 */
class InferenceResult {
 private:
  const CompiledFactorGraph &fg;
  const CmdParser &opts;
  int weight_values_normalizer;

 public:
  long nvars;     // number of variables
  long nweights;  // number of weights
  long ntallies;

  std::unique_ptr<int[]> multinomial_tallies;  // this might be slow...

  // array of sum of samples for each variable
  std::unique_ptr<double[]> agg_means;
  // array of number of samples for each variable
  std::unique_ptr<double[]> agg_nsamples;
  // assignment to variables, see variable.h for more detail
  std::unique_ptr<VariableValue[]> assignments_free;
  std::unique_ptr<VariableValue[]> assignments_evid;

  std::unique_ptr<double[]> weight_values;  // array of weight values
  std::unique_ptr<bool[]> weights_isfixed;  // array of whether weight is fixed

  InferenceResult(const CompiledFactorGraph &fg, const Weight weights[],
                  const CmdParser &opts);

  // copy constructor
  InferenceResult(const InferenceResult &other);

  void merge_weights_from(const InferenceResult &other);
  void average_regularize_weights(double current_stepsize);
  void copy_weights_to(InferenceResult &other) const;

  void clear_variabletally();
  void aggregate_marginals_from(const InferenceResult &other);
  void show_marginal_snippet(std::ostream &output) const;
  void show_marginal_histogram(std::ostream &output) const;
  void dump_marginals(std::ostream &text_output) const;

 private:
  InferenceResult(const CompiledFactorGraph &fg, const CmdParser &opts);
};

}  // namespace dd

#endif  // DIMMWITTED_INFERENCE_RESULT_H_
