# DimmWitted Factor Graph in Textual Format

DimmWitted provides a handy way to generate the custom binary format from text (TSV; tab-separated values).
`dw text2bin` and `dw bin2text` speaks the textual format described below.

TODO polish the following

## Weights TSV
* `weights*.tsv`
    1. wid, e.g., `100`
    2. is fixed (`1`) or not (`0`)
    3. weight value, e.g., `0`, `2.5`


## Variables TSV
* `variables*.tsv`
    1. vid, e.g., `0`
    2. is evidence (`0` or `1`)
    3. initial value
    4. variable type (`0` for Boolean, `1` for categorical)
    5. cardinality

## Domains TSV
* `domains*.tsv`
    1. vid, e.g., `0`
    2. cardinality, e.g., `3`.
    3. array of domain values, e.g., `{2,4,8}`.


## Factors TSV
* `factors*.text2bin-args`
    1. factor function id (See: [`FACTOR_FUNCTION_TYPE` enum](https://github.com/HazyResearch/sampler/blob/master/src/dstruct/factor_graph/factor.h))
    2. arity: how many variables are connected, e.g., `1` for unary and `2` for binary
    3. one or more flags to indicate which variables in corresponding position are negative (`0`) and positive (`1`)

* `factors*.tsv`
    1. vids: one or more depending on the given arity delimited by tabs, e.g., `0	1` for binary factors
    2. wids
        * when the factor function id is `FUNC_AND_CATEGORICAL`:
            2. number of weights, e.g., `3`
            3. num_vars columns of parallel var value arrays, e.g., `{1,2,3}	{2,3,4}`
            4. wid array, e.g., `{100,101,102}`
        * Otherwise:
            2. wid, e.g., `100`

