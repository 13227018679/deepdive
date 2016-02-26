#!/usr/bin/env python

from deepdive import *

@tsv_extractor
@returns(lambda
v1 = "bigint",
v2 = "float",
v3 = "boolean",
v4 = "boolean", 
v5 = "text",
v6 = "text",
v7 = "text",
v8 = "text",
v9 = "text",
v10 = "text",
v11 = "text",
v12 = "int[]",
v13 = "float[]",
v14 = "text[]",
v15 = "text[]",
v16 = "text[]",
v17 = "text[]",
:[])

def identity_func(
v1 = "bigint",
v2 = "float",
v3 = "boolean",
v4 = "boolean",
v5 = "text",
v6 = "text",
v7 = "text",
v8 = "text",
v9 = "text",
v10 = "text",
v11 = "text",
v12 = "int[]",
v13 = "float[]",
v14 = "text[]",
v15 = "text[]",
v16 = "text[]",
v17 = "text[]",
 ):
 yield [v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15, v16, v17]

