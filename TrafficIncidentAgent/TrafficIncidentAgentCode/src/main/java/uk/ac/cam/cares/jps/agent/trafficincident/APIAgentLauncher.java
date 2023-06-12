package uk.ac.cam.cares.jps.agent.trafficincident;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.XML;

import uk.ac.cam.cares.jps.base.exception.JPSRuntimeException;
import uk.ac.cam.cares.jps.base.agent.JPSAgent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.text.SimpleDateFormat;
import java.util.*;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.InsertValuesStepN;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;

import javax.servlet.*;

@WebServlet(urlPatterns = {"/retrieve"})
public class APIAgentLauncher extends JPSAgent {
    private final Logger LOGGER = LogManager.getLogger(APIAgentLauncher.class);

    public static final String Key_APIProp = "apiProperties";
    public static final String Key_ClientProp = "clientProperties";

    public static final String ARGUMENT_MISMATCH_MSG = "Argument mistmatch";
    public static final String AGENT_ERROR_MSG = "Th road obstruction API input agent could not be constructed.";
    public static final String GET_READINGS_ERROR_MSG = "Error when getting reading.";
    public static final String CONNECTOR_ERROR_MSG = "Error when working with APIConnector.";
    public static final String POSTGRES_INITIALIZATION_ERROR_MSG = "Error when initializing the Postgres";

    private String rdbURL = null; 
	private String rdbUser = null;
	private String rdbPassword = null;
    private Connection conn = null;
    // TODO: convert to enum
    long timestamp = System.currentTimeMillis();
    String[] typeArray;
    Double[] latitudeArray;
    Double[] longitudeArray;
    String[] messageArray;
    private DSLContext context;
    private static final SQLDialect dialect = SQLDialect.POSTGRES;
    private static final Field<Long> timestampColumn = DSL.field(DSL.name("timestamp"), Long.class);
    private static final Field<String> typeColumn = DSL.field(DSL.name("Type"), String.class);
    // TODO: convert to geo location instead of latitude, longitude pair
    private static final Field<Double> latitudeColumn = DSL.field(DSL.name("Latitude"), double.class);
    private static final Field<Double> longitudeColumn = DSL.field(DSL.name("Longitude"), double.class);
    private static final Field<String> messageColumn = DSL.field(DSL.name("Message"), String.class);

    // eg (sent in Postman) http://localhost:1016/traffic-incident-agent/retrieve?apiProperties=TRAFFICINCIDENT_API_PROPERTIES&clientProperties=TRAFFICINCIDENT_CLIENT_PROPERTIES
    @Override
    public JSONObject processRequestParameters(JSONObject requestParams, HttpServletRequest request) {
        JSONObject jsonMessage = new JSONObject();
        System.out.print(requestParams.getString(Key_ClientProp));
        System.out.print(requestParams.getString(Key_APIProp));
        if(validateInput(requestParams)) {   
            LOGGER.info("Passing Request to API Connector and Postgres Client");
            String clientProperties = System.getenv(requestParams.getString(Key_ClientProp));
            String apiProperties = System.getenv(requestParams.getString(Key_APIProp));
            
            String[] args = new String []{apiProperties, clientProperties};
            jsonMessage = initializeAgent(args);
            jsonMessage.accumulate("Result","values has been extracted");

            requestParams = jsonMessage;

        } else {
            jsonMessage.put("Result","Request Parameters not defined correctly");
            requestParams = jsonMessage;
        }
        return requestParams;
    }

    public boolean validateInput(JSONObject requestParams) {
        boolean validate = true;
        if (!requestParams.isEmpty()) {
            validate = requestParams.has(Key_ClientProp) && requestParams.has(Key_APIProp);
            System.out.print("Hello");
            if (validate) {
                String clientProperties = requestParams.getString(Key_ClientProp);
                String apiproperties = requestParams.getString(Key_APIProp);

                if ((System.getenv(clientProperties)==null) || (System.getenv(apiproperties)==null)) {
                    validate=false;
                }
            }
        }

        return validate;
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
            readings = connector.getReadings();
        } catch(Exception e) {
            LOGGER.error(GET_READINGS_ERROR_MSG);
            throw new JPSRuntimeException(e.getMessage());
        }
        
        LOGGER.info(String.format("Retrieved readings for %d incidents", readings.length()));
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
        for(int i=0; i<jsArr.length(); i++) {
            JSONObject currentEntry = jsArr.getJSONObject(i);

            Double latitude = (Double) currentEntry.get("Latitude");
            Double longitude = (Double) currentEntry.get("Longitude");
            String incidentType = (String) currentEntry.get("Type");
            String message = (String) currentEntry.get("Message");
            // database needs to be created beforehand
            this.insertValuesIntoPostgres(this.timestamp, incidentType, latitude, longitude, message);
        }

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

    protected void insertValuesIntoPostgres(Object timestamp, String type, Double latitude, Double longitude, String message) {
        Table<?> table = DSL.table(DSL.name("TrafficIncident"));
        InsertValuesStepN<?> insertValueStep = (InsertValuesStepN<?>) context.insertInto(table, timestampColumn, typeColumn, latitudeColumn, longitudeColumn, messageColumn);
        insertValueStep = insertValueStep.values(timestamp, type, latitude, longitude, message);

        insertValueStep.execute();
    }
}
