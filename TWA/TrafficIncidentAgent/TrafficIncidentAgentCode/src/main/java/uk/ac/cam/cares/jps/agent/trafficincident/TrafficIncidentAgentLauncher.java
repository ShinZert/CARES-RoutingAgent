package uk.ac.cam.cares.jps.agent.trafficincident;

import uk.ac.cam.cares.jps.base.agent.JPSAgent;

import java.util.Timer;
import java.util.TimerTask;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import org.json.JSONObject;

@WebServlet(urlPatterns = {"/start"})
public class TrafficIncidentAgentLauncher extends JPSAgent {
    @Override
    public JSONObject processRequestParameters(JSONObject requestParams, HttpServletRequest request) {
        JSONObject jsonMessage = new JSONObject();
        Timer timer = new Timer();
        TimerTask task = new TrafficIncidentAgent();
        // perform data extraction for every two minutes
        timer.schedule(task, 0, 1000*60*2);
        return jsonMessage;
    }
}