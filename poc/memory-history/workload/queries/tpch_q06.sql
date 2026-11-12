SELECT 
  sum(l.extendedprice*l.discount) AS revenue
FROM 
  lineitem l
WHERE 
  l.shipdate >= DATE '{{year}}-01-01'
  AND l.shipdate < DATE '{{year}}-01-01' + INTERVAL '1' YEAR
  AND l.discount BETWEEN {{discount}} - 0.01 AND {{discount}} + 0.01
  AND l.quantity < 24
;
