/**
 * Unit tests for gibbs sampling
 *
 * Author: Feiran Wang
 */

#include "single_thread_sampler.h"
#include "factor_graph.h"
#include "dimmwitted.h"
#include <gtest/gtest.h>
#include <fstream>

namespace dd {

// test fixture
class SamplerTest : public testing::Test {
 protected:
  dd::CompiledFactorGraph cfg;
  dd::SingleThreadSampler sampler;

  SamplerTest()
      : cfg(dd::CompiledFactorGraph({18, 18, 1, 18})),
        sampler(dd::SingleThreadSampler(&cfg, false, 0, false)) {}

  virtual void SetUp() {
    const char *argv[] = {
        "dw",      "gibbs",
        "-w",      "./test/biased_coin/graph.weights",
        "-v",      "./test/biased_coin/graph.variables",
        "-f",      "./test/biased_coin/graph.factors",
        "-m",      "./test/biased_coin/graph.meta",
        "-o",      ".",
        "-l",      "100",
        "-i",      "100",
        "-s",      "1",
        "--alpha", "0.1",
    };
    dd::CmdParser cmd_parser(sizeof(argv) / sizeof(*argv), argv);
    dd::FactorGraph fg({18, 18, 1, 18});
    fg.load_variables(cmd_parser.variable_file);
    fg.load_weights(cmd_parser.weight_file);
    fg.load_domains(cmd_parser.domain_file);
    fg.load_factors(cmd_parser.factor_file);
    fg.safety_check();

    fg.compile(cfg);
  }
};

// test for sample_sgd_single_variable
// the pseudo random number has been precalculated...
TEST_F(SamplerTest, sample_sgd_single_variable) {
  cfg.infrs->assignments_free[cfg.variables[0].id] = 1;
  cfg.stepsize = 0.1;
  for (int i = 0; i < 3; i++) {
    sampler.p_rand_seed[i] = 1;
  }

  sampler.sample_sgd_single_variable(0);
  EXPECT_EQ(cfg.infrs->weight_values[0], 0.1);

  sampler.sample_sgd_single_variable(0);
  EXPECT_EQ(cfg.infrs->weight_values[0], 0.1);

  sampler.sample_sgd_single_variable(0);
  EXPECT_EQ(cfg.infrs->weight_values[0], 0.1);
}

// test for sample_single_variable
TEST_F(SamplerTest, sample_single_variable) {
  cfg.infrs->assignments_free[cfg.variables[0].id] = 1;
  for (int i = 0; i < 3; i++) {
    sampler.p_rand_seed[i] = 1;
  }
  cfg.infrs->weight_values[0] = 2;

  sampler.sample_single_variable(0);
  EXPECT_EQ(cfg.infrs->assignments_evid[0], 1);

  sampler.sample_single_variable(10);
  EXPECT_EQ(cfg.infrs->assignments_evid[10], 1);

  cfg.infrs->weight_values[0] = 20;
  sampler.sample_single_variable(11);
  EXPECT_EQ(cfg.infrs->assignments_evid[11], 1);

  sampler.sample_single_variable(12);
  EXPECT_EQ(cfg.infrs->assignments_evid[12], 1);
}

}  // namespace dd
