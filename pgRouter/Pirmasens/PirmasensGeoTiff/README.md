# In SQL
CREATE EXTENSION postgis_raster

# Run
raster2pgsql -c -C -e -s 32632 -f rast -F -I -M -t 100x100 *.tif public.pirmasens_dem | psql -U postgres -h 172.23.128.1 -d routingpirmasens