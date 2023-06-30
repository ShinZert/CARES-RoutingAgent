package uk.ac.cam.cares.jps.agent.trafficincident;

import uk.ac.cam.cares.jps.base.exception.JPSRuntimeException;
import uk.ac.cam.cares.jps.base.agent.JPSAgent;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.*;
import java.util.*;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.InsertValuesStepN;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.json.JSONArray;
import org.json.JSONObject;
import org.postgis.Point;

@WebServlet(urlPatterns = {"/retrieve"})
public class APIAgentLauncher extends JPSAgent {
    private final Logger LOGGER = LogManager.getLogger(APIAgentLauncher.class);

    public static final String API_VALUES = "TRAFFICINCIDENT_API_PROPERTIES";
    public static final String CLIENT_VALUES = "TRAFFICINCIDENT_CLIENT_PROPERTIES";

    public static final String ARGUMENT_MISMATCH_MSG = "Argument mistmatch";
    public static final String AGENT_ERROR_MSG = "The road obstruction API input agent could not be constructed.";
    public static final String GET_READINGS_ERROR_MSG = "Error when getting reading.";
    public static final String CONNECTOR_ERROR_MSG = "Error when working with APIConnector.";
    public static final String POSTGRES_INITIALIZATION_ERROR_MSG = "Error when initializing the Postgres";
    public static final String SQL_UPDATE_ERROR_MSG = "Fail to update the record";

    public static final ZoneOffset offset= ZoneOffset.UTC;
    long timestamp = System.currentTimeMillis();
    private HashSet<TrafficIncident> ongoingTrafficIncidentSet = new HashSet<>();
    private HashSet<TrafficIncident> newTrafficIncidentSet = new HashSet<>();

    // Postgres related
    private String rdbUrl = null; 
	private String rdbUser = null;
	private String rdbPassword = null;
    private Connection conn = null;
    private DSLContext context;
    private static final SQLDialect dialect = SQLDialect.POSTGRES;
    private static final Field<Long> startTimeColumn = DSL.field(DSL.name("startTime"), Long.class);
    private static final Field<Long> endTimeColumn = DSL.field(DSL.name("endTime"), Long.class);
    private static final Field<String> typeColumn = DSL.field(DSL.name("Type"), String.class);
    private static final Field<Double> latitudeColumn = DSL.field(DSL.name("Latitude"), double.class);
    private static final Field<Double> longitudeColumn = DSL.field(DSL.name("Longitude"), double.class);
    private static final Field<String> messageColumn = DSL.field(DSL.name("Message"), String.class);
    private static final Field<Boolean> statusColumn = DSL.field(DSL.name("Status"), Boolean.class);

    // eg (sent in Postman) POST http://localhost:1016/traffic-incident-agent/retrieve
    @Override
    public JSONObject processRequestParameters(JSONObject requestParams, HttpServletRequest request) {
        JSONObject jsonMessage = new JSONObject();
        if(System.getenv(API_VALUES)!=null) {   
            LOGGER.info("Passing Request to API Connector and Postgres Client");
            String apiProperties = System.getenv(API_VALUES);
            
            jsonMessage = initializeAgent(apiProperties);
            jsonMessage.accumulate("Result","values has been extracted");

            requestParams = jsonMessage;

        } else {
            jsonMessage.put("Result","api or client configuration is missig.");
            requestParams = jsonMessage;
        }
        return requestParams;
    }

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
        connect();

        this.ongoingTrafficIncidentSet = retrieveOngoingIncidents();
        
        JSONArray jsArr = readings.getJSONArray("value");
        this.newTrafficIncidentSet = new HashSet<>();
        LOGGER.info("Adding new traffic incidents to Postgres:");
        for(int i=0; i<jsArr.length(); i++) {
            JSONObject currentEntry = jsArr.getJSONObject(i);

            Double latitude = (Double) currentEntry.get("Latitude");
            Double longitude = (Double) currentEntry.get("Longitude");
            String incidentType = (String) currentEntry.get("Type");
            String message = (String) currentEntry.get("Message");
            timestamp = APIAgentLauncher.parseMessageStringToTimestamp(message);
            TrafficIncident curr = new TrafficIncident(incidentType, latitude, 
                longitude, message, timestamp, true);
            this.newTrafficIncidentSet.add(curr);
            // only update when the traffic incident not present
            if (!this.ongoingTrafficIncidentSet.contains(curr)) {
                // database needs to be created in PgAdmin beforehand
                this.insertValuesIntoPostgres(curr);
                LOGGER.info(curr);
            }
        }
        this.convertLongLatPairToGeom();
        LOGGER.info("Above is/are newly occurred traffic incidents.");
        
        LOGGER.info("Checking whether any traffic incident has ended ...");
        for (TrafficIncident ti : this.ongoingTrafficIncidentSet) {
            if (!this.newTrafficIncidentSet.contains(ti)) {
                // TODO: decide when we mark the end time of the event
                ti.setEndTime(this.timestamp);
                ti.setStatus(false);
                LOGGER.info("Updating endtime for " + ti.toString());
                this.updateTrafficIncidentEndTimeStatusPostgres(ti);
            }
        }
        LOGGER.info("Above is/are ended traffic incidents.");
        return jsonMessage;
    }

    private void setRdbParameters() {
        EndpointConfig endpointConfig = new EndpointConfig();
        this.rdbUrl = endpointConfig.getDbUrl();
        this.rdbUser = endpointConfig.getDbUser();
        this.rdbPassword = endpointConfig.getDbPassword();
    }

    protected void connect() {
        try {
            if (this.conn == null || this.conn.isClosed()) {
                // Load required driver
                Class.forName("org.postgresql.Driver");
                // Connect to DB (using static connection and context properties)
                this.conn = DriverManager.getConnection(this.rdbUrl, this.rdbUser, this.rdbPassword);
                this.context = DSL.using(this.conn, dialect);
                System.out.println("Connecting successful: " + this.rdbUrl); 
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            System.out.println("Connecting failed: " + this.rdbUrl);
            throw new JPSRuntimeException("Establishing database connection failed");
        }
    }

	protected void disconnect() {
		try {
			conn.close();
			System.out.println("Disconnecting successful"); 
		} catch (Exception e) {
			LOGGER.error(e.getMessage());
			System.out.println("Disconnecting failed");
			throw new JPSRuntimeException("Closing database connection failed");
		}
	}

    public HashSet<TrafficIncident> retrieveOngoingIncidents() {
        String sql = "SELECT * FROM \"TrafficIncident\" WHERE \"Status\" = \'TRUE\'";
        HashSet<TrafficIncident> ongoingTrafficIncidentSet = new HashSet<>();
        ResultSet rs;
        try {
            PreparedStatement statement = this.conn.prepareStatement(sql);
            rs = statement.executeQuery();
            while (rs.next()) {
                String type = rs.getString("Type");
                Long startTime = rs.getLong("startTime");
                Long endTime = rs.getLong("endTime");
                Double latitude = rs.getDouble("Latitude");
                Double longitude = rs.getDouble("Longitude");
                String message = rs.getString("Message");
                Boolean status = rs.getBoolean("Status");
                TrafficIncident curr = new TrafficIncident(type, latitude, longitude, message, startTime, status);
                ongoingTrafficIncidentSet.add(curr);
            }
        } catch (SQLException e) {
            LOGGER.error(SQL_UPDATE_ERROR_MSG, e);
            throw new JPSRuntimeException(e.getMessage());
        }
        
        return ongoingTrafficIncidentSet;
    }

    protected void insertValuesIntoPostgres(TrafficIncident trafficIncident) {
        Table<?> table = DSL.table(DSL.name("TrafficIncident"));
        InsertValuesStepN<?> insertValueStep = (InsertValuesStepN<?>) context.insertInto(table, startTimeColumn, endTimeColumn, typeColumn, latitudeColumn, longitudeColumn, messageColumn, statusColumn);
        insertValueStep = insertValueStep.values(trafficIncident.startTime, trafficIncident.endTime, trafficIncident.incidentType, 
            trafficIncident.latitude, trafficIncident.longitude, trafficIncident.message, trafficIncident.status);

        insertValueStep.execute();
    }

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
        OffsetDateTime result = OffsetDateTime.of(year, month, day, hour, minute, 0, 0, APIAgentLauncher.offset);
        return result.toInstant().getEpochSecond();
    }

    private void updateTrafficIncidentEndTimeStatusPostgres(TrafficIncident trafficIncident) {
        String sql = "UPDATE \"TrafficIncident\" SET \"endTime\" = ?, \"Status\" = ? WHERE \"Type\" = ? and \"startTime\" = ? and \"Latitude\" = ? and \"Longitude\" = ?";
        try {
            PreparedStatement statement = this.conn.prepareStatement(sql);
            statement.setLong(1, trafficIncident.endTime);
            statement.setBoolean(2, trafficIncident.status);
            statement.setString(3, trafficIncident.incidentType);
            statement.setLong(4, trafficIncident.startTime);
            statement.setDouble(5, trafficIncident.latitude);
            statement.setDouble(6, trafficIncident.longitude);
            LOGGER.debug(statement);
            int rowAffected = statement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error(SQL_UPDATE_ERROR_MSG, e);
            throw new JPSRuntimeException(e.getMessage());
        }
        
        // LOGGER.info("Update end time for " + trafficIncident.toString())
    }

    /**
     * Adds location field for all records in TrafficIncident table without location based on longitude latitude column
     */
    private void convertLongLatPairToGeom() {
        // WSG4326 coordinates used in this case
        // SQL command below needs to run before calling this function
        // ALTER TABLE TrafficIncident ADD COLUMN location GEOMETRY(point, 4326);
        String sql = "UPDATE \"TrafficIncident\" SET \"location\" = ST_SETSRID(ST_MakePoint(\"TrafficIncident\".\"Longitude\", \"TrafficIncident\".\"Latitude\"), 4326) WHERE \"location\" IS NULL";
        try {
            PreparedStatement statement = this.conn.prepareStatement(sql);
            statement.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error(SQL_UPDATE_ERROR_MSG, e);
            throw new JPSRuntimeException(e.getMessage());
        }
    }
}
