# CREATE EXTENSION postgis_raster


# Import Raster - DEM
raster2pgsql -c -C -e -s 4326 -f rast -F -I -M -t 100x100 kingslynnbig.tif public.pirmasens_dem | psql -U postgres -h 172.23.128.1 -d kingslynn

# SQL View Command 
## Find nearest vertex


## Find shortest path 
SELECT
 min(r.seq) AS seq,
 e.gid AS gid,
 e.name,
 sum(e.cost) AS cost,
 ST_Collect(e.the_geom) AS geom 
 FROM pgr_dijkstra('SELECT gid as id, source, target, cost, reverse_cost FROM ways',%source%,%target%,false) AS r,ways AS e 
 WHERE r.edge=e.gid GROUP BY e.gid


# CREATE ELEVATION IN THE NODES
### Note to convert both either raster or the nodes in the same SRID using ST_Transform
ALTER TABLE ways_vertices_pgr ADD COLUMN elevation double precision;
UPDATE ways_vertices_pgr
SET elevation = ST_Value(pirmasens_dem.rast, 1,  ST_Transform(ways_vertices_pgr.the_geom,32632))
FROM pirmasens_dem
WHERE ST_Intersects(pirmasens_dem.rast, ST_Transform(ways_vertices_pgr.the_geom,32632));

# CREATE THE SLOPE - ADD ELEVATION TO THE SOURCE AND TARGET NODES IN WAYS table 

ALTER TABLE ways ADD COLUMN source_elevation double precision;
ALTER TABLE ways ADD COLUMN target_elevation double precision;

UPDATE ways w
SET source_elevation = v1.elevation,
    target_elevation = v2.elevation
FROM ways_vertices_pgr v1, ways_vertices_pgr v2
WHERE w.source = v1.id
AND w.target = v2.id;

ALTER TABLE ways RENAME COLUMN gid TO id;

# FIND OUT THE SLOPE
ALTER TABLE ways ADD COLUMN slope FLOAT;

UPDATE ways SET slope = ((target_elevation - source_elevation) / source_elevation) * 100;

# Duplicate the column
ALTER TABLE ways ADD COLUMN cost_pothole FLOAT,
                   ADD COLUMN reverse_cost_pothole FLOAT;

UPDATE ways
SET cost_pothole = cost ,
    reverse_cost_cost_pothole = reverse_cost


# INSERT /s Geometry
CREATE TABLE / (
    id SERIAL PRIMARY KEY,
    geom GEOMETRY(Point, 4326)
);

INSERT INTO pointsOfInterest (geom) VALUES ('POINT(7.607132913855679 49.198595660772156)');
INSERT INTO pointsOfInterest (geom) VALUES ('POINT(7.601254532635608 49.19550479863335)');
INSERT INTO pointsOfInterest (geom) VALUES ('POINT(7.598174108217222 49.19605277606155)');
INSERT INTO pointsOfInterest (geom) VALUES ('POINT(7.61177859313446 49.197197559443424)');
INSERT INTO pointsOfInterest (geom) VALUES ('POINT(7.607634045161916 49.19422629906845)');


# FIND CLOSEST EDGE
ALTER TABLE / ADD COLUMN closest_edge INTEGER;


UPDATE / SET closest_edge = (
  SELECT edge_id FROM pgr_findCloseEdges(
    $$SELECT id, the_geom as geom FROM public.ways$$,
    (SELECT /.geom),
    0.5, partial => false)
  LIMIT 1
);

# CHANGE THE VALUE
UPDATE ways
SET cost_/ = -ABS(cost_/),
    reverse_cost_/ = -ABS(reverse_cost_/)
WHERE id IN (
  SELECT closest_edge FROM /
  WHERE closest_edge IS NOT NULL
)




# Sample Query - Query the route
SELECT  source_elevation, target_elevation, ST_Length(ST_Transform(the_geom,32632)), the_geom

FROM pgr_dijkstra(
    'SELECT id, source, target, ST_Length(the_geom) AS cost FROM ways',
    (
        SELECT source
        FROM ways
        ORDER BY ST_Distance(
            the_geom,
            ST_SetSRID(ST_MakePoint(0.4027042483435537,52.75443837250586), 4326)
        )
        LIMIT 1
    ),
    (
        SELECT source
        FROM ways
        ORDER BY ST_Distance(
            the_geom,
            ST_SetSRID(ST_MakePoint(  0.4020457711052358,52.74661340785207), 4326)
        )
        LIMIT 1
    ),
    directed:=true
) AS route
JOIN ways AS r ON route.edge = r.id;




## Add source and target
-- Alter the "pothole" table to add the "source" and "target" columns
ALTER TABLE pothole
ADD COLUMN source BIGINT,
ADD COLUMN target BIGINT;

-- Update the "source" and "target" columns based on the matching "closest_edge" with "id" in the "ways" table
UPDATE pothole
SET source = ways.source, target = ways.target
FROM ways
WHERE pothole.closest_edge = ways.id;

ALTER TABLE pothole
ADD COLUMN closest_node INT;

ALTER TABLE pothole ADD COLUMN source_distance numeric;
UPDATE pothole AS p
SET source_distance = (
    SELECT ST_DISTANCE(p.geom, w.the_geom)
    FROM ways_vertices_pgr AS w
    WHERE p.source = w.id
);

ALTER TABLE pothole ADD COLUMN target_distance numeric;
UPDATE pothole AS p
SET target_distance = (
    SELECT ST_DISTANCE(p.geom, w.the_geom)
    FROM ways_vertices_pgr AS w
    WHERE p.target = w.id
);

UPDATE pothole
SET closest_node = CASE
    WHEN source_distance < target_distance THEN source
    ELSE target
END;

  
  

# Coming up with the djikstra matrix
CREATE TABLE tsp AS SELECT *
FROM pgr_TSP(
  $$SELECT * FROM pgr_dijkstraCostMatrix(
    'SELECT id, source, target, cost, reverse_cost FROM ways',
    (SELECT array_agg(id)
    FROM ways_vertices_pgr
    WHERE id IN (SELECT closest_node FROM pothole)),
    false)$$, (SELECT MIN(closest_node) FROM pothole), (SELECT MAX(closest_node) FROM pothole));

  
# Solving TSP node seq with geom
SELECT t.Seq, t.node, w.the_geom FROM tsp as t, ways_vertices_pgr as w WHERE t.node=w.id;



# Solving the travelling Salesmanproblem
  SELECT n1.node, n2.node, di.edge, di.cost, ways.the_geom
FROM (
    SELECT ROW_NUMBER() OVER() as seq, node
    FROM tsp
) n1
JOIN (
    SELECT ROW_NUMBER() OVER() as seq, node
    FROM tsp
) n2 ON n1.seq + 1 = n2.seq
JOIN pgr_dijkstra(
    'SELECT id, source, target, cost, reverse_cost FROM ways',
    n1.node,
    n2.node
) AS di ON true
JOIN ways ON di.edge = ways.id
ORDER BY n1.seq;













