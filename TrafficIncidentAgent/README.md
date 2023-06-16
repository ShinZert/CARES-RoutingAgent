# Traffic Incident Agent

This agent downloads real-time traffic-incident data from [http://datamall2.mytransport.sg/ltaodataservice/TrafficIncidents](http://datamall2.mytransport.sg/ltaodataservice/TrafficIncidents) and stores them in PostgreSQL.

## Building and running

This section specifies the minimum requirement to build the docker image.

This agent uses the Maven repository at [https://maven.pkg.github.com/cambridge-cares/TheWorldAvatar/](https://maven.pkg.github.com/cambridge-cares/TheWorldAvatar/) (in addition to Maven central). You'll need to provide your credentials in single-word text files located like this:

```
credentials/
  repo_username.txt
  repo_password.txt
```

`repo_username.txt` should contain your GitHub username, and `repo_password.txt` contains your GitHub [personal access token](https://docs.github.com/en/github/authenticating-to-github/creating-a-personal-access-token), which must have a 'scope' that [allows you to publish and install packages](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry#authenticating-to-github-packages).

You will also need to have an `api_key.txt` which can be obtained by registering from [Land Transport Data Mall](https://datamall.lta.gov.sg/content/datamall/en/request-for-api.html). Remember to copy the API key to the `config/api.properties` to ensure the data can be correctly retrieved and remember not to publish your key on GitHub.

This agent is designed to work alone. After pasting your key to the correct place as last paragraph, you can simply `docker compose -f "TrafficIncidentAgent/docker-compose.yml" up -d --build` to build the image and host it at the `localhost` with default port number `1016`.

In order to deposit data extracted to Postgres, you need to match this application's configuration with your PostgreSQL's. (format: `field_name: field_default_value_used_here`) Besides the port number to host PostgreSQL, you also need to create `DataBase: traffic-incident-test` using `user: postgres` with `password: Password1`. Inside the `database` created, you need to create a `Table: TrafficIncident` with corresponding columns specified in `./TrafficIncidentAgentCode/src/main/java/uk/ac/cam/cares/jps/agent/trafficincident/APIAgentLauncher.java`.

After running the above docker command, you can send a `POST` query with url `http://localhost:1016/traffic-incident-agent/retrieve` to get the agent running and deposit values into Postgres.

The modelling of the Traffic Incidents is achieved by maintaining a time interval to track the start and end time of the incident. When the incident first appears, the end time field will be left as 0 and only gets updated when the incident is not appearing in the newly queried result. Hence, the accuracy of data needs to be maintained via having regular call of the query.
