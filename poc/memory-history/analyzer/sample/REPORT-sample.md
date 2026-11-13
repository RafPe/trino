# Query log analysis

Input: `sample-log.csv`. Rule settings: last 10 runs per shape, failure growth factor 2. Large queries: peak ≥ 1.00 GiB.

## Volume

- Records read: 491, rejected by normalization: 0
- Records: 491
- Records with a fingerprint: 485 (98.8%)
- Time range (end time): 2026-11-02T07:09:13.213Z to 2026-11-08T18:51:04.230Z
- Shapes (fingerprints): 24
- Shapes seen once: 10

## Repeat rate

Part of the queries that had an earlier run: of the same shape, and of the exact same text. An earlier run is a run that ended before the query started. Only records with a fingerprint count.

| queries | n | earlier run of the same shape | earlier run of the same text |
|---|---|---|---|
| all | 485 | 461 (95.1%) | 108 (22.3%) |
| peak ≥ 1.00 GiB | 167 | 162 (97.0%) | 49 (29.3%) |
| memory failures | 12 | 9 (75.0%) | 1 (8.3%) |

## Memory spread of one shape

For shapes with 3 or more finished runs: largest peak / smallest peak of the finished runs.

| shapes | n | median | p90 | max | smallest peak is 0 |
|---|---|---|---|---|---|
| all | 14 | 1.16 | 3421.25 | 17643.81 | 0 |
| peak ≥ 1.00 GiB | 6 | 1.08 | 109.29 | 109.29 | 0 |

Worst 14 shapes (all):

| fingerprint | finished runs | min peak | max peak | max/min | normalized SQL |
|---|---|---|---|---|---|
| `ab8d4a96cbb8` | 30 | 243 B | 4.09 MiB | 17643.81 | `select sum ( l . extendedprice * l . discount ) as revenue from lineitem l where l . shipdate >= date ? and l . shipdate < date ? + interval ? year and l . discount between ? - ? and ? + ? and l . quantity < ?` |
| `b19c4b1f01bc` | 32 | 1.90 KiB | 6.33 MiB | 3421.25 | `select l . returnflag , l . linestatus , sum ( l . quantity ) as sum_qty , sum ( l . extendedprice ) as sum_base_price , sum ( l . extendedprice * ( ? - l . discount ) ) as sum_disc_price , sum ( l . extendedprice * ( ? - l . discount ) * ( ? + l . tax ) ) as sum_charge , avg ( l . quantity ) as avg` |
| `448e0001be58` | 28 | 28.54 MiB | 3.05 GiB | 109.29 | `select l . suppkey , count ( * ) as lines , sum ( l . extendedprice ) as revenue from lineitem l where l . shipdate >= date ? and l . shipdate < date ? + interval ? day group by l . suppkey order by revenue desc` |
| `14d0337245cc` | 32 | 3.35 MiB | 7.67 MiB | 2.29 | `select "dt" . "d_year" , "item" . "i_brand_id" "brand_id" , "item" . "i_brand" "brand" , "sum" ( "ss_ext_sales_price" ) "sum_agg" from date_dim dt , store_sales , item where ( "dt" . "d_date_sk" = "store_sales" . "ss_sold_date_sk" ) and ( "store_sales" . "ss_item_sk" = "item" . "i_item_sk" ) and ( "` |
| `c1863ae0f56c` | 31 | 20.95 MiB | 29.89 MiB | 1.43 | `select "dt" . "d_year" , "item" . "i_category_id" , "item" . "i_category" , "sum" ( "ss_ext_sales_price" ) from date_dim dt , store_sales , item where ( "dt" . "d_date_sk" = "store_sales" . "ss_sold_date_sk" ) and ( "store_sales" . "ss_item_sk" = "item" . "i_item_sk" ) and ( "item" . "i_manager_id" ` |
| `6c27069c1e2f` | 32 | 4.08 MiB | 5.17 MiB | 1.27 | `select "i_brand_id" "brand_id" , "i_brand" "brand" , "sum" ( "ss_ext_sales_price" ) "ext_price" from date_dim , store_sales , item where ( "d_date_sk" = "ss_sold_date_sk" ) and ( "ss_item_sk" = "i_item_sk" ) and ( "i_manager_id" = ? ) and ( "d_moy" = ? ) and ( "d_year" = ? ) group by "i_brand" , "i_` |
| `b8cc80f6c900` | 32 | 473.71 MiB | 580.37 MiB | 1.23 | `select c_count , count ( * ) as custdist from ( select c . custkey , count ( o . orderkey ) from customer c left outer join orders o on c . custkey = o . custkey and o . comment not like ? group by c . custkey ) as c_orders ( c_custkey , c_count ) group by c_count order by custdist desc , c_count de` |
| `4462c4f72db4` | 33 | 1.02 GiB | 1.11 GiB | 1.09 | `select "s_store_name" , "s_store_id" , "sum" ( ( case when ( "d_day_name" = ? ) then "ss_sales_price" else null end ) ) "sun_sales" , "sum" ( ( case when ( "d_day_name" = ? ) then "ss_sales_price" else null end ) ) "mon_sales" , "sum" ( ( case when ( "d_day_name" = ? ) then "ss_sales_price" else nul` |
| `4fea39f755ae` | 32 | 49.39 MiB | 53.53 MiB | 1.08 | `select "i_item_id" , "avg" ( "ss_quantity" ) "agg1" , "avg" ( "ss_list_price" ) "agg2" , "avg" ( "ss_coupon_amt" ) "agg3" , "avg" ( "ss_sales_price" ) "agg4" from store_sales , customer_demographics , date_dim , item , promotion where ( "ss_sold_date_sk" = "d_date_sk" ) and ( "ss_item_sk" = "i_item_` |
| `f952e4a7da3e` | 28 | 2.15 GiB | 2.33 GiB | 1.08 | `select nation , o_year , sum ( amount ) as sum_profit from ( select n . name as nation , extract ( year from o . orderdate ) as o_year , l . extendedprice * ( ? - l . discount ) - ps . supplycost * l . quantity as amount from part as p , supplier as s , lineitem as l , partsupp as ps , orders as o ,` |
| `de79d9be986a` | 33 | 1.24 GiB | 1.33 GiB | 1.07 | `select "i_brand_id" "brand_id" , "i_brand" "brand" , "i_manufact_id" , "i_manufact" , "sum" ( "ss_ext_sales_price" ) "ext_price" from date_dim , store_sales , item , customer , customer_address , store where ( "d_date_sk" = "ss_sold_date_sk" ) and ( "ss_item_sk" = "i_item_sk" ) and ( "i_manager_id" ` |
| `b1f2ec89c0b8` | 26 | 1.52 GiB | 1.60 GiB | 1.05 | `select c . name , c . custkey , o . orderkey , o . orderdate , o . totalprice , sum ( l . quantity ) from customer as c , orders as o , lineitem as l where o . orderkey in ( select l . orderkey from lineitem as l group by l . orderkey having sum ( l . quantity ) > ? ) and c . custkey = o . custkey a` |
| `38e26399cfb1` | 29 | 3.29 GiB | 3.43 GiB | 1.04 | `select s . name , count ( * ) as numwait from supplier s , lineitem l1 , orders o , nation n where s . suppkey = l1 . suppkey and o . orderkey = l1 . orderkey and o . orderstatus = ? and l1 . receiptdate > l1 . commitdate and exists ( select * from lineitem l2 where l2 . orderkey = l1 . orderkey and` |
| `5dd766167580` | 51 | 3.30 MiB | 3.35 MiB | 1.01 | `select "count" ( * ) from store_sales , household_demographics , time_dim , store where ( "ss_sold_time_sk" = "time_dim" . "t_time_sk" ) and ( "ss_hdemo_sk" = "household_demographics" . "hd_demo_sk" ) and ( "ss_store_sk" = "s_store_sk" ) and ( "time_dim" . "t_hour" = ? ) and ( "time_dim" . "t_minute` |

Worst 6 shapes (peak ≥ 1.00 GiB):

| fingerprint | finished runs | min peak | max peak | max/min | normalized SQL |
|---|---|---|---|---|---|
| `448e0001be58` | 28 | 28.54 MiB | 3.05 GiB | 109.29 | `select l . suppkey , count ( * ) as lines , sum ( l . extendedprice ) as revenue from lineitem l where l . shipdate >= date ? and l . shipdate < date ? + interval ? day group by l . suppkey order by revenue desc` |
| `4462c4f72db4` | 33 | 1.02 GiB | 1.11 GiB | 1.09 | `select "s_store_name" , "s_store_id" , "sum" ( ( case when ( "d_day_name" = ? ) then "ss_sales_price" else null end ) ) "sun_sales" , "sum" ( ( case when ( "d_day_name" = ? ) then "ss_sales_price" else null end ) ) "mon_sales" , "sum" ( ( case when ( "d_day_name" = ? ) then "ss_sales_price" else nul` |
| `f952e4a7da3e` | 28 | 2.15 GiB | 2.33 GiB | 1.08 | `select nation , o_year , sum ( amount ) as sum_profit from ( select n . name as nation , extract ( year from o . orderdate ) as o_year , l . extendedprice * ( ? - l . discount ) - ps . supplycost * l . quantity as amount from part as p , supplier as s , lineitem as l , partsupp as ps , orders as o ,` |
| `de79d9be986a` | 33 | 1.24 GiB | 1.33 GiB | 1.07 | `select "i_brand_id" "brand_id" , "i_brand" "brand" , "i_manufact_id" , "i_manufact" , "sum" ( "ss_ext_sales_price" ) "ext_price" from date_dim , store_sales , item , customer , customer_address , store where ( "d_date_sk" = "ss_sold_date_sk" ) and ( "ss_item_sk" = "i_item_sk" ) and ( "i_manager_id" ` |
| `b1f2ec89c0b8` | 26 | 1.52 GiB | 1.60 GiB | 1.05 | `select c . name , c . custkey , o . orderkey , o . orderdate , o . totalprice , sum ( l . quantity ) from customer as c , orders as o , lineitem as l where o . orderkey in ( select l . orderkey from lineitem as l group by l . orderkey having sum ( l . quantity ) > ? ) and c . custkey = o . custkey a` |
| `38e26399cfb1` | 29 | 3.29 GiB | 3.43 GiB | 1.04 | `select s . name , count ( * ) as numwait from supplier s , lineitem l1 , orders o , nation n where s . suppkey = l1 . suppkey and o . orderkey = l1 . orderkey and o . orderstatus = ? and l1 . receiptdate > l1 . commitdate and exists ( select * from lineitem l2 where l2 . orderkey = l1 . orderkey and` |

## Replay of the predictor rule

The log is replayed in time order. For each query, the rule predicts the peak from the last 10 runs of the same shape that ended before the query started: the largest peak of the finished runs, or, after a memory failure that is newer than the last finished run, the largest known peak × 2. A hit is a query with a prediction. The ratio is predicted / actual peak, for finished queries with a hit and a peak above 0.

| queries | n | hits | ratio median | p10 | p90 | too low (< 0.8) | too high (> 2) |
|---|---|---|---|---|---|---|---|
| all | 485 | 461 (95.1%) | 1.05 | 1.00 | 95.90 | 12 (2.7%) | 70 (16.0%) |
| peak ≥ 1.00 GiB | 167 | 162 (97.0%) | 1.02 | 0.99 | 1.07 | 2 (1.3%) | 4 (2.6%) |

Basis of the hits: 448 from finished runs, 13 from a memory failure × 2. Percentages of too low and too high are of the 438 rated queries (all); finished hits with a peak of 0 are not rated (0).

## Memory failures

- Memory failures with a fingerprint: 12. Without a fingerprint: 0.
- With an earlier run of the same shape: 9 (75.0%)
- With a prediction: 9 (from finished runs: 3, from an earlier memory failure: 6)
- Prediction ≥ the peak at which the query failed: 7 of 9. Predicted / failed peak: median 1.92, min 0.96, max 2.08.
- The failed peak is a lower bound of the real need. A prediction below it was too low for sure; a prediction above it was not necessarily enough.

## Input size check

The same replay, but an earlier run counts only if its input bytes are within 10% of the input bytes of the query. Only queries with input bytes count. The real input size is not known before a query runs, so this shows the best case.

| queries | check | n | hits | ratio median | p10 | p90 | too low (< 0.8) | too high (> 2) |
|---|---|---|---|---|---|---|---|---|
| all | without | 485 | 461 (95.1%) | 1.05 | 1.00 | 95.90 | 12 (2.7%) | 70 (16.0%) |
| all | with | 485 | 434 (89.5%) | 1.04 | 1.00 | 5.63 | 10 (2.3%) | 51 (11.8%) |
| peak ≥ 1.00 GiB | without | 167 | 162 (97.0%) | 1.02 | 0.99 | 1.07 | 2 (1.3%) | 4 (2.6%) |
| peak ≥ 1.00 GiB | with | 167 | 147 (88.0%) | 1.02 | 1.00 | 1.07 | 0 (0.0%) | 0 (0.0%) |

## What was not possible

Records without a fingerprint: 6 (1.2%). The 10 most frequent reasons:

| reason | records |
|---|---|
| statement not supported, or invalid SQL (starts with DESCRIBE) | 1 |
| statement not supported, or invalid SQL (starts with SHOW) | 1 |
| invalid SQL (parser rejected a statement that starts with SELECT) | 1 |
| statement not supported, or invalid SQL (starts with SET) | 1 |
| statement not supported, or invalid SQL (starts with SELEC) | 1 |
| CREATE that is not CREATE TABLE AS, or invalid SQL | 1 |
