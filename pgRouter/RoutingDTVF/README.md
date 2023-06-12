# Flood Routing Digital Twin Visualisation Framework (DTVF)

This visualization serves as a proof of concept for routing under flood, isochrone from points of interest, unreachable area.

The instantiated data is visualised using the Digital Twin Visualisation Framework ([DTVF]) version `3.3.4`. The configuration file structure (i.e. `data.json`) is based on the [example Mapbox visualisation].

<img src="readme-example.JPG" alt="Mapbox visualisation" width="100%"/>


## Important Pre-requisites
1) OSM data
Specify bounding box 
https://extract.bbbike.org/


osm2pgrouting
https://github.com/pgRouting/osm2pgrouting


Or it can be downloaded here
https://download.geofabrik.de/

Important note, it is better to 

2) Postgis
https://postgis.net/docs/using_raster_dataman.html

Make sure postgis_raster and pgr_routing is enabled. 

3) Geoserver


### OSM Data

### pgRouting


## Building the Image
The `docker` folder contains the required files to build a Docker Image for the example visualisation. This uses the `dtvf-base-image` image as a base then adds the contents of the `webspace` directory to a volume mounted at `/var/www/html` within the container.

- A valid Mapbox API token must be provided in your `index.html` file.
- A connection to the internet is required to contact remote resources and use the mapping libraries.

Once the requirements have been addressed, the image can be built using the below methods. If changing the visualisation, you'll need to rebuild and rerun the Docker image after and adjustments, or setup a Docker bind mount so that local changes are reflected within the container.

- To build the Image:
  - `docker-compose -f ./docker/docker-compose.yml build --force-rm`

- To generate a Container (i.e. run the Image):
  - `docker-compose -f ./docker/docker-compose.yml up -d --force-recreate`


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