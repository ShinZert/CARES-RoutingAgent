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

    public static final ZoneOffset offset= ZoneOffset.UTC;
    long timestamp = System.currentTimeMillis();
    private HashSet<TrafficIncident> pastTrafficIncidentSet = new HashSet<>();
    private HashSet<TrafficIncident> ongoingTrafficIncidentSet = new HashSet<>();

    // Postgres related
    private String rdbURL = null; 
	private String rdbUser = null;
	private String rdbPassword = null;
    private Connection conn = null;
    private DSLContext context;
    private static final SQLDialect dialect = SQLDialect.POSTGRES;
    private static final Field<Long> startTimeColumn = DSL.field(DSL.name("startTime"), Long.class);
    private static final Field<Long> endTimeColumn = DSL.field(DSL.name("endTime"), Long.class);
    private static final Field<String> typeColumn = DSL.field(DSL.name("Type"), String.class);
    // TODO: convert to geo location instead of latitude, longitude pair -> execute sql query
    private static final Field<Double> latitudeColumn = DSL.field(DSL.name("Latitude"), double.class);
    private static final Field<Double> longitudeColumn = DSL.field(DSL.name("Longitude"), double.class);
    private static final Field<String> messageColumn = DSL.field(DSL.name("Message"), String.class);

    // eg (sent in Postman) http://localhost:1016/traffic-incident-agent/retrieve
    @Override
    public JSONObject processRequestParameters(JSONObject requestParams, HttpServletRequest request) {
        JSONObject jsonMessage = new JSONObject();
        if(validateConfig()) {   
            LOGGER.info("Passing Request to API Connector and Postgres Client");
            String clientProperties = System.getenv(CLIENT_VALUES);
            String apiProperties = System.getenv(API_VALUES);
            
            String[] args = new String []{apiProperties, clientProperties};
            jsonMessage = initializeAgent(args);
            jsonMessage.accumulate("Result","values has been extracted");

            requestParams = jsonMessage;

        } else {
            jsonMessage.put("Result","api or client configuration is missig.");
            requestParams = jsonMessage;
        }
        return requestParams;
    }

    public boolean validateConfig() {
        return (System.getenv(CLIENT_VALUES)!=null) && (System.getenv(API_VALUES)!=null);
    }

    public JSONObject initializeAgent(String[] args) {
        if (args.length!=2) {
            LOGGER.error(ARGUMENT_MISMATCH_MSG);
            throw new JPSRuntimeException(ARGUMENT_MISMATCH_MSG);
        }

        LOGGER.debug("Launcher called with the following files: " + String.join(" ",args));       
        JSONObject jsonMessage = new JSONObject();

        // retrieve readings from data API and connector
        APIConnector connector;
        try {
            connector = new APIConnector(args[0]);
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


        // initialize Postgres connection
        InputStream input;
        Properties prop;
        try {
            input = new FileInputStream(args[1]);
            prop = new Properties();
            prop.load(input);
        } catch (IOException e) {
            LOGGER.error(POSTGRES_INITIALIZATION_ERROR_MSG);
            throw new JPSRuntimeException(POSTGRES_INITIALIZATION_ERROR_MSG);
        }

        // Get the property values and assign
        if (prop.containsKey("db.url")) {
            setRdbURL(prop.getProperty("db.url"));
        } else {
            throw new JPSRuntimeException("Properties file is missing \"db.url=<rdb_url>\" ");
        }
        if (prop.containsKey("db.user")) {
            setRdbUser(prop.getProperty("db.user"));
        } else {
            throw new JPSRuntimeException("Properties file is missing \"db.user=<rdb_username>\" ");
        }
        if (prop.containsKey("db.password")) {
            setRdbPassword(prop.getProperty("db.password"));
        } else {
            throw new JPSRuntimeException("Properties file is missing \"db.password=<rdb_password>\" ");
        }
        connect();

        JSONArray jsArr = readings.getJSONArray("value");
        this.ongoingTrafficIncidentSet = new HashSet<>();
        LOGGER.info("Adding new traffic incidents to Postgres:");
        for(int i=0; i<jsArr.length(); i++) {
            JSONObject currentEntry = jsArr.getJSONObject(i);

            Double latitude = (Double) currentEntry.get("Latitude");
            Double longitude = (Double) currentEntry.get("Longitude");
            String incidentType = (String) currentEntry.get("Type");
            String message = (String) currentEntry.get("Message");
            timestamp = APIAgentLauncher.parseMessageStringToTimestamp(message);
            TrafficIncident curr = new TrafficIncident(incidentType, latitude, 
                longitude, message, timestamp);
            this.ongoingTrafficIncidentSet.add(curr);
            // only update when the traffic incident not present
            if (!this.pastTrafficIncidentSet.contains(curr)) {
                // database needs to be created in PgAdmin beforehand
                this.insertValuesIntoPostgres(curr);
                LOGGER.info(curr);
            }
        }
        LOGGER.info("Above is/are newly occurred traffic incidents.");

        LOGGER.info("Checking whether any traffic incident has ended ...");
        for (TrafficIncident ti : this.pastTrafficIncidentSet) {
            if (!this.ongoingTrafficIncidentSet.contains(ti)) {
                // TODO: decide when we mark the end time of the event
                ti.setEndTime(this.timestamp);
                LOGGER.info(ti);
                // TODO: find past records from Postgres and update its end time
            }
        }
        LOGGER.info("Above is/are ended traffic incidents.");
        this.pastTrafficIncidentSet = this.ongoingTrafficIncidentSet;
        return jsonMessage;
    }

    private void setRdbURL(String rdbURL) {
		this.rdbURL = rdbURL;
	}
	
	private void setRdbUser(String user) {
		this.rdbUser = user;
	}
	
	private void setRdbPassword(String password) {
		this.rdbPassword = password;
	}

    protected void connect() {
        try {
            if (this.conn == null || this.conn.isClosed()) {
                // Load required driver
                Class.forName("org.postgresql.Driver");
                // Connect to DB (using static connection and context properties)
                this.conn = DriverManager.getConnection(this.rdbURL, this.rdbUser, this.rdbPassword);
                this.context = DSL.using(this.conn, dialect);
                System.out.println("Connecting successful: " + this.rdbURL); 
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            System.out.println("Connecting failed: " + this.rdbURL);
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

    protected void insertValuesIntoPostgres(TrafficIncident trafficIncident) {
        Table<?> table = DSL.table(DSL.name("TrafficIncident"));
        InsertValuesStepN<?> insertValueStep = (InsertValuesStepN<?>) context.insertInto(table, startTimeColumn, endTimeColumn, typeColumn, latitudeColumn, longitudeColumn, messageColumn);
        insertValueStep = insertValueStep.values(trafficIncident.startTime, trafficIncident.endTime, trafficIncident.incidentType, 
            trafficIncident.latitude, trafficIncident.longitude, trafficIncident.message);

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
}
