var geoserverUrl = "http://127.0.0.1:8081/geoserver";
var selectedPoint = null;

var source = null;
var target = null;


// initialize our map
var map = L.map("map", {
	center: [49.2016, 7.6002],
	zoom: 14 //set the zoom level
});

//add openstreet map baselayer to the map
var OpenStreetMap = L.tileLayer(
	"http://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",
	{
		maxZoom: 19,
		attribution:
			'&copy; <a href="http://www.openstreetmap.org/copyright">OpenStreetMap</a>'
	}
).addTo(map);

// empty geojson layer for the shortes path result
var pathLayer = L.geoJSON(null);
var tspPathLayer = L.geoJSON(null)
// draggable marker for starting point. Note the marker is initialized with an initial starting position
var sourceMarker = L.marker([49.1957016, 7.6080604], {
	draggable: true
})
	.on("dragend", function(e) {
		selectedPoint = e.target.getLatLng();
		getVertex(selectedPoint);
		getRoute();
	})
	.addTo(map);

// draggbale marker for destination point.Note the marker is initialized with an initial destination positon
var targetMarker = L.marker([49.1940527, 7.6094390], {
	draggable: true
})
	.on("dragend", function(e) {
		selectedPoint = e.target.getLatLng();
		getVertex(selectedPoint);
		getRoute();
	})
	.addTo(map);

// function to get nearest vertex to the passed point
function getVertex(selectedPoint) {
	var url = `${geoserverUrl}/wfs?service=WFS&version=1.0.0&request=GetFeature&typeName=routingpirmasens:nearest_vertex&outputformat=application/json&viewparams=lon:${
		selectedPoint.lng
	};lat:${selectedPoint.lat};`;
	$.ajax({
		url: url,
		async: false,
		success: function(data) {
			loadVertex(
				data,
				selectedPoint.toString() === sourceMarker.getLatLng().toString()
			);
		}
	});
}

// function to update the source and target nodes as returned from geoserver for latewr querying
function loadVertex(response, isSource) {
	var features = response.features;
	map.removeLayer(pathLayer);
	if (isSource) {
		source = features[0].properties.id;
	} else {
		target = features[0].properties.id;
	}
}

// function to get the shortest path from the give source and target nodes
function getRoute() {
	var url = `${geoserverUrl}/wfs?service=WFS&version=1.0.0&request=GetFeature&typeName=routingpirmasens:shortest_paths&outputformat=application/json&viewparams=source:${source};target:${target};`;

	$.getJSON(url, function(data) {
		map.removeLayer(pathLayer);
		pathLayer = L.geoJSON(data);
		map.addLayer(pathLayer);
	});
}


function getPoint() {
	var url = 'http://127.0.0.1:8081/geoserver/wfs?' +
    'service=WFS&' +
    'version=1.1.0&' +
    'request=GetFeature&' +
    'typeName=routingpirmasens:pothole&' +
    'outputFormat=application/json';

	fetch(url)
    .then(function(response) {
        return response.json();
    })
	.then(function (data) {
		var customIcon = L.icon({
			iconUrl: 'C:/Users/SPHU01/Dropbox (Cambridge CARES)/PC/Desktop/PSZ Repository/CARES/pgRouter/Pirmasens/Leaflet/pothole.png',
			iconSize: [50, 50], // Set the size of the icon
			// You can customize other icon options here if needed
		});

		// Create a Leaflet GeoJSON layer
		var pointLayer = L.geoJSON(data, {
			pointToLayer: function (feature, latlng) {
				return L.marker(latlng, { icon: customIcon });
			}
		}).addTo(map);
	});
}

//function to plot tspNode
function getTSPNode() {
    var url = 'http://127.0.0.1:8081/geoserver/wfs?' +
        'service=WFS&' +
        'version=1.1.0&' +
        'request=GetFeature&' +
        'typeName=routingpirmasens:tsp_node&' +
        'outputFormat=application/json';

    fetch(url)
        .then(function(response) {
            return response.json();
        })
        .then(function (data) {
            // Create a Leaflet GeoJSON layer
            var pointLayer = L.geoJSON(data, {
                pointToLayer: function (feature, latlng) {
                    // Create a numbered marker icon
                    var customIcon = L.divIcon({
                        className: 'numbered-marker',
                        html: '<div>' + feature.properties.seq + '</div>',
                        iconSize: [30, 30],
                    });

                    return L.marker(latlng, { icon: customIcon });
                }
            }).addTo(map);
        });
}



// function to get the shortest path from the give source and target nodes
function getTSP() {
	var url = `${geoserverUrl}/wfs?service=WFS&version=1.1.0&request=GetFeature&typeName=routingpirmasens:tsp&outputformat=application/json`;

	$.getJSON(url, function(data) {
		map.removeLayer(tspPathLayer);
		tspPathLayer = L.geoJSON(data, {
			style: {
				color: "red"
			}
		});
		map.addLayer(tspPathLayer);
	});

}


getTSP();
getVertex(sourceMarker.getLatLng());
getVertex(targetMarker.getLatLng());
getRoute();
getTSPNode();
getPoint();



// create a canvas element for the chart
var canvas = document.createElement('canvas');
canvas.id = "myChart";
document.getElementById("graph").appendChild(canvas);

// get the context of the canvas
var ctx = canvas.getContext('2d');

// create data for the chart
var data = {
  labels: ["January", "February", "March", "April", "May", "June", "July"],
  datasets: [
    {
      label: "My First Dataset",
      data: [65, 59, 80, 81, 56, 55, 40],
      fill: false,
      backgroundColor: "rgba(75,192,192,0.4)",
      borderColor: "rgba(75,192,192,1)",
      borderWidth: 1
    }
  ]
};

// create options for the chart
var options = {
  scales: {
    yAxes: [{
      ticks: {
        beginAtZero:true
      }
    }]
  }
};

// create the chart
var myChart = new Chart(ctx, {
  type: 'bar',
  data: data,
  options: options
});
