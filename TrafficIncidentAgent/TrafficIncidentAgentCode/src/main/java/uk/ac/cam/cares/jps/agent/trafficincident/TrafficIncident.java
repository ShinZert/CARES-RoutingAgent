package uk.ac.cam.cares.jps.agent.trafficincident;


public class TrafficIncident {
    public String incidentType;
    public double latitude;
    public double longitude;
    // message field can be updated during different call, depending on specific scenario
    public String message;
    public long startTime;
    public long endTime;

    public TrafficIncident(String incidentType, double latitude, double longitude, String message, long startTime) {
        this.incidentType = incidentType;
        this.latitude = latitude;
        this.longitude = longitude;
        this.message = message;
        this.startTime = startTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    @Override
    public String toString() {
        return String.format("%s at latitude %f, longitude %f starting from %d", this.incidentType, this.latitude, this.longitude, this.startTime);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj instanceof TrafficIncident) {
            // under the assumption that TrafficIncident with same type, location and start time must be the same (message may get updated during different call)
            TrafficIncident other = (TrafficIncident) obj;
            return this.incidentType.equals(other.incidentType)
                && this.latitude == other.latitude
                && this.longitude == other.longitude
                && this.startTime == other.startTime;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return this.toString().hashCode();
    }
}
