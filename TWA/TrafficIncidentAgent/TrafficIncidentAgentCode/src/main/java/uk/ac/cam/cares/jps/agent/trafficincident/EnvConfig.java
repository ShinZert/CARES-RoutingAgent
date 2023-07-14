package uk.ac.cam.cares.jps.agent.trafficincident;

public class EnvConfig {
    public static final String PYTHON_SERVICE_URL = System.getenv("PYTHON_SERVICE_URL");
    public static final String DATABASE = System.getenv("DATABASE");
    public static final String SIMULATION_DIR = System.getenv("SIMULATION_DIR");
    public static final String FILE_SERVER = System.getenv("FILE_SERVER_URL");
    public static final String GEOSERVER_WORKSPACE = System.getenv("GEOSERVER_WORKSPACE");
    public static final String SOURCE_LAYER = System.getenv("SOURCE_LAYER");
    public static final String VIS_FOLDER = System.getenv("VIS_FOLDER");
    public static final String GEOSERVER_URL = System.getenv("GEOSERVER_URL");

    private EnvConfig() {
        throw new IllegalStateException();
    }
}
