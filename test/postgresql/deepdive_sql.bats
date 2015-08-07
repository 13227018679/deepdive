#!/usr/bin/env bats
# Tests for `deepdive sql` command

. "$BATS_TEST_DIRNAME"/env.sh >&2
PATH="$DEEPDIVE_SOURCE_ROOT/util/test:$PATH"

@test "$DBVARIANT deepdive sql works" {
    result=$(deepdive sql "
        CREATE TEMP TABLE foo(a INT);
        INSERT INTO foo VALUES (1), (2), (3), (4);
        COPY(SELECT SUM(a) AS sum FROM foo) TO STDOUT
        ")
    [[ $result = 10 ]]
}

@test "$DBVARIANT deepdive sql eval works" {
    [[ $(deepdive sql eval "SELECT 1") = 1 ]]
}

@test "$DBVARIANT deepdive sql fails on bad SQL" {
    ! deepdive sql "
        CREATE;
        INSERT;
        SELECT 1
        "
}

@test "$DBVARIANT deepdive sql stops on error" {
    ! deepdive sql "
        CREATE TEMP TABLE foo(id INT);
        INSERT INTO foo SELECT id FROM __non_existent_table__$$;
        SELECT 1
        "
}

@test "$DBVARIANT deepdive sql eval fails on empty SQL" {
    ! deepdive sql eval ""
}

@test "$DBVARIANT deepdive sql eval fails on bad SQL" {
    ! deepdive sql eval "SELECT FROM WHERE"
}

@test "$DBVARIANT deepdive sql eval fails on non-SELECT SQL" {
    ! deepdive sql eval "CREATE TEMP TABLE foo(id INT)"
}

@test "$DBVARIANT deepdive sql eval fails on multiple SQL" {
    ! deepdive sql eval "CREATE TEMP TABLE foo(id INT); SELECT 1"
}

@test "$DBVARIANT deepdive sql eval fails on trailing semicolon" {
    ! deepdive sql eval "SELECT 1;"
}

# a nasty SQL input to test output formatters
NastySQL="
       SELECT 123::bigint as i
            , 45.678 as float
            , TRUE as t
            , FALSE as f
            , 'foo bar baz'::text AS s
            , ''::text AS empty_str
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
                   , E'\\\\' -- XXX Greenplum doesn't like the simpler '\\'
                   ] AS punctuations
            , ARRAY[ 'asdf  qwer"$'\t'"zxcv"$'\n'"1234'
                   , '\"I''m your father,\" said Darth Vader.'
                   , E'"'{"csv in a json": "a,b c,\\",\\",\\"line '\'\''1'\'\'$'\n''line \\"\\"2\\"\\"",  "foo":123,'$'\n''"bar":45.678}'"'
                     -- XXX Greenplum (or older PostgreSQL 8.x) treats backslashes as escapes in strings '...'
                     -- and E'...' is a consistent way to write backslashes in string literal across versions
                   ] AS torture_arr
    "

# expected TSV output
NastyTSVHeader=                         NastyTSV=
NastyTSVHeader+=$'\t''i'                NastyTSV+=$'\t''123'
NastyTSVHeader+=$'\t''float'            NastyTSV+=$'\t''45.678'
NastyTSVHeader+=$'\t''t'                NastyTSV+=$'\t''t'
NastyTSVHeader+=$'\t''f'                NastyTSV+=$'\t''f'
NastyTSVHeader+=$'\t''s'                NastyTSV+=$'\t''foo bar baz'
NastyTSVHeader+=$'\t''empty_str'        NastyTSV+=$'\t'''
NastyTSVHeader+=$'\t''n'                NastyTSV+=$'\t''\N'
NastyTSVHeader+=$'\t''num_arr'          NastyTSV+=$'\t''{1,2,3}'
NastyTSVHeader+=$'\t''float_arr'        NastyTSV+=$'\t''{1.2,3.45,67.890}'
NastyTSVHeader+=$'\t''text_arr'         NastyTSV+=$'\t''{easy,123,abc,"two words"}'
NastyTSVHeader+=$'\t''punctuations'     NastyTSV+=$'\t''{.,",",.,"{","}",[,],(,),"\\"","\\\\"}'
NastyTSVHeader+=$'\t''torture_arr'      NastyTSV+=$'\t''{"asdf  qwer\tzxcv\n1234"'
                                        NastyTSV+=',"\\"I'\''m your father,\\" said Darth Vader."'
                                        NastyTSV+=',"{\\"csv in a json\\": \\"a,b c,\\\\\\",\\\\\\",\\\\\\"line '\''1'\''\nline \\\\\\"\\\\\\"2\\\\\\"\\\\\\"\\",  \\"foo\\":123,\n\\"bar\\":45.678}"'
                                        NastyTSV+='}'
NastyTSVHeader=${NastyCSVHeader#$'\t'}  NastyTSV=${NastyTSV#$'\t'}  # strip the first delimiter

# expected CSV output and header
NastyCSVHeader=                     NastyCSV=
NastyCSVHeader+=',i'                NastyCSV+=',123'
NastyCSVHeader+=',float'            NastyCSV+=',45.678'
NastyCSVHeader+=',t'                NastyCSV+=',t'
NastyCSVHeader+=',f'                NastyCSV+=',f'
NastyCSVHeader+=',s'                NastyCSV+=',foo bar baz'
NastyCSVHeader+=',empty_str'        NastyCSV+=',""'
NastyCSVHeader+=',n'                NastyCSV+=','
NastyCSVHeader+=',num_arr'          NastyCSV+=',"{1,2,3}"'
NastyCSVHeader+=',float_arr'        NastyCSV+=',"{1.2,3.45,67.890}"'
NastyCSVHeader+=',text_arr'         NastyCSV+=',"{easy,123,abc,""two words""}"'
NastyCSVHeader+=',punctuations'     NastyCSV+=',"{.,"","",.,""{"",""}"",[,],(,),""\"""",""\\""}"'
NastyCSVHeader+=',torture_arr'      NastyCSV+=',"{""asdf  qwer'$'\t''zxcv'$'\n''1234""'
                                    NastyCSV+=',""\""I'\''m your father,\"" said Darth Vader.""'
                                    NastyCSV+=',""{\""csv in a json\"": \""a,b c,\\\"",\\\"",\\\""line '\''1'\'$'\n''line \\\""\\\""2\\\""\\\""\"",  \""foo\"":123,'$'\n''\""bar\"":45.678}""'
                                    NastyCSV+='}"'
NastyCSVHeader=${NastyCSVHeader#,}  NastyCSV=${NastyCSV#,}  # strip the first delimiter

# expected JSON output
NastyJSON='
        {
          "i": 123,
          "float": 45.678,
          "t": true,
          "f": false,
          "s": "foo bar baz",
          "empty_str": "",
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

# tests with formats

@test "$DBVARIANT deepdive sql eval format=tsv works" {
    actual=$(deepdive sql eval "$NastySQL" format=tsv)
    diff -u <(echo "$NastyTSV")                         <(echo "$actual")
}

@test "$DBVARIANT deepdive sql eval format=tsv header=1 works" {
    skip  # XXX psql does not support HEADER for FORMAT text
    actual=$(deepdive sql eval "$NastySQL" format=tsv header=1)
    diff -u <(echo "$NastyTSVHeader"; echo "$NastyTSV") <(echo "$actual")
}

@test "$DBVARIANT deepdive sql eval format=csv works" {
    actual=$(deepdive sql eval "$NastySQL" format=csv)
    diff -u <(echo "$NastyCSV")                         <(echo "$actual")
}

@test "$DBVARIANT deepdive sql eval format=csv header=1 works" {
    actual=$(deepdive sql eval "$NastySQL" format=csv header=1)
    diff -u <(echo "$NastyCSVHeader"; echo "$NastyCSV") <(echo "$actual")
}

@test "$DBVARIANT deepdive sql eval format=json works" {
    actual=$(deepdive sql eval "$NastySQL" format=json)
    # test whether two JSONs are the same
    compare_json "$NastyJSON" "$actual"
}
