#!/usr/bin/env bats
# Tests for `deepdive sql eval` formats

. "$BATS_TEST_DIRNAME"/env.sh >&2
PATH="$DEEPDIVE_SOURCE_ROOT/util/test:$PATH"

@test "$DBVARIANT deepdive sql eval format=json" {
    q="SELECT 123::bigint as i
            , 45.678 as float
            , TRUE as t
            , FALSE as f
            , 'foo bar baz'::text AS s
            , NULL::text AS n
            , ARRAY[1,2,3] AS num_arr
            , ARRAY[1.2,3.45,67.890] AS float_arr
            , ARRAY[ 'easy'
                   , '123'
                   , 'abc'
                   , 'two words'
                   ] as text_arr
            , ARRAY[ '.'
                   , ','
                   , '.'
                   , '{'
                   , '}'
                   , '['
                   , ']'
                   , '('
                   , ')'
                   , '\"'
                   , '\\'
                   ] AS punctuations
            , ARRAY[ 'asdf  qwer"$'\t'"zxcv"$'\n'"1234'
                   , '\"I''m your father,\" said Darth Vader.'
                   , '"'{"csv in a json": "a,b c,\",\",\"line '\'\''1'\'\'$'\n''line \"\"2\"\"",  "foo":123,'$'\n''"bar":45.678}'"'
                   ] AS torture_arr
    "
    expected='
        {
          "i": 123,
          "float": 45.678,
          "t": true,
          "f": false,
          "s": "foo bar baz",
          "n": null,
          "num_arr": [
            1,
            2,
            3
          ],
          "float_arr": [
            1.2,
            3.45,
            67.89
          ],
          "text_arr": [
            "easy",
            "123",
            "abc",
            "two words"
          ],
          "punctuations": [
            ".",
            ",",
            ".",
            "{",
            "}",
            "[",
            "]",
            "(",
            ")",
            "\"",
            "\\"
          ],
          "torture_arr": [
            "asdf  qwer\tzxcv\n1234",
            "\"I'\''m your father,\" said Darth Vader.",
            "{\"csv in a json\": \"a,b c,\\\",\\\",\\\"line '\''1'\''\nline \\\"\\\"2\\\"\\\"\",  \"foo\":123,\n\"bar\":45.678}"
          ]
        }
    '
    actual=$(db-query "$q" json 0) # TODO change to: deepdive sql eval "$q" format=json
    # test whether two JSONs are the same
    compare-json "$expected" "$actual"
}
