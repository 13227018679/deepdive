a(k int).
b(k int, p text, q text, r int).
c(s text, n int, t text).

Q("test" :: TEXT, 123, id) :- a(id), b(id, x,y,z), c(x || y,10,"foo"), z>100.