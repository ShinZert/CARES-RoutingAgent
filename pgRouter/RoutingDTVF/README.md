# Flood Routing Digital Twin Visualisation Framework (DTVF)

This visualization serves as a proof of concept for routing under flood, isochrone from points of interest, unreachable area.

The instantiated data is visualised using the Digital Twin Visualisation Framework ([DTVF]) version `3.3.4`. The configuration file structure (i.e. `data.json`) is based on the [example Mapbox visualisation].

<img src="floodrouter.JPG" alt="Mapbox visualisation" width="100%"/>


## Important Pre-requisites
### Raw OSM data
1) [BBBike.org](https://extract.bbbike.org/) allows you to extract selected region. 
2) [Geofabrik](https://download.geofabrik.de/) allows you to download OSM data via region/country 

Note: 
Downloading cropped map from BBBike.org is currently the best option. If OSM map data is downloaded from Geofabrik and subsequently cropped by using tool such as osmium or osmium will result in leaving out certain nodes, subsequently when imported via osm2pgrouting will lead to invisible/non-existent road.


### Preparing data in Postgis 
1) Run basic extension [osm2pgrouting] library (https://github.com/pgRouting/osm2pgrouting)

Note: 

or 

2) Run [pgr_createTopology](https://docs.pgrouting.org/3.1/en/pgr_createTopology.html)


### Preparing PostGIS environment
1) [postgis_raster](https://postgis.net/docs/RT_FAQ.html): `CREATE Extension Postgis_raster;` This is for the raster data. 
2) [pgrouting](https://pgrouting.org/index.html): `CREATE EXTENSION pgrouting;` This is to run pg_Routing commands.


### Importing Raster data
[Raster2pgsql](https://postgis.net/docs/using_raster_dataman.html)

Note: Specify the right SRID


## Creating the Visualisation
### Prerequisite
A valid Mapbox API token must be provided in your `index.html` file.

```
# To build the Image:
docker-compose -f ./docker/docker-compose.yml build --force-rm

# To generate a Container (i.e. run the Image):
docker-compose -f ./docker/docker-compose.yml up -d --force-recreate
```

<!-- Links -->
[DTVF]: https://github.com/cambridge-cares/TheWorldAvatar/wiki/Digital-Twin-Visualisations
[example Mapbox visualisation]: https://github.com/cambridge-cares/TheWorldAvatar/tree/main/web/digital-twin-vis-framework/example-mapbox-vis
[FeatureInfoAgent]: https://github.com/cambridge-cares/TheWorldAvatar/tree/main/Agents/FeatureInfoAgent

<!-- repositories -->
[FeatureInfoAgent subdirectory]: /DTVF/FeatureInfoAgent
[FeatureInfoAgent queries]: FeatureInfoAgent/queries
[DTVF subdirectory]: /DTVF
[icons]: /DTVF/data/icons
[index.html]: index.html
[data.json]: /DTVF/data.json