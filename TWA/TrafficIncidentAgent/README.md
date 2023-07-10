# Traffic Incident Agent

This agent downloads almost-real-time traffic-incident data from [http://datamall2.mytransport.sg/ltaodataservice/TrafficIncidents](http://datamall2.mytransport.sg/ltaodataservice/TrafficIncidents), processes data to wanted format, and stores them in Postgre database in the stack.

## Set up

This section specifies the minimum requirement to build the docker image.

This agent uses the Maven repository at [https://maven.pkg.github.com/cambridge-cares/TheWorldAvatar/](https://maven.pkg.github.com/cambridge-cares/TheWorldAvatar/) (in addition to Maven central). You'll need to provide your credentials in single-word text files located like this:

```
credentials/
  repo_password.txt
  repo_username.txt
```

`repo_username.txt` should contain your GitHub username, and `repo_password.txt` contains your GitHub [personal access token](https://docs.github.com/en/github/authenticating-to-github/creating-a-personal-access-token), which must have a 'scope' that [allows you to publish and install packages](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry#authenticating-to-github-packages).

In the same folder, you will also need to have an `api_key.txt` which can be obtained by registering from [Land Transport Data Mall](https://datamall.lta.gov.sg/content/datamall/en/request-for-api.html). The `api_key.txt` file is not directly used, but remember to copy the API key to the `config/api.properties` to ensure the data can be correctly retrieved. The `api_key` is ended with `==` and do not get it confused with Account Key.

You also need to have a copy of your credentials in single-word text files located like below:

```
docker/
  credentials/
    repo_password.txt
    repo_username.txt
```

## IMPORTANT

Due to compatibility issues, the `./stack.sh` and `./docker/entrypoint.sh` **must** be using `LF` instead of `CRLF` in order to run the agent properly. Otherwise, you may encounter the issue of `exec ./entrypoint.sh not found`.

## Build and running

This agent is designed to work with a stack from CMCL. Refer to [this link](https://github.com/cambridge-cares/TheWorldAvatar/tree/main/Deploy/stacks/dynamic/stack-manager) to find out how to set up a stack.

After running the command `./stack.sh build` and then `./stack.sh start <STACK NAME>` at the folder same as the link, you should be able to see a container named as `<STACK NAME>` as you specified earlier. Now you can proceed to build the image here and start it as a service by running the same two commands, but at the current folder.

While you have the container running, you do not need to create any database or table as it is already automated. The PostGIS extension is also automatically enabled. You can view the data in stack. By opening the Adminer (PostgreSQL GUI) at http://localhost:3838/adminer/ui/?username=postgres&pgsql=. Enter `<STACK NAME>-postgis:5432` as the Server and the value from the postgis_password file as the Password. The Database slot is the default `postgres` and the table is named as `TrafficIncident`. The table should include `starttime:bigint`, `endtime:bigint`, `type:character varing`, `message:character varying`, `latitude:double precision`, `longitude:double precision`, `location: geography NULL`, `status:Boolean`.

After having the container running and setting up the table as described, you can run `curl http://localhost:1016/traffic-incident-agent/start` in order to get the agent working. The agent will then extract traffic incident data and store it into Postgres every two minutes. Or you can use Postman to send a query with url `http://localhost:1016/traffic-incident-agent/start` to achieve the same effect.

When the incident no longer appears in the new incidents extracted, it will be marked as complete in Postgres and its endtime updated.
