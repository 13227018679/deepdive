R(a int, b int).
S(a int, b int).
Q(x int).

Q(y) :- R(x, y); R(x, y), S(y, z).