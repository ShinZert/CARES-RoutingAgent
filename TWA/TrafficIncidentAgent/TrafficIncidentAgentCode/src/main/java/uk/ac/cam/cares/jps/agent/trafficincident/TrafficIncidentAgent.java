package uk.ac.cam.cares.jps.agent.trafficincident;

import uk.ac.cam.cares.jps.base.exception.JPSRuntimeException;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.TimerTask;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.jooq.Field;
import org.json.JSONArray;
import org.json.JSONObject;

public class TrafficIncidentAgent extends TimerTask {
    private final Logger LOGGER = LogManager.getLogger(TrafficIncidentAgent.class);

    public static final String API_VALUES = "TRAFFICINCIDENT_API_PROPERTIES";
    public static final String GET_READINGS_ERROR_MSG = "Error when getting reading.";
    public static final String CONNECTOR_ERROR_MSG = "Error when working with APIConnector.";
    
    public static final ZoneOffset offset= ZoneOffset.UTC;
    long timestamp = System.currentTimeMillis();
    private HashSet<TrafficIncident> ongoingTrafficIncidentSet = new HashSet<>();
    private HashSet<TrafficIncident> newTrafficIncidentSet = new HashSet<>();

    private TrafficIncidentPostgresAgent postgresAgent;

    @Override
    public void run() {
        JSONObject jsonMessage = new JSONObject();
        if(System.getenv(API_VALUES)!=null) {   
            LOGGER.info("Passing Request to API Connector and Postgres Client");
            String apiProperties = System.getenv(API_VALUES);
            
            jsonMessage = initializeAgent(apiProperties);
            jsonMessage.accumulate("Result","values has been extracted");
        } else {
            jsonMessage.put("Result","api or client configuration is missig.");
        }
    }

    /**
     * Initializes the agent by:
     *   - initialize APIconnector with @param apiProperties
     *   - extract readings from the APIconnector initialized
     *   - connect to Postgres and initialize table
     *   - store extracted readings to Postgres
     *   - convert latitude, longitude pair to Geometry point
     *   - compare between current and (previously deemed) ongoing incidents
     *       and mark ended incidents as complete
     */
    public JSONObject initializeAgent(String apiProperties) {     
        JSONObject jsonMessage = new JSONObject();
        // retrieve readings from data API and connector
        APIConnector connector;
        try {
            connector = new APIConnector(apiProperties);
        } catch(IOException e) {
            LOGGER.error(CONNECTOR_ERROR_MSG,e);
            throw new JPSRuntimeException(CONNECTOR_ERROR_MSG,e);
        }

        LOGGER.info("API Connector Object Initialized");
        jsonMessage.accumulate("Result","API Connector object Initialized");

        JSONObject readings;
        try {
            // timestamp records current time to get data from API
            this.timestamp = System.currentTimeMillis();
            readings = connector.getReadings();
        } catch(Exception e) {
            LOGGER.error(GET_READINGS_ERROR_MSG);
            throw new JPSRuntimeException(e.getMessage());
        }

        LOGGER.info(String.format("Retrieved %d incident readings", readings.getJSONArray("value").length()));
        jsonMessage.accumulate("Result","Retrieved "+readings.getJSONArray("value").length()+" incident readings");

        // Get the property values and assign
        setRdbParameters();
        this.postgresAgent.connect();
        this.postgresAgent.createSchemaIfNotExists();

        this.ongoingTrafficIncidentSet = this.postgresAgent.retrieveOngoingIncidents();
        
        JSONArray jsArr = readings.getJSONArray("value");
        this.newTrafficIncidentSet = new HashSet<>();
        LOGGER.info("Adding new traffic incidents to Postgres:");
        for(int i=0; i<jsArr.length(); i++) {
            JSONObject currentEntry = jsArr.getJSONObject(i);
            // Note below the field name follows the API format by LTA data mall
            Double latitude = (Double) currentEntry.get("Latitude");
            Double longitude = (Double) currentEntry.get("Longitude");
            String incidentType = (String) currentEntry.get("Type");
            String message = (String) currentEntry.get("Message");
            timestamp = TrafficIncidentAgent.parseMessageStringToTimestamp(message);
            TrafficIncident curr = new TrafficIncident(incidentType, latitude, 
                longitude, message, timestamp, true);
            this.newTrafficIncidentSet.add(curr);
            // only update when the traffic incident not present
            if (!this.ongoingTrafficIncidentSet.contains(curr)) {
                // database needs to be created in PgAdmin beforehand
                this.postgresAgent.insertValuesIntoPostgres(curr);
                LOGGER.info(curr);
            }
        }
        this.postgresAgent.convertLongLatPairToGeom();
        LOGGER.info("Above is/are newly occurred traffic incidents.");
        
        LOGGER.info("Checking whether any traffic incident has ended ...");
        for (TrafficIncident ti : this.ongoingTrafficIncidentSet) {
            if (!this.newTrafficIncidentSet.contains(ti)) {
                ti.setEndTime(this.timestamp);
                ti.setStatus(false);
                LOGGER.info("Updating endtime for " + ti.toString());
                this.postgresAgent.updateTrafficIncidentEndTimeStatusPostgres(ti);
            }
        }
        LOGGER.info("Above is/are ended traffic incidents.");
        this.postgresAgent.createOrReplaceView();
        this.postgresAgent.disconnect();
        return jsonMessage;
    }

    private void setRdbParameters() {
        EndpointConfig endpointConfig = new EndpointConfig();
        this.postgresAgent = new TrafficIncidentPostgresAgent(
            endpointConfig.getDbUrl(),
            endpointConfig.getDbUser(),
            endpointConfig.getDbPassword());
    }

    /**
     * Parses the @param message and returns the start time as specified
     */
    private static long parseMessageStringToTimestamp(String message) {
        // eg: (15/6)14:25 Roadworks on ECP (towards City) after Fort Rd Exit. Avoid lane 1.
        int year = Year.now().getValue();
        String dateTimeRawString = message.trim().split(" ")[0];
        String dateRawString = dateTimeRawString.split("\\)")[0];
        String timeRawString = dateTimeRawString.split("\\)")[1];
        int month = Integer.parseInt(dateRawString.split("/")[1]);
        int day = Integer.parseInt(dateRawString.split("/")[0].substring(1));
        int hour = Integer.parseInt(timeRawString.split(":")[0]);
        int minute = Integer.parseInt(timeRawString.split(":")[1]);
        OffsetDateTime result = OffsetDateTime.of(year, month, day, hour, minute, 0, 0, TrafficIncidentAgent.offset);
        return result.toInstant().getEpochSecond();
    }
}
