# CREATE EXTENSION postgis_raster
osm2pgrouting --f kingslynnbig.osm --conf mapconfig.xml --dbname kingslynn --username postgres --password postgres --clean

# Import Raster - DEM
raster2pgsql -c -C -e -s 4326 -f rast -F -I -M -t 100x100 kingslynnbig.tif public.kingslynn_dem | psql -U postgres -h 172.23.128.1 -d kingslynn

# Import Raster  - FLOOD (NEVER MERGE TIF TILES TOGETHER)
raster2pgsql -c -C -e -s OSGB36 -f rast -F -I -M -t 100x100 h_79200_*.tif public.flood_raster | psql -U postgres -h 172.23.128.1 -d kingslynn


# Import Raster - Population
raster2pgsql -c -C -e -s 4326 -f rast -F -I -M -t 100x100 OpenPopGridClipped.tif public.population_raster | psql -U postgres -d kingslynn

raster2pgsql -c -C -e -s 4326 -f rast -F -I -M -t 100x100 OpenPopGrid_2014.asc public.population | psql -U postgres -d kingslynn


INSERT INTO flood_polygon (geom)
SELECT ST_ConvexHull(ST_Clip(rast, 1, ST_Envelope(rast), true))
FROM flood_raster
WHERE ST_ValueCount(rast, 1,true) > 0;

CREATE TABLE flood_polygon AS
SELECT (ST_DumpAsPolygons(rast,1,true)).*
FROM public.flood_raster ;

CREATE TABLE flood_polygon_single AS
(
    SELECT ST_UNION(geom_selected) AS geom
    FROM
    (
        SELECT geom AS geom_selected
        FROM flood_polygon
        WHERE val >= 0.3
    ) AS subquery
);


UPDATE flood_polygon_single
SET geom = (
SELECT ST_UNION(geom_selected) AS geom
FROM (
SELECT geom AS geom_selected
FROM flood_polygon
WHERE val >= 0.3
) AS subquery
);

UPDATE flood_polygon_single
SET geom = ST_Transform(geom, 4326)
WHERE ST_SRID(geom) = 27700; 



SELECT
 min(r.seq) AS seq,
 e.id AS id,
 sum(e.cost) AS cost,
 ST_Collect(e.the_geom) AS geom 
 FROM pgr_dijkstra('SELECT id as id, source, target, cost_flood as cost, reverse_cost_flood as reverse_cost FROM ways WHERE ways.tag_id IN (100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 115, 116, 121, 123, 124, 125, 401)',%source%,%target%,false) AS r,ways AS e 
 WHERE r.edge=e.id GROUP BY e.id




# CREATE ELEVATION IN THE NODES
ALTER TABLE ways_vertices_pgr ADD COLUMN elevation double precision;
UPDATE ways_vertices_pgr
SET elevation = ST_Value(kingslynn_dem.rast, 1,  ways_vertices_pgr.the_geom)
FROM kingslynn_dem
WHERE ST_Intersects(kingslynn_dem.rast, ways_vertices_pgr.the_geom);

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

# Query the route
# CHANGE "GID" to "ID"
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

# FIND OUT THE SLOPE
ALTER TABLE ways ADD COLUMN slope FLOAT;

UPDATE ways SET slope = ((target_elevation - source_elevation) / source_elevation) * 100;


# INSERT FLOOD Geometry
INSERT INTO flood (geom)
VALUES (ST_SetSRID(ST_GeomFromGeoJSON('{"type":"MultiPolygon","coordinates":[[[[0.38849,52.72679],[0.37556,52.72566],[0.37567,52.73139],[0.39342,52.74511],[0.39459,52.74743],[0.3959,52.74727],[0.39753,52.74638],[0.39857,52.74611],[0.40014,52.7455],[0.39949,52.74452],[0.40102,52.74285],[0.40071,52.74191],[0.39981,52.74111],[0.39959,52.7388],[0.39948,52.73823],[0.39927,52.73733],[0.40005,52.73597],[0.40055,52.73458],[0.39968,52.7337],[0.39956,52.73326],[0.39897,52.7329],[0.39827,52.73172],[0.3925,52.7324],[0.38849,52.72679]]],[[[0.38938,52.77214],[0.38992,52.76984],[0.39269,52.77026],[0.39565,52.76729],[0.39486,52.76635],[0.40055,52.76481],[0.40066,52.76376],[0.39951,52.76238],[0.39881,52.76165],[0.39702,52.75982],[0.39611,52.75878],[0.39663,52.75838],[0.39613,52.75716],[0.39494,52.75695],[0.39493,52.75668],[0.39522,52.75467],[0.39563,52.75343],[0.39561,52.75282],[0.39547,52.75221],[0.39533,52.75196],[0.3963,52.75177],[0.39633,52.75107],[0.3966,52.75056],[0.39684,52.7502],[0.39616,52.75001],[0.39633,52.74925],[0.39678,52.74873],[0.39675,52.74828],[0.39611,52.74783],[0.39575,52.74761],[0.39485,52.74781],[0.39364,52.75091],[0.39215,52.75377],[0.39157,52.75544],[0.39163,52.75676],[0.38645,52.7658],[0.38334,52.77144],[0.38406,52.7716],[0.38938,52.77214]]]]}'), 4326));


# Duplicate the column
ALTER TABLE ways ADD COLUMN cost_flood FLOAT,
                   ADD COLUMN reverse_cost_flood FLOAT;

UPDATE ways
SET cost_flood = cost ,
    reverse_cost_flood = reverse_cost

# CHANGE THE VALUE
UPDATE ways
SET cost_flood = -abs(cost_flood) ,
    reverse_cost_flood = -abs(reverse_cost_flood)
WHERE EXISTS (
    SELECT 1
    FROM flood_polygon_single
    WHERE ST_Intersects(ways.the_geom, flood_polygon_single.geom)
) 

# pgr_djikstra Query
SELECT  source_elevation, target_elevation, ST_Length(ST_Transform(the_geom,32632)), the_geom
FROM 

pgr_dijkstra(
    'SELECT id, source, target, cost_flood AS cost FROM ways',
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





# ISOCHRONE
# Duplicate the column
ALTER TABLE ways ADD COLUMN cost_s_flood FLOAT,
                   ADD COLUMN reverse_cost_s_flood FLOAT;

UPDATE ways
SET cost_s_flood = cost_s ,
    reverse_cost_s_flood = reverse_cost_s

# CHANGE THE VALUE
UPDATE ways
SET cost_s_flood = -abs(cost_s_flood) ,
    reverse_cost_s_flood = -abs(reverse_cost_s_flood)
WHERE EXISTS (
    SELECT 1
    FROM flood_polygon_single
    WHERE ST_Intersects(ways.the_geom, flood_polygon_single.geom)
) 

# Isochrone 
SELECT
    id,
    the_geom
FROM
    pgr_drivingDistance(
        'SELECT id, source, target, cost_s_flood as cost FROM ways', -- Replace with your table name and cost column
        3720, -- Specify the source vertex ID here
        300, -- Specify the time/distance limit (in seconds or units) here
        false -- Set to 'true' for directed graph, 'false' for undirected
    ) AS dd
JOIN
    ways_vertices_pgr AS v ON dd.node = v.id

# CREATE POLYGON ISOCHRONE IN QGis 
-- Create a table to store the isochrone polygons
CREATE TABLE isochrone_results (
    minute integer,
    isochrone_polygon geometry(Polygon)
);

# CREATE SFCGAL Extension
CREATE EXTENSION postgis_sfcgal;

# NOTE NODE SELECTED MUST BE ROUTABLE 
# Different shape
Convex Hull, Concave Hull, Optimal Alpha, Alpha Polygon

## NOTE THIS IS CONVEX HULL POLYGON 
-- Loop through each minute up to 10 minutes
DO $$
DECLARE
    minute_limit integer;
BEGIN
    FOR minute_limit IN 1..5 LOOP
        -- Execute the isochrone query for the current minute limit and store the results in the isochrone_results table
        EXECUTE '
            INSERT INTO isochrone_results (minute, isochrone_polygon)
            SELECT
                ' || minute_limit || ' AS minute,
                ST_ConvexHull(ST_Collect(the_geom)) AS isochrone_polygon
            FROM
                (
                    SELECT
                        id,
                        the_geom
                    FROM
                        pgr_drivingDistance(
                            ''SELECT id, source, target, cost_s_flood as cost FROM ways WHERE ways.tag_id IN (100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 115, 116, 121, 123, 124, 125, 401)'', -- Replace with your table name and cost column
                            7536, -- Specify the source vertex ID here
                            ' || (minute_limit * 120) || ', -- Specify the time/distance limit (in seconds) here
                            false
                        ) AS dd
                    JOIN
                        ways_vertices_pgr AS v ON dd.node = v.id
                ) AS subquery';
    END LOOP;
END $$;

-- Select all the isochrone polygons from the isochrone_results table
SELECT * FROM isochrone_results;

## CONCAVE
DO $$
DECLARE
    minute_limit integer;
BEGIN
    FOR minute_limit IN 1..5 LOOP
        -- Execute the isochrone query for the current minute limit and store the results in the isochrone_results table
        EXECUTE '
            INSERT INTO isochrone_results (minute, isochrone_polygon)
            SELECT
                ' || minute_limit || ' AS minute,
                ST_ConcaveHull(ST_Collect(the_geom),0.5) AS isochrone_polygon
            FROM
                (
                    SELECT
                        id,
                        the_geom
                    FROM
                        pgr_drivingDistance(
                            ''SELECT id, source, target, cost_s_flood as cost FROM ways WHERE ways.tag_id IN (100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 115, 116, 121, 123, 124, 125, 401)'', -- Replace with your table name and cost column
                            7536, -- Specify the source vertex ID here
                            ' || (minute_limit * 120) || ', -- Specify the time/distance limit (in seconds) here
                            false
                        ) AS dd
                    JOIN
                        ways_vertices_pgr AS v ON dd.node = v.id
                ) AS subquery';
    END LOOP;
END $$;

-- Select all the isochrone polygons from the isochrone_results table
SELECT * FROM isochrone_results;



## NOTE THIS IS OptimalAlphaShape POLYGON

-- Create a table to store the isochrone polygons
CREATE TABLE isochrone_results (
    minute integer,
    isochrone_polygon geometry(Polygon)
);

TRUNCATE TABLE isochrone_results;
DO $$
DECLARE
    minute_limit integer;
BEGIN
    FOR minute_limit IN 1..5 LOOP
        -- Execute the isochrone query for the current minute limit and store the results in the isochrone_results table
        EXECUTE '
            INSERT INTO isochrone_results (minute, isochrone_polygon)
            SELECT
                ' || minute_limit * 2 || ' AS minute,
                ST_OptimalAlphaShape(ST_Collect(the_geom)) AS isochrone_polygon
            FROM
                (
                    SELECT
                        id,
                        the_geom
                    FROM
                        pgr_drivingDistance(
                            ''SELECT id, source, target, cost_s_flood as cost FROM ways WHERE ways.tag_id IN (100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 115, 116, 121, 123, 124, 125, 401)'', -- Replace with your table name and cost column
                            7536, -- Specify the source vertex ID here
                            ' || (minute_limit * 120) || ', -- Specify the time/distance limit (in seconds) here
                            false
                        ) AS dd
                    JOIN
                        ways_vertices_pgr AS v ON dd.node = v.id
                ) AS subquery';
    END LOOP;
END $$;


## Isochrone with roadnetwork instead
DO $$
DECLARE
    minute_limit integer;
BEGIN
    FOR minute_limit IN 1..5 LOOP
        -- Execute the isochrone query for the current minute limit and store the results in the isochrone_results table
        EXECUTE '
            INSERT INTO isochrone_results_nw (minute, isochrone_polygon)
            SELECT
                ' || minute_limit || ' AS minute,
                ST_ConcaveHull(ST_Collect(way), 0.85) AS isochrone_polygon
            FROM
                ways
            WHERE
                ways.tag_id IN (100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 115, 116, 121, 123, 124, 125, 401) -- Replace with your desired road tags
                AND ways.cost_s_flood <= ' || (minute_limit * 120) || '; -- Specify the time/distance limit (in seconds) here';
    END LOOP;
END $$;


## TRIP CENTRALITY
CREATE TABLE trips_centrality AS (
SELECT
  b.id,
  b.the_geom AS geom,
  count(b.the_geom) AS count
FROM
	ways AS t,
  pgr_dijkstra(
      'SELECT
          g.id AS id,
          g.source,
          g.target,
          g.cost_s AS cost
          FROM ways AS g WHERE
                g.tag_id IN (100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 115, 116, 121, 123, 124, 125, 401)', 
          7536, t.target,
          directed := FALSE) AS j
JOIN ways AS b 
  ON j.edge = b.id 
GROUP BY b.id, b.the_geom
);

## RETURN CRITICAL ROAD TO FIX - BASED ON RESULTS WITH THE SHORTEST NUMBER OF PATH TO INCREASE ACCESIBILITY PATH
SELECT tc.*
FROM trips_centrality tc
INNER JOIN ways w ON tc.id = w.id
WHERE w.cost_s_flood < 0 AND w.cost_s_flood < 0
ORDER BY tc.count DESC;

# DUPLICATE NEW COLUMN 
ALTER TABLE ways
ADD COLUMN cost_s_flood_fixed numeric,
ADD COLUMN reverse_cost_s_flood_fixed numeric;

UPDATE ways
SET cost_s_flood_fixed = cost_s_flood,
    reverse_cost_s_flood_fixed = reverse_cost_s_flood;

# CHANGE THE VALUE OF THE FIRST 3 TOP CRITICAL BRIDGE
UPDATE ways
SET cost_s_flood_fixed = abs(cost_s_flood_fixed),
    reverse_cost_s_flood_fixed = abs(reverse_cost_s_flood_fixed)
WHERE id IN (
    SELECT tc.id
    FROM trips_centrality tc
    INNER JOIN ways w ON tc.id = w.id
    WHERE w.cost_s_flood < 0 AND w.cost_s_flood < 0
    ORDER BY tc.count DESC
    LIMIT 4
);


## NOTE THIS IS OptimalAlphaShape POLYGON WITH FIXED ROAD
DO $$
DECLARE
    minute_limit integer;
BEGIN
    FOR minute_limit IN 1..5 LOOP
        -- Execute the isochrone query for the current minute limit and store the results in the isochrone_results table
        EXECUTE '
            INSERT INTO isochrone_results (minute, isochrone_polygon)
            SELECT
                ' || minute_limit || ' AS minute,
                ST_OptimalAlphaShape(ST_Collect(the_geom)) AS isochrone_polygon
            FROM
                (
                    SELECT
                        id,
                        the_geom
                    FROM
                        pgr_drivingDistance(
                            ''SELECT id, source, target, cost_s_flood_fixed as cost FROM ways WHERE ways.tag_id IN (100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 115, 116, 121, 123, 124, 125, 401)'', -- Replace with your table name and cost column
                            7536, -- Specify the source vertex ID here
                            ' || (minute_limit * 120) || ', -- Specify the time/distance limit (in seconds) here
                            false
                        ) AS dd
                    JOIN
                        ways_vertices_pgr AS v ON dd.node = v.id
                ) AS subquery';
    END LOOP;
END $$;



























## Sample Query
CREATE TABLE building_properties (
  id SERIAL PRIMARY KEY,
  building_IRI VARCHAR(255),
  propertyType VARCHAR(255),
  value INT,
  area DECIMAL(10, 2),
  geom GEOMETRY(Point, 4326)
);

INSERT INTO building_properties (building_IRI, propertyType, value, area, geom)
VALUES (
  'https://www.theworldavatar.com/kg/ontobuiltenv/Building_3700ce3b-ce64-48c4-9299-8b1b7434fa3b',
  'https://www.theworldavatar.com/kg/ontobuiltenv/MultiResidential_3bf425d3-23d0-4c2b-bba1-e7731439a923',
  4143000,
  471.53,
  ST_SetSRID(ST_MakePoint( 0.3947932037886142,52.75482278377076), 4326)
);

INSERT INTO building_properties (building_IRI, propertyType, value, area, geom)
VALUES (
  'https://www.theworldavatar.com/kg/ontobuiltenv/Building_8b32980e-c96f-42f1-a8a0-e1d27f96235e',
  'https://www.theworldavatar.com/kg/ontobuiltenv/MultiResidential_4f05693f-655e-4cfb-a8b7-c1c7b37e4d21',
  3336000,
  981.0,
  ST_SetSRID(ST_MakePoint( 0.38297175627405344,52.74186217408458), 4326)
);

INSERT INTO building_properties (building_IRI, propertyType, value, area, geom)
VALUES (
  'https://www.theworldavatar.com/kg/ontobuiltenv/Building_c3f28506-4dbf-4bd1-9d96-f55387f35ddb',
  'https://www.theworldavatar.com/kg/ontobuiltenv/MultiResidential_865a7f0d-f8df-44f1-84f1-8d987d2e9113',
  2851000,
  547.0,
  ST_SetSRID(ST_MakePoint( 0.43617714686580783,52.79243541416795), 4326)
);

INSERT INTO building_properties (building_IRI, propertyType, value, area, geom)
VALUES (
  'https://www.theworldavatar.com/kg/ontobuiltenv/Building_a15d1582-c216-4e77-a0fc-0227126b1e95',
  'https://www.theworldavatar.com/kg/ontobuiltenv/SingleResidential_9746e338-ff11-4209-81cc-fcc50f45804a',
  2638000,
  410.0,
  ST_SetSRID(ST_MakePoint( 0.3639685124294437,52.75416651734307), 4326)
);

INSERT INTO building_properties (building_IRI, propertyType, value, area, geom)
VALUES (
  'https://www.theworldavatar.com/kg/ontobuiltenv/Building_37166f06-6333-4693-a596-265630624b32',
  'https://www.theworldavatar.com/kg/ontobuiltenv/MultiResidential_20ca3fe0-c6e4-4c93-b42a-20dc12f4a862',
  2597000,
  880.0,
  ST_SetSRID(ST_MakePoint( 0.4020831131566202,52.765336446212174), 4326)
);






## SAMPLE QUERY TO INSERT
INSERT INTO building_properties (id,building_IRI, propertyType, value, geom)
VALUES (
    '1',
    '<https://www.theworldavatar.com/kg/ontobuiltenv/Building_a15d1582-c216-4e77-a0fc-0227126b1e95>',
    '<https://www.theworldavatar.com/kg/ontobuiltenv/SingleResidential>',
    2638000,
    ST_SetSRID(ST_MakePoint( 0.3639685124294437, 52.75416651734307), 4326)
);

INSERT INTO building_properties (id,building_IRI, propertyType, value, geom)
VALUES (
    '2',
    '<https://www.theworldavatar.com/kg/ontobuiltenv/Building_8d0e581b-7d12-48c4-99a3-3d7c451392d1>',
    '<https://www.theworldavatar.com/kg/ontobuiltenv/SingleResidential>',
    1996000,
    ST_SetSRID(ST_MakePoint(0.4310341724847265, 52.77611280632098), 4326)
);

INSERT INTO building_properties (id,building_IRI, propertyType, value, geom)
VALUES (
    '3',
    '<https://www.theworldavatar.com/kg/ontobuiltenv/Building_e5b7a4c0-d6d3-43be-ab20-93bdb49d6ac0>',
    '<https://www.theworldavatar.com/kg/ontobuiltenv/SingleResidential>',
    1950000,
    ST_SetSRID(ST_MakePoint(0.450150,52.786824), 4326)
);


## TSP with KingsLynn
## Add source and target to building
-- Alter the "building_properties" table to add the "source" and "target" columns
ALTER TABLE building_properties
ADD COLUMN source BIGINT,
ADD COLUMN target BIGINT;

# FIND CLOSEST EDGE
ALTER TABLE building_properties ADD COLUMN closest_edge INTEGER;


UPDATE building_properties SET closest_edge = (
  SELECT edge_id FROM pgr_findCloseEdges(
    $$SELECT id, the_geom as geom FROM public.ways$$,
    (SELECT building_properties.geom),
    0.5, partial => false)
  LIMIT 1
);



-- Update the "source" and "target" columns based on the matching "closest_edge" with "id" in the "ways" table
UPDATE building_properties
SET source = ways.source, target = ways.target
FROM ways
WHERE building_properties.closest_edge = ways.id;

ALTER TABLE building_properties
ADD COLUMN closest_node INT;

ALTER TABLE building_properties ADD COLUMN source_distance numeric;
UPDATE building_properties AS p
SET source_distance = (
    SELECT ST_DISTANCE(p.geom, w.the_geom)
    FROM ways_vertices_pgr AS w
    WHERE p.source = w.id
);

ALTER TABLE building_properties ADD COLUMN target_distance numeric;
UPDATE building_properties AS p
SET target_distance = (
    SELECT ST_DISTANCE(p.geom, w.the_geom)
    FROM ways_vertices_pgr AS w
    WHERE p.target = w.id
);

UPDATE building_properties
SET closest_node = CASE
    WHEN source_distance < target_distance THEN source
    ELSE target
END;


CREATE TABLE tsp AS SELECT *
FROM pgr_TSP(
  $$SELECT * FROM pgr_dijkstraCostMatrix(
    'SELECT id, source, target, cost_s as cost FROM ways WHERE ways.tag_id IN (100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 115, 116, 121, 123, 124, 125, 401)',
    (SELECT array_agg(id)
    FROM ways_vertices_pgr
    WHERE id IN (SELECT closest_node FROM building_properties)),
    false)$$, (SELECT MIN(closest_node) FROM building_properties), (SELECT MAX(closest_node) FROM building_properties));



SELECT ways.the_geom
FROM (
    SELECT ROW_NUMBER() OVER() as seq, tsp.node
    FROM tsp
) n1
JOIN (
    SELECT ROW_NUMBER() OVER() as seq, tsp.node
    FROM tsp
) n2 ON n1.seq + 1 = n2.seq
JOIN pgr_dijkstra(
    'SELECT id, source, target, cost, reverse_cost FROM ways WHERE ways.tag_id IN (100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 115, 116, 121, 123, 124, 125, 401)',
    n1.node,
    n2.node
) AS di ON true
JOIN ways ON di.edge = ways.id
ORDER BY n1.seq
