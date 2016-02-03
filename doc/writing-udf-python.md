---
layout: default
title: Writing user-defined functions in Python
---

# Writing user-defined functions in Python

DeepDive supports *user-defined functions* (UDFs) for data processing, in addition to the [normal derivation rules in DDlog](writing-dataflow-ddlog.md).
These functions are supposed to take as input tab-separated values ([TSV or PostgreSQL's text format](http://www.postgresql.org/docs/9.1/static/sql-copy.html#AEN64351)) per line and output zero or more lines also containing values separated by tab.
DeepDive provides a handy Python library for quickly writing the UDFs in a clean way as well as parsing and formating such input/output.
This page describes DeepDive's recommended way of writing UDFs in Python as well as how such UDFs are used in the DDlog program.
However, DeepDive is language agnostic when it comes to UDFs, i.e., a UDF can be implemented in any programming language as long as it can be executed from the command line, reads TSV lines correctly from standard input, and writes TSV to standard output.


## Using UDFs in DDlog

To use user-defined functions in DDlog, they must be declared first then called using special syntax.

First, let's declare the schema of the two relations for our running example.

```
article(
    id     int,
    url    text,
    title  text,
    author text,
    words  text[]
).

classification(
    article_id int,
    topic      text
).
```

Suppose we want to write a simple UDF to classify each `article` into different topics, adding tuples to the relation `classification`.


### Function Declarations
A function declaration states what input it takes and what output it returns as well as the implementation details.
In our example, suppose we will use only the `author` and `words` of each `article` to output the topic identified by its `id`, and the implementation will be kept in an executable file called `udf/classify.py`.
The exact declaration for such function is shown below.

```
function classify_articles over (id int, author text, words text[])
    returns (article_id int, topic text)
    implementation "udf/classify.py" handles tsv lines.
```

Notice that the column definitions of relation `classification` are repeated in the `returns` clause.
This can be omitted by using the `rows like` syntax as shown below.

```
function classify_articles over (id int, author text, words text[])
    return rows like classification
    implementation "udf/classify.py" handles tsv lines.
```

Also note that the function input is similar to the `articles` relation, but some columns are missing.
This is because the function will not use the rest of the columns as mentioned before, and it is a good idea to drop unnecessary values for efficiency.
Next section shows how such input tuples can be derived and fed into a function.


### Function Call Rules
The function declared above can be called to derive tuples for another relation of the output type.
The input tuples for the function call are derived using a syntax similar to a [normal derivation rule](writing-dataflow-ddlog.md).
For example, the rule shown below calls the `classify_articles` function to fill the `classification` relation using a subset of columns from the `articles` relation.

```
classification += classify_articles(id, author, words) :-
    article(id, _, _, author, words).
```

Function call rules can be thought as a special case of normal derivation rules with different head syntax, where instead of the head relation name, there is a function name after the name of the relation being derived separated by `+=`.


## Writing UDFs in Python

DeepDive provides a templated way to write user-defined functions in Python.
It provides several [Python function decorators](https://www.python.org/dev/peps/pep-0318/) to simplify parsing and formatting input and output respectively.
The [Python generator](https://www.python.org/dev/peps/pep-0255/) to be called upon every input row should be decorated with `@tsv_extractor`, i.e., before the `def` line `@tsv_extractor` should be placed.
(A Python generator is a Python function that uses `yield` instead of `return` to produce multiple results per call.)
The input and output column types expected by the generator can be declared using the `@over` and  `@returns` decorators, which tells how the input parser and output formatter should behave.

Let's look at a realistic example to describe how exactly they should be used in the code.
Below is a near-complete code for the `udf/classify.py` declared as the implementation for the DDlog function `classify_articles`.

```
#!/usr/bin/env python
from deepdive import *  # Required for @tsv_extractor, @over, and @returns

compsci_authors = [...]
bio_authors     = [...]
bio_words       = [...]

@tsv_extractor  # Declares the generator below as the main function to call
@returns(lambda # Declares the types of output columns as declared in DDlog
        article_id = "int",
        topic      = "text",
    :[])
def classify(   # The input types can be declared directly on each parameter as its default value
        article_id = "int",
        author     = "text",
        words      = "text[]",
    ):
    """
    Classify articles by assigning topics.
    """
    num_topics = 0

    if author in compsci_authors:
        num_topics += 1
        yield [article_id, "cs"]

    if author in bio_authors:
        num_topics += 1
        yield [article_id, "bio"]
    elif any (word for word in bio_words if word in words):
        num_topics += 1
        yield [article_id, "bio"]

    if num_topics == 0:
        yield [article_id, None]
```

This simple UDF checks to see if the authors of the article are in a known set and assigns a topic based on that.
If the author is not recognized, we try to look for words that appear in a predefined set.
Finally, if nothing matches, we simply put it into another catch-all topic.
Note that the topics themselves here are completely user defined.

Notice that to use these Python decorators you'll need to have `from deepdive import *`.
Also notice that the types of input columns can be declared as default values for the generator parameters, instead of using the `@over` decorator in the same way as `@returns`.


### @tsv_extractor decorator

The `@tsv_extractor` decorator should be placed as the first decorator for the main generator that will take one input row at a time and `yield` zero or more output rows as list of values.
This basically lets DeepDive know which function to call when running the Python program.

#### Caveats
Generally, this generator should be placed at the bottom of the program unless there are some cleanup or tear-down tasks to do after processing all the input rows.
Any function or variable used by the decorated generator should be appear before it as the `@tsv_extractor` decorator will immediately start parsing input and calling the generator.
The generator should not `print` or `sys.stdout.write` anything as that will corrupt the standard output.
Instead, `print >>sys.stderr` or `sys.stderr.write` can be used for logging useful information.

### @over and @returns decorators

To parse the input TSV lines correctly into Python values and format the values generated by the `@tsv_extractor` correctly in TSV, the column types need to be written down in the Python program.
They should be consistent with the function declaration in DDlog.
Arguments to `@over` and `@returns` decorators can be either a list of name and type pairs or a function with all parameters having their default values set as its type.
The use of `lambda` is preferred because the list of pairs require more symbols that clutter the declaration, e.g., compare above with `@returns([("article_id", "int"), ("topic", "text")])`.
The reason `dict(column="type", ... )` or `{ "column": "type", ... }` do not work is because Python forgets the order of the columns with those syntax, which is crucial for the TSV parser and formatter.
The passed function is never called so the body can be left as any value, such as empty list (`[]`).


#### Parameter default values instead of @over

In fact, the types for input columns can be declared directly in the `@tsv_extractor` generator's signature as default values for the parameters as shown in the example above, instead of having to repeat them separately in a `@over` decorator.
This is the preferred way as it helps avoid redundant declarations.



## Running and debugging UDFs

Once a first cut of the UDF is written, it can be run using the `deepdive do` and `deepdive redo` commands.
For example, the `classify_articles` function in our running example to derive the `classification` relation can be run with the following command:

```bash
deepdive redo classification
```

This will invoke the Python program `udf/classify.py`, giving as input the TSV rows holding three columns of the `article` table, and taking its output to add rows to the `classification` table in the database.

There are dedicated pages describing more details about [running these UDFs](ops-execution.md) and [debugging these UDFs](debugging-udf.md).


<!-- TODO Mention deepdive testfire or deepdive check here once it's ready -->
