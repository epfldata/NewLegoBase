-- subq -> make_domain


CREATE STREAM R(A int, B int) 
  FROM FILE '../../experiments/data/simple/tiny/r.dat' LINE DELIMITED
  csv ();

SELECT * FROM (SELECT COUNT(*) FROM R) n;
