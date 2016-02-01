#!/usr/bin/env bats
. "$BATS_TEST_DIRNAME"/env.sh

cd "$BATS_TMPDIR"

check_correct_output() {
    local m=$1 n=$2
    tee \
        >(wc -l >num_lines) \
        >(awk 'BEGIN{ m = 0 } { m += $1 } END { print m }' >sum) \
        >(awk '{ ++lineno; if ($1 != ($3 - $2 + 1)) { print lineno } }' >incorrect_lines) \
        #
    # check number of partitions
    local n_actual=$(cat num_lines; rm -f num_lines)
    echo >&2 num_output_partitions=$n_actual
    [[ $n_actual -eq $n ]]
    # check if they all sum up to m
    local sum=$(cat sum; rm -f sum)
    echo "$sum = $m ?"
    [[ $sum -eq $m ]]
    # check if any line contained incorrect ranges
    if [[ -s incorrect_lines ]]; then
        cat >&2 incorrect_lines; rm -f incorrect_lines
        false
    fi
}

@test "partition_integers usage" {
    ! partition_integers || false
    ! partition_integers 10 || false
    ! partition_integers 10 two || false
    ! partition_integers ten 2 || false
    ! partition_integers ten two || false
    partition_integers 10 2 >/dev/null
}

@test "partition_integers works" {
    for m in 0 10 100 131 437 1723; do
        for n in 2 5 7 11 102 135 207 1724; do
            echo "partitioning $m by $n"
            partition_integers $m $n | check_correct_output $m $n
        done
    done
}

@test "partition_integers fails on non-positive partitions" {
    for m in 10 100; do
        for n in 0 -2 -101; do
            echo "partitioning $m by $n"
            ! partition_integers $m $n >/dev/null || false
        done
    done
    for m in -200 -1000; do
        for n in 0 10 100 -2 -101; do
            echo "partitioning $m by $n"
            ! partition_integers $m $n >/dev/null || false
        done
    done
}
