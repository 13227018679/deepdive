# DimmWitted tests

## Running all tests

```bash
bats *.bats
```

## End to end test directories

### Factor graph TSV format
* `variables*.tsv`
    1. vid, e.g., `0`
    2. is evidence (`0` or `1`)
    3. initial value
    4. variable type (`0` for Boolean, `1` for categorical/multinomial)
    5. cardinality

* `domains*.tsv`
    1. vid, e.g., `0`
    2. cardinality, e.g., `3`.
    3. array of domain values, e.g., `{2,4,8}`.

* `factors*.text2bin-args`
    1. factor function id (See: [`FACTOR_FUNCTION_TYPE` enum](https://github.com/HazyResearch/sampler/blob/master/src/dstruct/factor_graph/factor.h))
    2. arity: how many variables are connected, e.g., `1` for unary and `2` for binary
    3. reserved for incremental (use `0`)
    4. one or more flags to indicate which variables in corresponding position are negative (`0`) and positive (`1`)

* `factors*.tsv`
    1. vids: one or more depending on the given arity delimited by tabs, e.g., `0	1` for binary factors
    2. wids
        * when the factor function id is `FUNC_AND_CATEGORICAL`:
            2. number of weights, e.g., `3`
            3. wid array, e.g., `{100,101,102}`
        * Otherwise:
            2. wid, e.g., `100`

* `weights*.tsv`
    1. wid, e.g., `100`
    2. is fixed (`1`) or not (`0`)
    3. weight value, e.g., `0`, `2.5`

