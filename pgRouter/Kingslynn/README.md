# CREATE EXTENSION postgis_raster



osm2pgrouting --f kingslynncropped.osm --conf mapconfig.xml --dbname kingslynn --username postgres --password postgres --clean

//Import Raster - DEM
raster2pgsql -c -C -e -s 4326 -f rast -F -I -M -t 100x100 kingslynnbig.tif public.kingslynn_dem | psql -U postgres -h 172.23.128.1 -d kingslynn

//Import Raster  - FLOOD (NEVER MERGE TIF TILES TOGETHER)
raster2pgsql -c -C -e -s OSGB36 -f rast -F -I -M -t 100x100 h_79200_*.tif public.flood_raster | psql -U postgres -h 172.23.128.1 -d kingslynn

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
 e.gid AS gid,
 e.name,
 sum(e.cost) AS cost,
 ST_Collect(e.the_geom) AS geom 
 FROM pgr_dijkstra('SELECT gid as id, source, target, cost, reverse_cost FROM ways',%source%,%target%,false) AS r,ways AS e 
 WHERE r.edge=e.gid GROUP BY e.gid


//CREATE ELEVATION IN THE NODES
ALTER TABLE ways_vertices_pgr ADD COLUMN elevation double precision;
UPDATE ways_vertices_pgr
SET elevation = ST_Value(kingslynn_dem.rast, 1,  ways_vertices_pgr.the_geom)
FROM kingslynn_dem
WHERE ST_Intersects(kingslynn_dem.rast, ways_vertices_pgr.the_geom);

//CREATE THE SLOPE - ADD ELEVATION TO THE SOURCE AND TARGET NODES IN WAYS table 

ALTER TABLE ways ADD COLUMN source_elevation double precision;
ALTER TABLE ways ADD COLUMN target_elevation double precision;

UPDATE ways w
SET source_elevation = v1.elevation,
    target_elevation = v2.elevation
FROM ways_vertices_pgr v1, ways_vertices_pgr v2
WHERE w.source = v1.id
AND w.target = v2.id;

ALTER TABLE ways RENAME COLUMN gid TO id;

//Query the route

//CHANGE "GID" to "ID"
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




//FIND OUT THE SLOPE
ALTER TABLE ways ADD COLUMN slope FLOAT;

UPDATE ways SET slope = ((target_elevation - source_elevation) / source_elevation) * 100;













//INSERT FLOOD Geometry
INSERT INTO flood (geom)
VALUES (ST_SetSRID(ST_GeomFromGeoJSON('{"type":"MultiPolygon","coordinates":[[[[0.38849,52.72679],[0.37556,52.72566],[0.37567,52.73139],[0.39342,52.74511],[0.39459,52.74743],[0.3959,52.74727],[0.39753,52.74638],[0.39857,52.74611],[0.40014,52.7455],[0.39949,52.74452],[0.40102,52.74285],[0.40071,52.74191],[0.39981,52.74111],[0.39959,52.7388],[0.39948,52.73823],[0.39927,52.73733],[0.40005,52.73597],[0.40055,52.73458],[0.39968,52.7337],[0.39956,52.73326],[0.39897,52.7329],[0.39827,52.73172],[0.3925,52.7324],[0.38849,52.72679]]],[[[0.38938,52.77214],[0.38992,52.76984],[0.39269,52.77026],[0.39565,52.76729],[0.39486,52.76635],[0.40055,52.76481],[0.40066,52.76376],[0.39951,52.76238],[0.39881,52.76165],[0.39702,52.75982],[0.39611,52.75878],[0.39663,52.75838],[0.39613,52.75716],[0.39494,52.75695],[0.39493,52.75668],[0.39522,52.75467],[0.39563,52.75343],[0.39561,52.75282],[0.39547,52.75221],[0.39533,52.75196],[0.3963,52.75177],[0.39633,52.75107],[0.3966,52.75056],[0.39684,52.7502],[0.39616,52.75001],[0.39633,52.74925],[0.39678,52.74873],[0.39675,52.74828],[0.39611,52.74783],[0.39575,52.74761],[0.39485,52.74781],[0.39364,52.75091],[0.39215,52.75377],[0.39157,52.75544],[0.39163,52.75676],[0.38645,52.7658],[0.38334,52.77144],[0.38406,52.7716],[0.38938,52.77214]]]]}'), 4326));


//Duplicate the column
ALTER TABLE ways ADD COLUMN cost_flood FLOAT,
                   ADD COLUMN reverse_cost_flood FLOAT;

UPDATE ways
SET cost_flood = cost ,
    reverse_cost_flood = reverse_cost



//CHANGE THE VALUE



UPDATE ways
SET cost_flood = -cost_flood ,
    reverse_cost_flood = -reverse_cost_flood
WHERE EXISTS (
    SELECT 1
    FROM flood_polygon_single
    WHERE ST_Intersects(ways.the_geom, flood_polygon_single.geom)
) AND cost_flood >= 0 AND reverse_cost_flood >= 0;








//pgr_djikstra Query
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





