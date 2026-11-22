// Runs ONCE, on the first start of the MongoDB container (an empty data volume), as a script of
// /docker-entrypoint-initdb.d. The image has already created the administrator from
// MONGO_INITDB_ROOT_USERNAME and MONGO_INITDB_ROOT_PASSWORD. This script creates the user of the
// application with the least privilege that it needs: the role readWrite on ONE database. The
// user can create the collection and its indexes (the Liquibase changelog does it) and read and
// write documents; it cannot create users or touch another database.
//
// The user is created in the database "admin", so the authentication database stays "admin", the
// default of the application; its rights are on the database of the application.
//
// The values come from the environment of the container, not from the text of the script, so a
// password with quotes, dollar signs or backslashes needs no escaping.
const environment = process.env;
const username = environment.MONGO_APP_USERNAME;
const password = environment.MONGO_APP_PASSWORD;
const database = environment.MONGO_APP_DATABASE;

if (!username || !password || !database) {
  throw new Error('MONGO_APP_USERNAME, MONGO_APP_PASSWORD and MONGO_APP_DATABASE must be set');
}

const admin = db.getSiblingDB('admin');
// The entrypoint runs the script as the administrator; the explicit login does not depend on it.
admin.auth(environment.MONGO_INITDB_ROOT_USERNAME, environment.MONGO_INITDB_ROOT_PASSWORD);

admin.createUser({
  user: username,
  pwd: password,
  roles: [{ role: 'readWrite', db: database }]
});
print(`Created the user "${username}" with the role readWrite on the database "${database}"`);
