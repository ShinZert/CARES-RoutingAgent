# Convert osm.pbf into osm 
cd "/mnt/c/Users/SPHU01/Dropbox (Cambridge CARES)/PC/Desktop/Street OSM/PGRouting/osm2pgrouting"
osmconvert routingpirmasens.osm.pbf --drop-author --drop-version --out-osm -o=routingpirmasens.osm

# Import osm into postgresql
osm2pgrouting --f routingpirmasens.osm --conf mapconfig.xml --dbname routingpirmasens --username postgres --password postgres --clean