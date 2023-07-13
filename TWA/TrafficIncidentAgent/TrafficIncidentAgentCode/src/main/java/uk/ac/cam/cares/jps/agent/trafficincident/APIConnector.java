package uk.ac.cam.cares.jps.agent.trafficincident;

import uk.ac.cam.cares.jps.base.exception.JPSRuntimeException;

import org.apache.http.client.HttpResponseException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.Properties;


public class APIConnector {
    private String API_URL;
    private String accountKey;

    public static final String ERROR_MSG = "APIConnector failed while retrieving readings.";
    public static final Logger LOGGER = LogManager.getLogger(APIConnector.class);

    public APIConnector(String URL, String date, String key) {
        this.API_URL = URL;
        this.accountKey = key;
    }

    public APIConnector(String filepath) throws IOException {
        loadAPIConfig(filepath);
    }

    private void loadAPIConfig(String filepath) throws IOException {
        File file = new File(filepath);
        if (!file.exists()) {
            throw new FileNotFoundException("API Config file not found in the path.");
        }

        try (InputStream input = new FileInputStream(file)) {
            Properties prop = new Properties();
            prop.load(input);

            if (prop.containsKey("trafficincident.api_url")) {
                this.API_URL = prop.getProperty("trafficincident.api_url");
            } else {
                throw new IOException("traffic incident api url not specified.");
            }

            if (prop.containsKey("trafficincident.accountKey") && !prop.getProperty("trafficincident.accountKey").equals("")) {
                this.accountKey = prop.getProperty("trafficincident.accountKey");
            } else {
                throw new IOException("traffic incident api account key not specified");
            }
        }
    }

    public JSONObject getReadings() {
        try {
            return retrieveData();
        } catch (IOException e) {
            LOGGER.error(ERROR_MSG);
            return new JSONObject();
        }
    }

    private JSONObject retrieveData() throws IOException, JSONException {
        String path = this.API_URL;
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpGet readRequest = new HttpGet(path);
        readRequest.setHeader("AccountKey", this.accountKey);
        readRequest.setHeader("accept", "application/json");
        CloseableHttpResponse response = httpClient.execute(readRequest);
        int status = response.getStatusLine().getStatusCode();
        if (status == 200) {
            return new JSONObject(EntityUtils.toString(response.getEntity()));
        } else {
            throw new HttpResponseException(status, " Data could not be retrieved.");
        }
    }
}
