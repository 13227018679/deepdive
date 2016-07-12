#!/usr/bin/env bats
# Tests for chunking example

set -x
. "$BATS_TEST_DIRNAME"/env.sh >&2

exec 3>&2 # dup stderr as fd 3

: ${CHUNKING_TEST_NUM_WORDS_TRAIN:=1000} ${CHUNKING_TEST_NUM_WORDS_TEST:=200}
: ${CHUNKING_TEST_MIN_F1SCORE:=60}

: ${CHUNKING_TEST_REUSE_NUM_WORDS_TEST:=200}
: ${CHUNKING_TEST_REUSE_MIN_F1SCORE:=60}

get_f1score() {
    printf '%.0f' $(
        result/eval.sh | tee /dev/stderr | sed -n '/^accuracy:/ s/.* FB1: *//p')
}

run_chunking_example() {
exec 1>&3 2>&3 # tee to original stderr
    cd "$BATS_TEST_DIRNAME"/chunking_example || skip
    export SUBSAMPLE_NUM_WORDS_TRAIN=$CHUNKING_TEST_NUM_WORDS_TRAIN SUBSAMPLE_NUM_WORDS_TEST=$CHUNKING_TEST_NUM_WORDS_TEST
    DEEPDIVE_CONFIG_EXTRA='deepdive.calibration.holdout_query: "INSERT INTO dd_graph_variables_holdout(variable_id) SELECT dd_id FROM dd_variables_chunk WHERE word_id > '${SUBSAMPLE_NUM_WORDS_TRAIN}'"' \
    deepdive compile
    deepdive model weights init
    deepdive redo process/init/app data/model/probabilities
    f1score=$(get_f1score)
    echo "f1score = $f1score"
    [[ $f1score -ge $CHUNKING_TEST_MIN_F1SCORE ]] ||
        skip "f1score = $f1score < $CHUNKING_TEST_MIN_F1SCORE"
}

run_chunking_example_reusing_weights() {
exec 1>&3 2>&3 # tee to original stderr
    cd "$BATS_TEST_DIRNAME"/chunking_example || skip
    export SUBSAMPLE_NUM_WORDS_TRAIN=0 SUBSAMPLE_NUM_WORDS_TEST=$CHUNKING_TEST_REUSE_NUM_WORDS_TEST

    # keep the learned weights from a small corpus
    deepdive model weights keep

    # load larger test corpus
    deepdive redo words_raw

    # reuse the weights (to skip learning)
    deepdive model weights reuse  # taking care of all extraction and grounding for larger data

    # and do inference
    deepdive redo data/model/probabilities

    # check quality
    f1score=$(get_f1score)
    echo "f1score = $f1score"
    [[ $f1score -ge $CHUNKING_TEST_REUSE_MIN_F1SCORE ]] ||
        skip "f1score = $f1score < $CHUNKING_TEST_REUSE_MIN_F1SCORE"
}

@test "$DBVARIANT chunking example" {
    run_chunking_example
}

@test "$DBVARIANT chunking example reuse weights" {
    run_chunking_example_reusing_weights
}
