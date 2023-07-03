# Kings Lynn Flood Router

This document marks down the steps taken to create flood router, isochrone under flooding and transport network criticality analysis. 

## Importing data 
This section describes the steps required to prepare the data for further actions. 
### Prepare PostGIS Environment 
```
CREATE EXTENSION postgis_raster
CREATE EXTENSION pg_routing
```

### Import OSM Data
This step imports OSM data into Postgis database in the form of `ways_vertices_pgr` and `ways`, i.e. Nodes and Edges.  


[mapconfig.xml](https://github.com/pgRouting/osm2pgrouting/blob/main/mapconfig.xml) refers to how osm2pgrouting will subsequently take the road. Select the right mapconfig.xml files for different purposes.  

```
osm2pgrouting --f kingslynnbig.osm --conf mapconfig.xml --dbname kingslynn --username postgres --password postgres --clean
```

### Import Terrain Raster Data - Digital Elevation Model (DEM)
This step import terrain raster data into Postgis database as a raster table. 
```
raster2pgsql -c -C -e -s 4326 -f rast -F -I -M -t 100x100 kingslynnbig.tif public.kingslynn_dem | psql -U postgres -h 172.23.128.1 -d kingslynn
```

### Import Flood Raster Data 
This step import flood raster data into Postgis database as a raster table. 
```
raster2pgsql -c -C -e -s OSGB36 -f rast -F -I -M -t 100x100 h_79200_*.tif public.flood_raster | psql -U postgres -h 172.23.128.1 -d kingslynn
```

Note: 
1) Consume the tiles individually, do not merge TIF tiles and re-export tiles via QGIS. This introduces NON-DATA error. 
2) Specify the correct SRID as according the respective raster data. 

### Import Raster - Population
Population Raster data is retrieved from [OpenPopGrid](http://openpopgrid.geodata.soton.ac.uk/)

```
raster2pgsql -c -C -e -s 4326 -f rast -F -I -M -t 100x100 OpenPopGridClipped.tif public.population_raster | psql -U postgres -d kingslynn
```

Note: Currently, QGIS is used to consume the .asc file, crops desired location and exported in tif file. 

## Massaging the tables 
### Creating Flood Polygon

This step convert the raster data into polygon. 
```
CREATE TABLE flood_polygon AS
SELECT (ST_DumpAsPolygons(rast,1,true)).*
FROM public.flood_raster ;
```

This step convert the raster data into single polygon, and keep only flood depth more than 0.3m. Converted into single polygon to ease geoserver in displaying the flood data on DTVF.  
```
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
```

This step is to update flood_polygon_single water depth value when required.  
```
UPDATE flood_polygon_single
SET geom = (
SELECT ST_UNION(geom_selected) AS geom
FROM (
SELECT geom AS geom_selected
FROM flood_polygon
WHERE val >= 0.3
) AS subquery
);
```

This step is to convert flood polygon SRID to EPSG:4326 to ensure consistency in SRID. 
```
UPDATE flood_polygon_single
SET geom = ST_Transform(geom, 4326)
WHERE ST_SRID(geom) = 27700; 
```
### Creating elevation (Optional)
This step intersects the nodes with raster DEM data and assigns elevation value to each node. 
``` 
ALTER TABLE ways_vertices_pgr ADD COLUMN elevation double precision;
UPDATE ways_vertices_pgr
SET elevation = ST_Value(kingslynn_dem.rast, 1,  ways_vertices_pgr.the_geom)
FROM kingslynn_dem
WHERE ST_Intersects(kingslynn_dem.rast, ways_vertices_pgr.the_geom);
```

Calculate the slope of `way`. 
```
ALTER TABLE ways ADD COLUMN source_elevation double precision;
ALTER TABLE ways ADD COLUMN target_elevation double precision;

UPDATE ways w
SET source_elevation = v1.elevation,
    target_elevation = v2.elevation
FROM ways_vertices_pgr v1, ways_vertices_pgr v2
WHERE w.source = v1.id
AND w.target = v2.id;

ALTER TABLE ways RENAME COLUMN gid TO id;

ALTER TABLE ways ADD COLUMN slope FLOAT;

UPDATE ways SET slope = ((target_elevation - source_elevation) / source_elevation) * 100;
```

### Creating Single Route Query
This is a sample SQL query that given two coordinates, carry out a pgr_djikstra routing. 
```
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
```

### SQL View
Configure new SQL view in Geoserver as a new layer. 

This is the SQL view query for nearest vertex in Geoserver.
```
SELECT
  v.id,
  v.the_geom
FROM
  ways_vertices_pgr AS v,
  ways AS e
WHERE
  v.id = (SELECT
            id
          FROM ways_vertices_pgr
          ORDER BY the_geom <-> ST_SetSRID(ST_MakePoint(%lon%, %lat%), 4326) LIMIT 1)
  AND (e.source = v.id OR e.target = v.id)
GROUP BY v.id, v.the_geom
```

Validataion regular expression `^[\d\.\+-eE]+$`. 

This is the SQL view query for shortest path in Geoserver.
```
SELECT
min(r.seq) AS seq,
e.id AS id,
sum(e.cost) AS cost,
ST_Collect(e.the_geom) AS geom 
FROM pgr_dijkstra('SELECT id as id, source, target, cost_s_flood as cost, reverse_cost_s_flood   as reverse_cost FROM ways WHERE ways.tag_id IN (100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 115, 116, 121, 123, 124, 125, 401)',%source%,%target%,false) AS r,ways AS e WHERE r.edge=e.id GROUP BY e.id
```

Validataion regular expression `^[\d]+$`. 

Modify the geojson endpoint in index.html

### Duplicate the column
```
ALTER TABLE ways ADD COLUMN cost_flood FLOAT,
                   ADD COLUMN reverse_cost_flood FLOAT;

UPDATE ways
SET cost_flood = cost ,
    reverse_cost_flood = reverse_cost
```

Change the value
```
UPDATE ways
SET cost_flood = -abs(cost_flood) ,
    reverse_cost_flood = -abs(reverse_cost_flood)
WHERE EXISTS (
    SELECT 1
    FROM flood_polygon_single
    WHERE ST_Intersects(ways.the_geom, flood_polygon_single.geom)
) 
```


## Creating Isochrone flooded and unflooded

### Adding  SFCGAL Extension
SFCGAL extension is required to calculate and create polygons. 
```
CREATE EXTENSION postgis_sfcgal;
```

Other Polygon shapes includes Convex Hull, Concave Hull, Optimal Alpha, Alpha Polygon.

## Creating Optimal AlphaShape Isochrone Polygon 
Note: The selected node for the source of Isochrone must be routable. 

Isochrone is in 2 minute increment. 
```
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
```


## Criticaly Road Network Analysis
### Calculating Trip Centrality
```
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
```

### Return Critical Road 
Based on results, return the path with highest trip centrality.  

```
SELECT tc.*
FROM trips_centrality tc
INNER JOIN ways w ON tc.id = w.id
WHERE w.cost_s_flood < 0 AND w.cost_s_flood < 0
ORDER BY tc.count DESC;
```

Duplicate new cost function column.
```
ALTER TABLE ways
ADD COLUMN cost_s_flood_fixed numeric,
ADD COLUMN reverse_cost_s_flood_fixed numeric;

UPDATE ways
SET cost_s_flood_fixed = cost_s_flood,
    reverse_cost_s_flood_fixed = reverse_cost_s_flood;
```

Modify the cost function of top 3 critical bridge. 

```
UPDATE ways
SET cost_s_flood_fixed = abs(cost_s_flood_fixed),
    reverse_cost_s_flood_fixed = abs(reverse_cost_s_flood_fixed)
WHERE id IN (
    SELECT tc.id
    FROM trips_centrality tc
    INNER JOIN ways w ON tc.id = w.id
    WHERE w.cost_s_flood < 0 AND w.cost_s_flood < 0
    ORDER BY tc.count DESC
    LIMIT 3
);
```


Recreate Optimal_AlphaShape Isochrone Polygon with cost_s_fixed as cost function to calculate the isochrone after fixing the bridge.
```
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
```


## Unreachable Population
This step calculates the difference between isochrone flooded and unflooded and subsequently match with the population raster data to return the unreachable population in less than 10 minutes.
```
SELECT (
    SELECT ST_Difference(u.isochrone_polygon, i.isochrone_polygon)
    FROM isochrone_results AS i, isochrone_results_unflooded AS u
    WHERE i.minute = 10 AND u.minute = 10
),ROUND(SUM((ST_SummaryStats(ST_Clip(population_raster.rast, ST_Transform((
    SELECT ST_Difference(u.isochrone_polygon, i.isochrone_polygon)
    FROM isochrone_results AS i, isochrone_results_unflooded AS u
    WHERE i.minute = 10 AND u.minute = 10
), ST_SRID(population_raster.rast)), TRUE))).sum)) AS "Unreachable Population In Less than 10 Minutes"
FROM population_raster, unreachablelayer
```



## Travelling Salesman Problem with KingsLynn
### SPARQL Query for richest houses in KingsLynn
```
PREFIX obe:       <https://www.theworldavatar.com/kg/ontobuiltenv/>
PREFIX om:		  <http://www.ontology-of-units-of-measure.org/resource/om-2/>	
PREFIX rdf:       <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs:      <http://www.w3.org/2000/01/rdf-schema#>
PREFIX dabgeo:	  <http://www.purl.org/oema/infrastructure/>

select ?property ?price ?area
where { ?property  rdf:type dabgeo:Building ;
                   obe:hasPropertyUsage ?use ;
                   obe:hasMarketValue ?mv ; 
                   obe:hasTotalFloorArea ?floor . 
       # Consider only residential buildings
       ?use rdf:type ?usage .
       VALUES ?usage {obe:Domestic obe:SingleResidential obe:MultiResidential}
       # Consider only buildings with floor area in certain range (i.e., to exclude potential "outliers")
       ?floor om:hasValue/om:hasNumericalValue ?area . 
       FILTER (?area > 100 && ?area < 1000)
       ?mv om:hasValue/om:hasNumericalValue ?price . 
      }
ORDER BY DESC(?price)
LIMIT 10
```


### Inserting Richest House into Postgis
```
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

```

### Adding hospital
```

INSERT INTO building_properties (id,building_IRI, propertyType, value, geom, closest_node)
VALUES (
    '6',
    '<https://www.theworldavatar.com/kg/ontobuiltenv/Building_89b375af-1be1-4bdd-888d-3f17e9afde7c>',
    '<https://www.theworldavatar.com/kg/ontobuiltenv/Hospital_685675f8-9d61-4020-96cf-9a2c8e3f2332>',
    208233000,
    ST_SetSRID(ST_MakePoint(0.4449964774757034,52.756232805460925), 4326), 7536
);
```



### Add source and target to building
```
-- Alter the "building_properties" table to add the "source" and "target" columns
ALTER TABLE building_properties
ADD COLUMN source BIGINT,
ADD COLUMN target BIGINT;
```

### Find closest edge
Find the closest edge and subsequently the nearest node on this edge. Subsequently assign the node for pgr_tsp. 
```
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
```

Create TSP table to specify the order of sequence to visit each node. 
```
CREATE TABLE tsp AS SELECT *
FROM pgr_TSP(
  $$SELECT * FROM pgr_dijkstraCostMatrix(
    'SELECT id, source, target, cost_s as cost FROM ways WHERE ways.tag_id IN (100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 115, 116, 121, 123, 124, 125, 401)',
    (SELECT array_agg(id)
    FROM ways_vertices_pgr
    WHERE id IN (SELECT closest_node FROM building_properties)),
    false)$$, (SELECT MIN(closest_node) FROM building_properties), (SELECT MAX(closest_node) FROM building_properties));
```


### SQL View
#### TSP Nodes KingsLynn 
```
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
    'SELECT id, source, target, cost, reverse_cost FROM ways WHERE ways.tag_id IN (100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 115, 116, 120, 121, 123, 124, 125, 401)',
    n1.node,
    n2.node
) AS di ON true
JOIN ways ON di.edge = ways.id
ORDER BY n1.seq
```
#### TSP Nodes KingsLynn 
This step returns the nodes in travelling salesman problem. 
```
SELECT t.Seq, t.node, w.the_geom, bp.building_iri, bp.value, bp.area FROM tsp as t, ways_vertices_pgr as w, building_properties as bp WHERE t.node=w.id AND bp.closest_node=t.node
```