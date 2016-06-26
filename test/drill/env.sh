#!/usr/bin/env bash
# A script that sets up environment for testing DeepDive against Drill 

# load common test environment settings
. "${BASH_SOURCE%/*}"/../env.sh

# initialize database
: ${DEEPDIVE_DB_URL:=drill://${TEST_POSTGRES_DBHOST:-${TEST_DBHOST:-localhost}}/${TEST_DBNAME:-deepdive_test_$USER}}
. load-db-driver.sh 2>/dev/null

# environment variables expected by Scala test code
export PGDATABASE=$DBNAME  # for testing to work with null settings
export DBCONNSTRING=jdbc:drill://$PGHOST:$PGPORT/$DBNAME
export DEEPDIVE_TEST_ENV="drill"
# for compatibility with psql/mysql generic tests. Should get rid of "PG" stuff.
export DBHOST=$PGHOST
export DBPORT=$PGPORT
export DBPASSWORD=$PGPASSWORD
export DBUSER=$PGUSER

# incremental active
export DEEPDIVE_ACTIVE_INCREMENTAL_VARIABLES="r1"
export DEEPDIVE_ACTIVE_INCREMENTAL_RULES="testFactor"
export BASEDIR="$PWD"/out  # XXX out/ under the working directory happens to be DeepDive's default output path
