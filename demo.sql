-- select * from r;
-- select * from s;
-- select * from clean_r;
-- explain select * from r;
-- explain select * from clean_r;
-- select * from clean_r where b = 2;
-- select * from clean_r where b <> 2;
-- explain select * from clean_r where b = 2;
-- select a,d from clean_r r, s where r.b = s.b;
explain select a,d from clean_r r, s where r.b = s.b;
-- select a,d,r.b as b from clean_r r, s where r.b = s.b;
-- select a,d,s.b as s_b from clean_r r, s where r.b = s.b;

-- CREATE IVIEW CLEAN_R_C 
-- WITH MISSING_VALUE('C'), 
--      SAMPLING('10 seconds')
-- AS SELECT * FROM R;
-- select * from clean_r_c;
select a,d, CONF() from clean_r r, s where r.b = s.b;
